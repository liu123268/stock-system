package com.stock.system.stock.market;

import com.longbridge.ErrorKind;
import com.longbridge.Market;
import com.longbridge.OpenApiException;
import com.longbridge.quote.AdjustType;
import com.longbridge.quote.Candlestick;
import com.longbridge.quote.IntradayLine;
import com.longbridge.quote.MarketTradingDays;
import com.longbridge.quote.Period;
import com.longbridge.quote.QuoteContext;
import com.longbridge.quote.SecurityQuote;
import com.longbridge.quote.TradeSessions;
import com.longbridge.quote.TradeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LongbridgeSdkClientTest {
    private final QuoteContext context = mock(QuoteContext.class);
    private final LongbridgeSdkSession session = new LongbridgeSdkSession(null, null, context);

    @Test
    @EnabledIfEnvironmentVariable(named = "STOCK_LONGBRIDGE_SDK_REAL_PROBE", matches = "true")
    void readsFourMarketCapabilitiesThroughAuthorizedSdkWithoutDatabase() {
        String clientId = System.getenv("LONGBRIDGE_CLIENT_ID");
        try (var real = new LongbridgeSdkClient(clientId, Duration.ofSeconds(15), Duration.ofSeconds(5),
                Duration.ofMinutes(1), Duration.ofMillis(100))) {
            var quote = real.getQuotes(List.of("600519.SH")).get("600519.SH");
            assertThat(quote).isNotNull();
            assertThat(quote.lastDone()).isPositive();
            System.out.println("SDK_REAL_QUOTE_OK symbol=600519.SH");
            var daily = real.getCandlesticks("600519.SH", CandlestickPeriod.DAY, 2);
            assertThat(daily).hasSize(2).allSatisfy(bar -> assertThat(bar.close()).isPositive());
            System.out.println("SDK_REAL_DAILY_OK bars=" + daily.size());
            var intraday = real.getIntraday("600519.SH");
            assertThat(intraday).isNotEmpty().allSatisfy(point -> assertThat(point.price()).isPositive());
            System.out.println("SDK_REAL_INTRADAY_OK points=" + intraday.size());
            var history = real.getCandlesticksOnDate("600519.SH", CandlestickPeriod.MINUTE_1, LocalDate.of(2026, 9, 9));
            assertThat(history).isNotEmpty().allSatisfy(bar -> assertThat(bar.close()).isPositive());
            System.out.println("SDK_REAL_HISTORY_OK bars=" + history.size());
            assertThat(real.isTradingDay("600519.SH", LocalDate.of(2026, 9, 9))).isTrue();
            assertThat(real.isTradingDay("600519.SH", LocalDate.of(2026, 10, 3))).isFalse();
            System.out.println("SDK_REAL_CALENDAR_OK noDatabase=true");
        } catch (RuntimeException failure) {
            throw new AssertionError("SDK_REAL_PROBE_FAILED type=" + failure.getClass().getSimpleName());
        }
    }

    private LongbridgeSdkClient client() {
        return new LongbridgeSdkClient(() -> session, Duration.ofSeconds(1), Duration.ofSeconds(5),
                Duration.ofMinutes(1), Duration.ofMillis(100));
    }

    @Test
    void retainsDelistedStatusWithNoUsablePriceOrTimestamp() throws Exception {
        SecurityQuote quote = mock(SecurityQuote.class);
        when(quote.getSymbol()).thenReturn("03333.HK");
        when(quote.getTradeStatus()).thenReturn(TradeStatus.Delisted);
        when(context.getQuote(new String[]{"03333.HK"}))
                .thenReturn(CompletableFuture.completedFuture(new SecurityQuote[]{quote}));
        try (var client = client()) {
            var result = client.getQuotes(List.of("03333.HK")).get("03333.HK");
            assertThat(result.tradeStatus()).isEqualTo("Delisted");
            assertThat(result.lastDone()).isNull(); assertThat(result.timestamp()).isNull();
        }
    }

    @Test
    void batchesNormalizesSymbolsAndCachesWithoutChangingProviderTime() throws Exception {
        SecurityQuote quote = mock(SecurityQuote.class);
        when(quote.getSymbol()).thenReturn("600519.SH");
        when(quote.getLastDone()).thenReturn(new BigDecimal("1500.25"));
        when(quote.getTimestamp()).thenReturn(OffsetDateTime.parse("2026-10-01T15:00:00+08:00"));
        when(quote.getTradeStatus()).thenReturn(TradeStatus.Halted);
        when(context.getQuote(new String[]{"600519.SH"}))
                .thenReturn(CompletableFuture.completedFuture(new SecurityQuote[]{quote}));
        try (var client = client()) {
            var first = client.getQuotes(List.of("600519.sh", " 600519.SH "));
            var second = client.getQuotes(List.of("600519.SH"));
            assertThat(second).isEqualTo(first);
            assertThat(first.get("600519.SH").timestamp()).isEqualTo(Instant.parse("2026-10-01T07:00:00Z"));
            assertThat(first.get("600519.SH").tradeStatus()).isEqualTo("Halted");
            verify(context, times(1)).getQuote(new String[]{"600519.SH"});
        }
        verify(context).close();
    }

    @Test
    void usesUnadjustedBarsAndStrictBeforeBoundaryWithoutLosingSameDayBars() throws Exception {
        Candlestick old = bar("2026-09-30T09:31:00+08:00");
        Candlestick recent = bar("2026-09-30T09:32:00+08:00");
        Candlestick boundary = bar("2026-09-30T09:33:00+08:00");
        when(context.getHistoryCandlesticksByDate("600519.SH", Period.Min_1, AdjustType.NoAdjust,
                null, LocalDate.of(2026, 9, 30), TradeSessions.Intraday))
                .thenReturn(CompletableFuture.completedFuture(new Candlestick[]{boundary, old, recent}));
        try (var client = client()) {
            var result = client.getCandlesticksBefore("600519.SH", CandlestickPeriod.MINUTE_1, 1,
                    Instant.parse("2026-09-30T01:33:00Z"));
            assertThat(result).hasSize(1);
            assertThat(result.get(0).timestamp()).isEqualTo(Instant.parse("2026-09-30T01:32:00Z"));
        }
    }

    @Test
    void routesLatestAndDatedBarsThroughSdkAndCachesEachBoundarySeparately() throws Exception {
        LocalDate date = LocalDate.of(2026, 9, 30);
        Candlestick daily = bar("2026-09-30T00:00:00+08:00");
        Candlestick minute = bar("2026-09-30T09:31:00+08:00");
        when(context.getCandlesticks("600519.SH", Period.Day, 2, AdjustType.NoAdjust, TradeSessions.Intraday))
                .thenReturn(CompletableFuture.completedFuture(new Candlestick[]{daily}));
        when(context.getHistoryCandlesticksByDate("600519.SH", Period.Min_1, AdjustType.NoAdjust,
                date, date, TradeSessions.Intraday))
                .thenReturn(CompletableFuture.completedFuture(new Candlestick[]{minute}));
        try (var client = client()) {
            assertThat(client.getCandlesticks("600519.SH", CandlestickPeriod.DAY, 2)).hasSize(1);
            assertThat(client.getCandlesticks("600519.SH", CandlestickPeriod.DAY, 2)).hasSize(1);
            assertThat(client.getCandlesticksOnDate("600519.SH", CandlestickPeriod.MINUTE_1, date)).hasSize(1);
            assertThat(client.getCandlesticksOnDate("600519.SH", CandlestickPeriod.MINUTE_1, date)).hasSize(1);
        }
        verify(context, times(1)).getCandlesticks("600519.SH", Period.Day, 2, AdjustType.NoAdjust, TradeSessions.Intraday);
        verify(context, times(1)).getHistoryCandlesticksByDate("600519.SH", Period.Min_1, AdjustType.NoAdjust,
                date, date, TradeSessions.Intraday);
    }

    @Test
    void convertsIntradayPriceAverageVolumeAndTimestamp() throws Exception {
        IntradayLine line = mock(IntradayLine.class);
        when(line.getTimestamp()).thenReturn(OffsetDateTime.parse("2026-09-30T09:31:00+08:00"));
        when(line.getPrice()).thenReturn(new BigDecimal("1500"));
        when(line.getAvgPrice()).thenReturn(new BigDecimal("1499.50"));
        when(line.getVolume()).thenReturn(20L);
        when(line.getTurnover()).thenReturn(new BigDecimal("30000"));
        when(context.getIntraday("600519.SH", TradeSessions.Intraday))
                .thenReturn(CompletableFuture.completedFuture(new IntradayLine[]{line}));
        try (var client = client()) {
            var points = client.getIntraday("600519.SH");
            assertThat(points).hasSize(1);
            assertThat(points.get(0).averagePrice()).isEqualByComparingTo("1499.50");
            assertThat(points.get(0).volume()).isEqualTo(20);
            assertThat(points.get(0).timestamp()).isEqualTo(Instant.parse("2026-09-30T01:31:00Z"));
        }
    }

    @Test
    void queriesOfficialCalendarForHalfDayAndCachesByMarket() throws Exception {
        LocalDate date = LocalDate.of(2026, 12, 24);
        MarketTradingDays days = mock(MarketTradingDays.class);
        when(days.getTradingDays()).thenReturn(new LocalDate[0]);
        when(days.getHalfTradingDays()).thenReturn(new LocalDate[]{date});
        when(context.getTradingDays(Market.HK, date, date)).thenReturn(CompletableFuture.completedFuture(days));
        try (var client = client()) {
            assertThat(client.isTradingDay("700.HK", date)).isTrue();
            assertThat(client.isTradingDay("9988.HK", date)).isTrue();
        }
        verify(context, times(1)).getTradingDays(Market.HK, date, date);
    }

    @Test
    void timeoutClosesOwnedConnectionBeforeNextRequestCreatesAnother() throws Exception {
        QuoteContext nextContext = mock(QuoteContext.class);
        when(context.getQuote(any())).thenReturn(new CompletableFuture<>());
        when(nextContext.getQuote(any())).thenReturn(CompletableFuture.completedFuture(new SecurityQuote[0]));
        AtomicInteger opened = new AtomicInteger();
        try (var client = new LongbridgeSdkClient(() -> opened.getAndIncrement() == 0 ? session
                : new LongbridgeSdkSession(null, null, nextContext), Duration.ofMillis(30),
                Duration.ZERO, Duration.ZERO, Duration.ofMillis(100))) {
            assertThatThrownBy(() -> client.getQuotes(List.of("600519.SH")))
                    .isInstanceOf(LongbridgeUpstreamException.class).hasMessageContaining("timed out");
            verify(context).close();
            assertThat(client.getQuotes(List.of("600519.SH"))).isEmpty();
            assertThat(opened).hasValue(2);
        }
        verify(nextContext).close();
    }

    @Test
    void preservesQuotaAndMinuteRangeButDoesNotExposeRawProviderMessage() {
        var failure = new OpenApiException(ErrorKind.OpenApi, 301607L,
                "secret-value out of minute candlestick limit begin date:2025-10-01");
        var translated = LongbridgeSdkClient.safeFailure(failure);
        assertThat(translated.hasProviderCode("301607")).isTrue();
        assertThat(translated.minuteHistoryBeginDate()).contains(LocalDate.of(2025, 10, 1));
        assertThat(translated.getMessage()).doesNotContain("secret-value");
        assertThat(translated.getCause()).isNull();
    }

    @Test
    void unrecognizedStatusIsKeptUnavailableInsteadOfInventingNormal() throws Exception {
        SecurityQuote quote = mock(SecurityQuote.class);
        when(quote.getSymbol()).thenReturn("600519.SH");
        when(quote.getLastDone()).thenReturn(BigDecimal.TEN);
        when(quote.getTimestamp()).thenReturn(OffsetDateTime.now());
        when(context.getQuote(any())).thenReturn(CompletableFuture.completedFuture(new SecurityQuote[]{quote}));
        try (var client = client()) {
            assertThat(client.getQuotes(List.of("600519.SH")).get("600519.SH").tradeStatus()).isEmpty();
        }
    }

    @Test
    void rejectsInvalidCountBeforeOpeningConnectionAndClosesIdempotently() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        var client = new LongbridgeSdkClient(() -> { opens.incrementAndGet(); return session; },
                Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO, Duration.ofMillis(100));
        assertThatThrownBy(() -> client.getCandlesticks("600519.SH", CandlestickPeriod.DAY, 1001))
                .isInstanceOf(IllegalArgumentException.class);
        client.close();
        client.close();
        assertThat(opens).hasValue(0);
        assertThatThrownBy(() -> client.getQuotes(List.of("600519.SH"))).hasMessageContaining("closed");
        verify(context, never()).close();
    }

    private Candlestick bar(String timestamp) {
        Candlestick bar = mock(Candlestick.class);
        when(bar.getTimestamp()).thenReturn(OffsetDateTime.parse(timestamp));
        when(bar.getOpen()).thenReturn(BigDecimal.TEN);
        when(bar.getHigh()).thenReturn(BigDecimal.TEN);
        when(bar.getLow()).thenReturn(BigDecimal.TEN);
        when(bar.getClose()).thenReturn(BigDecimal.TEN);
        when(bar.getTurnover()).thenReturn(BigDecimal.ZERO);
        return bar;
    }
}
