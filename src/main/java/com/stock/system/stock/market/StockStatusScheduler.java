package com.stock.system.stock.market;

import org.springframework.beans.factory.annotation.Autowired;
import java.time.Clock;

import com.stock.system.stock.entity.Stock;
import com.stock.system.stock.mapper.StockMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Uses the running backend's gateway, never opens another SDK/CLI session. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "stock-system.market.mode", havingValue = "longbridge")
public class StockStatusScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(StockStatusScheduler.class);
    private final StockMapper mapper;
    private final LongbridgeQuoteGateway gateway;
    private final StockStatusChecks checks;
    private final DataSource dataSource;
    private final Clock clock;

    @Autowired
    public StockStatusScheduler(StockMapper mapper, LongbridgeQuoteGateway gateway,
                                StockStatusChecks checks, DataSource dataSource) {
        this(mapper, gateway, checks, dataSource, Clock.systemUTC());
    }

    StockStatusScheduler(StockMapper mapper, LongbridgeQuoteGateway gateway,
                         StockStatusChecks checks, DataSource dataSource, Clock clock) {
        this.mapper = mapper; this.gateway = gateway; this.checks = checks; this.dataSource = dataSource;
        this.clock = clock;
    }

    // Polling also catches a backend started after the daily time or a freshly imported directory.
    @Scheduled(fixedDelayString = "${stock-system.market.status-sync.poll-ms:60000}", initialDelay = 15000)
    public void updateDueStocks() {
        LocalDateTime now = StockStatusChecks.time(clock.instant());
        LocalDateTime due = now.toLocalDate().atTime(6, 35);
        if (now.isBefore(due)) return;
        try (Connection lock = dataSource.getConnection()) {
            String key = "stock-status:" + lock.getCatalog();
            try (var statement = lock.prepareStatement("select get_lock(?, 0)")) {
                statement.setString(1, key);
                try (var result = statement.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1) return;
                }
            }
            try {
                int total = 0;
                long afterId = 0;
                for (int batch = 0; batch < 100; batch++) {
                    List<Stock> stocks = mapper.selectDueStatusChecks(due, afterId);
                    if (stocks.isEmpty()) break;
                    Instant attempt = clock.instant();
                    Map<String, LongbridgeQuote> quotes;
                    try { quotes = gateway.getQuotes(stocks.stream().map(Stock::getCode).toList()); }
                    catch (LongbridgeUpstreamException failure) {
                        // Do not turn an outage into hundreds of individual requests.
                        LOG.warn("Daily stock-status batch unavailable; recording unverified results");
                        quotes = Map.of();
                    }
                    for (Stock stock : stocks) checks.observe(stock, quotes.get(stock.getCode()), attempt);
                    total += stocks.size();
                    // A short-lived cached observation may precede today's cutoff. Advance
                    // through the directory once and let the next poll recheck that row.
                    afterId = stocks.get(stocks.size() - 1).getId();
                }
                if (total > 0) LOG.info("Daily stock-status check completed: attempted={}", total);
            } finally {
                try (var statement = lock.prepareStatement("select release_lock(?)")) {
                    statement.setString(1, key); statement.execute();
                }
            }
        } catch (Exception failure) {
            LOG.warn("Daily stock-status check interrupted: {}", failure.getClass().getSimpleName());
        }
    }
}
