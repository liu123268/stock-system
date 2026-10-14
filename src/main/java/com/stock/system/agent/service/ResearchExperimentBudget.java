package com.stock.system.agent.service;

import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** 仅隔离 test 真实模型验收使用，与已授权累计 10 元实验 JSONL 共用文件锁和余额。 */
@Component
@Profile("test")
@ConditionalOnProperty(name = "stock-system.agent.experiment-ledger")
public class ResearchExperimentBudget implements AutoCloseable {
    private final ObjectMapper json;
    private final FileChannel channel;
    private final FileLock lock;
    private final Map<String, BigDecimal> held = new HashMap<>();
    private BigDecimal spent = BigDecimal.ZERO;
    private static final BigDecimal LIMIT = BigDecimal.TEN;
    private static final BigDecimal RESERVE = new BigDecimal("4.016");

    public ResearchExperimentBudget(ObjectMapper json, @Value("${stock-system.agent.experiment-ledger}") String path) throws Exception {
        this.json = json;
        channel = FileChannel.open(Path.of(path), StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            acquired = channel.tryLock();
            if (acquired == null || channel.size() > 1_048_576) throw new IllegalStateException("Experiment ledger unavailable");
            var bytes = ByteBuffer.allocate((int) channel.size());
            while (bytes.hasRemaining() && channel.read(bytes) >= 0) { }
            bytes.flip();
            for (String line : StandardCharsets.UTF_8.decode(bytes).toString().split("\\R")) {
                if (line.isBlank()) continue;
                var event = json.readTree(line);
                String id = event.path("requestId").asString();
                BigDecimal amount = new BigDecimal(event.path("amountCny").asString());
                if (id.isBlank() || amount.signum() < 0) throw new IllegalStateException("Invalid experiment ledger");
                if (event.path("event").asString().equals("reserve")) {
                    if (amount.compareTo(RESERVE) > 0 || held.putIfAbsent(id, amount) != null)
                        throw new IllegalStateException("Invalid experiment reservation");
                } else if (event.path("event").asString().equals("settle")) {
                    var reserved = held.remove(id);
                    if (reserved == null || amount.compareTo(reserved) > 0) throw new IllegalStateException("Invalid experiment settlement");
                    spent = spent.add(amount);
                } else throw new IllegalStateException("Invalid experiment event");
                if (occupied().compareTo(LIMIT) > 0) throw new IllegalStateException("Experiment budget exceeded");
            }
            lock = acquired;
        } catch (Exception failure) {
            if (acquired != null) acquired.release();
            channel.close();
            throw new IllegalStateException("Cannot open authorized experiment budget ledger");
        }
    }

    public synchronized void reserve(String id, int inputBytes) {
        if (inputBytes > 65_536 || held.containsKey(id) || occupied().add(RESERVE).compareTo(LIMIT) > 0)
            throw new BusinessException(ErrorCode.RESEARCH_AGENT_BUDGET_EXCEEDED);
        append(Map.of("event", "reserve", "requestId", id, "amountCny", RESERVE.toPlainString(),
                "inputBytes", inputBytes, "priceVersion", "official-flash-2026-10-14-peak", "time", Instant.now().toString()));
        held.put(id, RESERVE);
    }

    public synchronized void settle(String id, Integer input, Integer output) {
        if (input == null || output == null || input < 0 || output < 0) return;
        BigDecimal cost = BigDecimal.valueOf(input).multiply(new BigDecimal("0.000002"))
                .add(BigDecimal.valueOf(output).multiply(new BigDecimal("0.000008")));
        if (held.get(id) == null || cost.compareTo(held.get(id)) > 0)
            throw new BusinessException(ErrorCode.RESEARCH_AGENT_BUDGET_EXCEEDED);
        append(Map.of("event", "settle", "requestId", id, "amountCny", cost.toPlainString(), "time", Instant.now().toString()));
        held.remove(id);
        spent = spent.add(cost);
    }

    public synchronized BigDecimal occupied() { return held.values().stream().reduce(spent, BigDecimal::add); }
    public synchronized State state() { return new State(spent, occupied(), held.size(), LIMIT); }
    public record State(BigDecimal spentUpperYuan, BigDecimal occupiedUpperYuan, int unknownReservations, BigDecimal limitYuan) {}
    private void append(Map<String, ?> event) {
        try {
            var bytes = StandardCharsets.UTF_8.encode(json.writeValueAsString(event) + "\n");
            channel.position(channel.size());
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        } catch (Exception failure) { throw new BusinessException(ErrorCode.RESEARCH_AGENT_UNAVAILABLE); }
    }
    @PreDestroy public void close() throws Exception { try { lock.release(); } finally { channel.close(); } }
}
