package com.stock.system.stock.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MarketCacheWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    "stock-system.market.mode=longbridge",
                    "stock-system.market.redis.enabled=false"
            );

    @Test
    void longbridgeProvidersReceivePrimaryCachingGatewayAndNoOpFallback() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(CachingLongbridgeMarketGateway.class);
            assertThat(context).hasSingleBean(NoOpMarketSharedCache.class);
            assertThat(context.getBean(LongbridgeQuoteGateway.class))
                    .isInstanceOf(CachingLongbridgeMarketGateway.class);
            assertThat(context.getBean(LongbridgeCandlestickGateway.class))
                    .isInstanceOf(CachingLongbridgeMarketGateway.class);
            assertThat(context.getBean(LongbridgeIntradayGateway.class))
                    .isInstanceOf(CachingLongbridgeMarketGateway.class);
            assertThat(context.getBean(LongbridgeTradingDayGateway.class))
                    .isInstanceOf(CachingLongbridgeMarketGateway.class);
        });
    }

    @Test
    void enabledRedisReplacesNoOpCacheWithoutChangingProviderGateway() {
        contextRunner
                .withPropertyValues("stock-system.market.redis.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(RedisMarketSharedCache.class);
                    assertThat(context).doesNotHaveBean(NoOpMarketSharedCache.class);
                    assertThat(context.getBean(MarketSharedCache.class))
                            .isInstanceOf(RedisMarketSharedCache.class);
                    assertThat(context.getBean(LongbridgeQuoteGateway.class))
                            .isInstanceOf(CachingLongbridgeMarketGateway.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            CachingLongbridgeMarketGateway.class,
            NoOpMarketSharedCache.class,
            RedisMarketSharedCache.class
    })
    static class TestConfiguration {

        @Bean
        LongbridgeJsonRpcClient upstream() {
            return mock(LongbridgeJsonRpcClient.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }
    }
}
