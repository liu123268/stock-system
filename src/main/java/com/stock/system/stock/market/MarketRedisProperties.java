package com.stock.system.stock.market;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "stock-system.market.redis")
class MarketRedisProperties {

    private boolean enabled;
    private String keyPrefix = "stock-system:market:v1";
    private Duration quoteTtl = Duration.ofSeconds(5);
    private Duration candlestickTtl = Duration.ofMinutes(1);
    private Duration historicalTtl = Duration.ofHours(12);
    private Duration tradingDayTtl = Duration.ofHours(24);
    private Duration lockTtl = Duration.ofSeconds(8);
    private Duration lockWait = Duration.ofMillis(800);
    private Duration failureCooldown = Duration.ofSeconds(30);
    private int ttlJitterPercent = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public Duration getQuoteTtl() {
        return quoteTtl;
    }

    public void setQuoteTtl(Duration quoteTtl) {
        this.quoteTtl = quoteTtl;
    }

    public Duration getCandlestickTtl() {
        return candlestickTtl;
    }

    public void setCandlestickTtl(Duration candlestickTtl) {
        this.candlestickTtl = candlestickTtl;
    }

    public Duration getHistoricalTtl() {
        return historicalTtl;
    }

    public void setHistoricalTtl(Duration historicalTtl) {
        this.historicalTtl = historicalTtl;
    }

    public Duration getTradingDayTtl() {
        return tradingDayTtl;
    }

    public void setTradingDayTtl(Duration tradingDayTtl) {
        this.tradingDayTtl = tradingDayTtl;
    }

    public Duration getLockTtl() {
        return lockTtl;
    }

    public void setLockTtl(Duration lockTtl) {
        this.lockTtl = lockTtl;
    }

    public Duration getLockWait() {
        return lockWait;
    }

    public void setLockWait(Duration lockWait) {
        this.lockWait = lockWait;
    }

    public Duration getFailureCooldown() {
        return failureCooldown;
    }

    public void setFailureCooldown(Duration failureCooldown) {
        this.failureCooldown = failureCooldown;
    }

    public int getTtlJitterPercent() {
        return ttlJitterPercent;
    }

    public void setTtlJitterPercent(int ttlJitterPercent) {
        this.ttlJitterPercent = ttlJitterPercent;
    }
}
