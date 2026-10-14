package com.stock.system.stock.market;

import com.longbridge.OpenApiException;
import com.longbridge.fundamental.FinancialReportKind;
import com.longbridge.fundamental.FinancialReportOptions;
import com.longbridge.fundamental.FinancialReportPeriod;
import com.longbridge.fundamental.FinancialReports;
import com.stock.system.stock.financial.FinancialPeriod;
import com.stock.system.stock.financial.FinancialSourceGateway;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

@Component
@ConditionalOnExpression("'${stock-system.market.mode:directory}' == 'longbridge' && '${stock-system.market.longbridge.transport:cli}' == 'sdk'")
class LongbridgeSdkFinancialClient implements FinancialSourceGateway, AutoCloseable {
    private final SessionFactory sessions;
    private final Duration timeout;
    private final Duration interval;
    private final ReentrantLock lock = new ReentrantLock(true);
    private LongbridgeFinancialSdkSession session;
    private long nextRequestNanos;
    private boolean closed;

    @Autowired
    LongbridgeSdkFinancialClient(@Value("${stock-system.market.longbridge.client-id:}") String clientId,
            @Value("${stock-system.market.longbridge.request-timeout:5s}") Duration timeout,
            @Value("${stock-system.market.longbridge.minimum-request-interval:100ms}") Duration interval) {
        this(() -> LongbridgeFinancialSdkSession.open(clientId, timeout), timeout, interval);
    }

    LongbridgeSdkFinancialClient(SessionFactory sessions, Duration timeout, Duration interval) {
        if (timeout.isZero() || timeout.isNegative() || interval.compareTo(Duration.ofMillis(100)) < 0) {
            throw new IllegalArgumentException("Invalid financial source timing configuration");
        }
        this.sessions = sessions;
        this.timeout = timeout;
        this.interval = interval;
    }

    @Override
    public SourceReport readFinancialReport(String symbol, FinancialPeriod period) {
        FinancialSourceGateway.validateSymbol(symbol);
        if (period == null) throw new IllegalArgumentException("Financial series required");
        boolean acquired = false;
        CompletableFuture<FinancialReports> request = null;
        try {
            acquired = lock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) throw new SourceFailure(FailureReason.TIMEOUT);
            if (closed) throw new SourceFailure(FailureReason.SOURCE_FAILURE);
            if (session == null) session = sessions.open();
            long wait = nextRequestNanos - System.nanoTime();
            if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
            nextRequestNanos = System.nanoTime() + interval.toNanos();
            FinancialReportOptions options = new FinancialReportOptions();
            options.symbol = symbol;
            options.kind = FinancialReportKind.All;
            options.period = switch (period) {
                case ANNUAL -> FinancialReportPeriod.Annual;
                case SEMIANNUAL -> FinancialReportPeriod.SemiAnnual;
                case QUARTER -> FinancialReportPeriod.QuarterlyFull;
            };
            request = session.context().getFinancialReport(symbol, options);
            FinancialReports result = request.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (result == null || result.list == null || result.list.length() > 2_000_000) {
                throw new SourceFailure(FailureReason.UNEXPECTED_RESPONSE);
            }
            return new SourceReport(result.list, Instant.now(), "LONGBRIDGE_FINANCIAL_REPORT");
        } catch (TimeoutException failure) {
            if (request != null) request.cancel(true);
            discardSession();
            throw new SourceFailure(FailureReason.TIMEOUT);
        } catch (InterruptedException failure) {
            if (acquired) { if (request != null) request.cancel(true); discardSession(); }
            Thread.currentThread().interrupt();
            throw new SourceFailure(FailureReason.SOURCE_FAILURE);
        } catch (SourceFailure failure) {
            throw failure;
        } catch (ExecutionException failure) {
            if (!(failure.getCause() instanceof OpenApiException api) || api.getCode() == null) discardSession();
            throw safeFailure(failure.getCause());
        } catch (Exception | LinkageError failure) {
            if (acquired) discardSession();
            throw safeFailure(failure);
        } finally {
            if (acquired) lock.unlock();
        }
    }

    private static SourceFailure safeFailure(Throwable failure) {
        if (failure instanceof OpenApiException api && api.getCode() != null) {
            long category = api.getCode() / 1000;
            if (category == 429) return new SourceFailure(FailureReason.RATE_LIMIT);
            if (category == 401 || category == 403) return new SourceFailure(FailureReason.ACCESS_DENIED);
        }
        return new SourceFailure(FailureReason.SOURCE_FAILURE);
    }

    private void discardSession() {
        LongbridgeFinancialSdkSession old = session;
        session = null;
        if (old != null) old.close();
    }

    @PreDestroy
    @Override public void close() {
        lock.lock();
        try { closed = true; discardSession(); } finally { lock.unlock(); }
    }
    @FunctionalInterface interface SessionFactory { LongbridgeFinancialSdkSession open() throws Exception; }
}
