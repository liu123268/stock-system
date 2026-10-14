package com.stock.system.stock.market;

import com.longbridge.ErrorKind;
import com.longbridge.OpenApiException;
import com.longbridge.fundamental.FinancialReportKind;
import com.longbridge.fundamental.FinancialReportOptions;
import com.longbridge.fundamental.FinancialReportPeriod;
import com.longbridge.fundamental.FinancialReports;
import com.longbridge.fundamental.FundamentalContext;
import com.stock.system.stock.financial.FinancialPeriod;
import com.stock.system.stock.financial.FinancialSourceGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LongbridgeSdkFinancialClientTest {
    private final FundamentalContext context = mock(FundamentalContext.class);
    private final LongbridgeFinancialSdkSession session = new LongbridgeFinancialSdkSession(null, null, context);
    private static FinancialReports emptyReport() { var result = new FinancialReports(); result.list = "{}"; return result; }

    @Test void opensLazilyReusesHttpSessionAndPassesFixedAllKindAndSeries() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        when(context.getFinancialReport(anyString(), any())).thenReturn(CompletableFuture.completedFuture(emptyReport()));
        try (var client = new LongbridgeSdkFinancialClient(() -> { opens.incrementAndGet(); return session; },
                Duration.ofSeconds(1), Duration.ofMillis(100))) {
            assertThat(opens.get()).isZero();
            assertThat(client.readFinancialReport("700.HK", FinancialPeriod.QUARTER).json()).isEqualTo("{}");
            client.readFinancialReport("600519.SH", FinancialPeriod.SEMIANNUAL);
            assertThat(opens.get()).isEqualTo(1);
            var options = ArgumentCaptor.forClass(FinancialReportOptions.class);
            verify(context, times(2)).getFinancialReport(anyString(), options.capture());
            assertThat(options.getAllValues().get(0).kind).isEqualTo(FinancialReportKind.All);
            assertThat(options.getAllValues().get(0).period).isEqualTo(FinancialReportPeriod.QuarterlyFull);
            assertThat(options.getAllValues().get(1).period).isEqualTo(FinancialReportPeriod.SemiAnnual);
        }
        verify(context).close();
    }
    @Test void timeoutCancelsPendingRequestClosesSessionAndAllowsLaterExplicitRequest() throws Exception {
        CompletableFuture<FinancialReports> pending = new CompletableFuture<>();
        when(context.getFinancialReport(anyString(), any())).thenReturn(pending)
                .thenReturn(CompletableFuture.completedFuture(emptyReport()));
        AtomicInteger opens = new AtomicInteger();
        try (var client = new LongbridgeSdkFinancialClient(() -> { opens.incrementAndGet(); return session; },
                Duration.ofMillis(40), Duration.ofMillis(100))) {
            assertThatThrownBy(() -> client.readFinancialReport("600519.SH", FinancialPeriod.ANNUAL))
                    .isInstanceOf(FinancialSourceGateway.SourceFailure.class).hasMessageContaining("TIMEOUT");
            assertThat(pending.isCancelled()).isTrue();
            verify(context).close();
            assertThat(client.readFinancialReport("600519.SH", FinancialPeriod.ANNUAL).json()).isEqualTo("{}");
            assertThat(opens.get()).isEqualTo(2);
        }
    }
    @Test void keepsOnlySafeRateLimitReasonAndDoesNotAutomaticallyRetry() throws Exception {
        when(context.getFinancialReport(anyString(), any())).thenReturn(CompletableFuture.failedFuture(
                new OpenApiException(ErrorKind.Http, 429001L, "sensitive-provider-body")));
        try (var client = new LongbridgeSdkFinancialClient(() -> session, Duration.ofSeconds(1), Duration.ofMillis(100))) {
            assertThatThrownBy(() -> client.readFinancialReport("600519.SH", FinancialPeriod.ANNUAL))
                    .hasMessageContaining("RATE_LIMIT").hasMessageNotContaining("sensitive-provider-body").hasNoCause();
            verify(context).getFinancialReport(anyString(), any());
        }
    }
    @Test void rejectsInvalidIdentityBeforeOpeningNativeResourcesAndRejectsCallsAfterClose() {
        AtomicInteger opens = new AtomicInteger();
        var client = new LongbridgeSdkFinancialClient(() -> { opens.incrementAndGet(); return session; },
                Duration.ofSeconds(1), Duration.ofMillis(100));
        assertThatThrownBy(() -> client.readFinancialReport("https://untrusted.invalid", FinancialPeriod.ANNUAL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(opens.get()).isZero();
        client.close();
        assertThatThrownBy(() -> client.readFinancialReport("600519.SH", FinancialPeriod.ANNUAL))
                .isInstanceOf(FinancialSourceGateway.SourceFailure.class);
        assertThat(opens.get()).isZero();
    }
}
