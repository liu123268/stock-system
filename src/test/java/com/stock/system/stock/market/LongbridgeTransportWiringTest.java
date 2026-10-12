package com.stock.system.stock.market;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** No SDK auth/network is performed when building the context; transport ownership stays lazy. */
class LongbridgeTransportWiringTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withUserConfiguration(LongbridgeJsonRpcClient.class, LongbridgeSdkClient.class,
                    CachingLongbridgeMarketGateway.class, NoOpMarketSharedCache.class)
            .withPropertyValues("stock-system.market.mode=longbridge", "stock-system.market.redis.enabled=false");

    @Test
    void localSdkReplacesCliInsideCachingLayer() {
        runner.withPropertyValues("stock-system.market.longbridge.transport=sdk").run(context -> {
            assertThat(context).hasSingleBean(LongbridgeSdkClient.class).doesNotHaveBean(LongbridgeJsonRpcClient.class);
            assertThat(context.getBean(LongbridgeUpstreamGateway.class)).isInstanceOf(LongbridgeSdkClient.class);
            assertThat(context.getBean(LongbridgeQuoteGateway.class)).isInstanceOf(CachingLongbridgeMarketGateway.class);
        });
    }

    @Test
    void productionAndLegacyConfigurationKeepCliOnly() {
        runner.withPropertyValues("stock-system.market.longbridge.transport=cli").run(context -> {
            assertThat(context).hasSingleBean(LongbridgeJsonRpcClient.class).doesNotHaveBean(LongbridgeSdkClient.class);
        });
        runner.run(context -> assertThat(context).hasSingleBean(LongbridgeJsonRpcClient.class)
                .doesNotHaveBean(LongbridgeSdkClient.class));
    }

    @Test
    void mockDoesNotConstructAnyRealTransport() {
        runner.withPropertyValues("stock-system.market.mode=mock", "stock-system.market.longbridge.transport=sdk")
                .run(context -> assertThat(context).doesNotHaveBean(LongbridgeSdkClient.class)
                        .doesNotHaveBean(LongbridgeJsonRpcClient.class).doesNotHaveBean(CachingLongbridgeMarketGateway.class));
    }

    @Test
    void checkedInDevAndProdPropertiesSelectDifferentTransports() throws java.io.IOException {
        var devProperties = new ResourcePropertySource(new ClassPathResource("application-dev.properties"));
        var prodProperties = new ResourcePropertySource(new ClassPathResource("application-prod.properties"));
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addLast(
                devProperties))
                .run(context -> assertThat(context).hasSingleBean(LongbridgeSdkClient.class)
                        .doesNotHaveBean(LongbridgeJsonRpcClient.class));
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addLast(
                prodProperties))
                .run(context -> assertThat(context).hasSingleBean(LongbridgeJsonRpcClient.class)
                        .doesNotHaveBean(LongbridgeSdkClient.class));
    }
}
