package com.stock.system.agent;

import com.stock.system.agent.service.ResearchExperimentBudget;
import com.stock.system.agent.service.ResearchModelExecution;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Function;

/** WebClient 的编码后请求守卫。执行对象显式经 Reactor Context 传递，不依赖网络线程的 ThreadLocal。 */
final class ResearchModelStreamConnector implements ClientHttpConnector {
    private final ClientHttpConnector delegate = new JdkClientHttpConnector(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
    private final ObjectProvider<ResearchExperimentBudget> budgets;

    ResearchModelStreamConnector(ObjectProvider<ResearchExperimentBudget> budgets) { this.budgets = budgets; }

    @Override public Mono<ClientHttpResponse> connect(HttpMethod method, URI uri,
                                                     Function<? super ClientHttpRequest, Mono<Void>> callback) {
        return Mono.deferContextual(context -> {
            var execution = context.<ResearchModelExecution>getOrEmpty(ResearchModelExecution.class)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESEARCH_AGENT_INVALID_RESULT));
            return delegate.connect(method, uri, request -> callback.apply(new ClientHttpRequestDecorator(request) {
                @Override public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                    return DataBufferUtils.join(body, 65_536)
                            .switchIfEmpty(Mono.error(new BusinessException(ErrorCode.RESEARCH_AGENT_INVALID_RESULT)))
                            .flatMap(buffer -> {
                                // join 对单个 Mono 缓冲区可能直接返回，仍须显式检查编码后的长度。
                                if (buffer.readableByteCount() > 65_536) {
                                    DataBufferUtils.release(buffer);
                                    return Mono.error(new BusinessException(ErrorCode.RESEARCH_AGENT_SOURCE_LIMIT_EXCEEDED));
                                }
                                byte[] encoded = new byte[buffer.readableByteCount()];
                                try { buffer.read(encoded); } finally { DataBufferUtils.release(buffer); }
                                // 预留可能核对文件账本；不在 HTTP I/O 线程执行阻塞操作。
                                return Mono.fromRunnable(() -> execution.dispatch(budgets.getIfAvailable(), encoded.length))
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .then(Mono.defer(() -> super.writeWith(Mono.just(bufferFactory().wrap(encoded)))));
                            }).onErrorMap(DataBufferLimitException.class,
                                    ignored -> new BusinessException(ErrorCode.RESEARCH_AGENT_SOURCE_LIMIT_EXCEEDED));
                }
                @Override public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                    return writeWith(Flux.from(body).concatMap(Flux::from));
                }
            })).doOnNext(response -> execution.upstreamStatus(response.getStatusCode().value()));
        });
    }
}
