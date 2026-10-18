package com.stock.system.agent.service;

import com.stock.system.agent.mapper.ResearchAgentRecordMapper.Target;
import com.stock.system.agent.vo.ResearchAgentResponseVO.Run;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.stock.dto.ResearchSourceFilter;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/** 有界 MVC 推送。连接关闭只移除订阅；停止必须走带权限检查的独立接口。 */
@Service
public class ResearchAgentStreamService {
    private final ResearchAgentService service;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new SynchronousQueue<>(), factory("research-agent-stream-"), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(factory("research-agent-heartbeat-"));
    private final Map<Key, Flight> flights = new HashMap<>();

    public ResearchAgentStreamService(ResearchAgentService service) { this.service = service; }

    public SseEmitter open(Target target, String requestId, String question, ResearchSourceFilter filter) {
        service.verifyAccess(target);
        String id = normalize(requestId);
        var command = new Command(target, question, filter);
        var key = new Key(target.user(), id);
        synchronized (flights) {
            Flight flight = flights.get(key);
            if (flight != null && !flight.command.equals(command))
                throw new BusinessException(ErrorCode.RESEARCH_AGENT_REQUEST_CONFLICT);
            if (flight != null) return flight.attach();
            flight = new Flight(key, command);
            SseEmitter emitter = flight.attach();
            flights.put(key, flight);
            try { workers.execute(flight); }
            catch (RejectedExecutionException busy) {
                flights.remove(key);
                flight.close();
                throw new BusinessException(ErrorCode.RESEARCH_AGENT_BUSY);
            }
            return emitter;
        }
    }

    public Cancellation cancel(Target target, String requestId) {
        service.verifyAccess(target);
        String id = normalize(requestId);
        Flight flight;
        synchronized (flights) {
            flight = flights.get(new Key(target.user(), id));
            if (flight != null) {
                if (!flight.command.target.equals(target)) throw new BusinessException(ErrorCode.RESEARCH_AGENT_RUN_NOT_FOUND);
                flight.cancelRequested = true;
            }
        }
        try { return new Cancellation(id, service.cancel(target, id)); }
        catch (BusinessException failure) {
            // 资料读取与 begin 之间的停止由同一 flight 记住；创建运行后立即检查。
            if (flight != null && failure.getErrorCode() == ErrorCode.RESEARCH_AGENT_RUN_NOT_FOUND)
                return new Cancellation(id, null);
            throw failure;
        }
    }

    public record Cancellation(String requestId, Run run) {}
    private record Key(long user, String requestId) {}
    private record Command(Target target, String question, ResearchSourceFilter filter) {}
    private record Progress(String requestId, long sequence, Long runId, ResearchAgentProgress.Stage stage) {}
    private record Completed(String requestId, long sequence, Long runId, Run result) {}
    private record Failed(String requestId, long sequence, Long runId, String errorCode, String message) {}

    private final class Flight implements Runnable, ResearchAgentProgress {
        private final Key key;
        private final Command command;
        private final List<SseEmitter> subscribers = new ArrayList<>();
        private volatile boolean cancelRequested;
        private Long runId;
        private long sequence;
        private Stage stage = Stage.READING;
        private boolean closed;
        private ScheduledFuture<?> heartbeatTask;

        private Flight(Key key, Command command) { this.key = key; this.command = command; }

        synchronized SseEmitter attach() {
            if (closed || subscribers.size() >= 4) throw new BusinessException(ErrorCode.RESEARCH_AGENT_BUSY);
            var emitter = new SseEmitter(75_000L);
            subscribers.add(emitter);
            emitter.onCompletion(() -> detach(emitter));
            emitter.onTimeout(() -> { detach(emitter); emitter.complete(); });
            emitter.onError(unused -> detach(emitter));
            send(emitter, "progress", new Progress(key.requestId, ++sequence, runId, stage));
            return emitter;
        }

        synchronized void detach(SseEmitter emitter) { subscribers.remove(emitter); }
        @Override public boolean cancellationRequested() { return cancelRequested; }
        @Override public synchronized void bound(long runId) { this.runId = runId; stage(stage); }
        @Override public synchronized void stage(Stage stage) {
            if (closed) return;
            this.stage = stage;
            var event = new Progress(key.requestId, ++sequence, runId, stage);
            for (var emitter : List.copyOf(subscribers)) send(emitter, "progress", event);
        }
        private void send(SseEmitter emitter, String name, Object data) {
            try { emitter.send(SseEmitter.event().name(name).data(data)); }
            // IOException 由 Servlet 容器收尾；不再对失效连接 completeWithError。
            catch (IOException | IllegalStateException disconnected) { detach(emitter); }
        }
        private synchronized void beat() {
            for (var emitter : List.copyOf(subscribers)) {
                try { emitter.send(SseEmitter.event().comment("keep-alive")); }
                catch (IOException | IllegalStateException disconnected) { detach(emitter); }
            }
        }
        @Override public void run() {
            heartbeatTask = heartbeat.scheduleAtFixedRate(this::beat, 8, 8, TimeUnit.SECONDS);
            try {
                Run run = command.question == null
                        ? service.summarize(command.target, key.requestId, command.filter, this)
                        : service.ask(command.target, key.requestId, command.question, command.filter, this);
                // 输出前再检查当前权限及依赖，不能把缓存的旧正文直接推送。
                run = service.status(command.target, run.runId());
                synchronized (this) {
                    var event = new Completed(key.requestId, ++sequence, run.runId(), run);
                    for (var emitter : List.copyOf(subscribers)) send(emitter, "result", event);
                }
            } catch (BusinessException failure) { failed(failure.getErrorCode()); }
            catch (RuntimeException failure) { failed(ErrorCode.RESEARCH_AGENT_UNAVAILABLE); }
            finally {
                close();
                synchronized (flights) { flights.remove(key, this); }
            }
        }
        private synchronized void failed(ErrorCode code) {
            var event = new Failed(key.requestId, ++sequence, runId, code.name(), code.message());
            for (var emitter : List.copyOf(subscribers)) send(emitter, "failure", event);
        }
        synchronized void close() {
            closed = true;
            if (heartbeatTask != null) heartbeatTask.cancel(false);
            for (var emitter : List.copyOf(subscribers)) emitter.complete();
            subscribers.clear();
        }
    }

    private static ThreadFactory factory(String prefix) {
        var sequence = new java.util.concurrent.atomic.AtomicInteger();
        return task -> { var thread = new Thread(task, prefix + sequence.incrementAndGet()); thread.setDaemon(true); return thread; };
    }
    private static String normalize(String id) {
        try {
            if (id == null || id.length() != 36) throw new IllegalArgumentException();
            String normalized = UUID.fromString(id).toString();
            if (!normalized.equalsIgnoreCase(id)) throw new IllegalArgumentException();
            return normalized;
        } catch (IllegalArgumentException invalid) { throw new BusinessException(ErrorCode.INVALID_REQUEST); }
    }
    @PreDestroy public void shutdown() { workers.shutdown(); heartbeat.shutdown(); }
}
