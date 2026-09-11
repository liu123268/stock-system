package com.stock.system.stock.market;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MarketRedisPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "stock-system.market.redis.enabled=true",
                    "stock-system.market.redis.key-prefix=learning:market:v2",
                    "stock-system.market.redis.quote-ttl=7s",
                    "stock-system.market.redis.ttl-jitter-percent=15"
            );

    @Test
    void bindsRedisSettingsFromSpringConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(MarketRedisProperties.class);
            MarketRedisProperties properties = context.getBean(MarketRedisProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getKeyPrefix()).isEqualTo("learning:market:v2");
            assertThat(properties.getQuoteTtl()).isEqualTo(Duration.ofSeconds(7));
            assertThat(properties.getTtlJitterPercent()).isEqualTo(15);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MarketRedisProperties.class)
    static class PropertiesConfiguration {
    }
}
