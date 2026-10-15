package com.stock.system.agent;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Resolves checked-in configuration without starting a server or reading any real local Key. */
class ResearchAgentLocalConfigurationTest {
    @Test
    void absentLocalFilesKeepModelAndBudgetDisabled() throws IOException {
        var environment = environment("dev", Map.of(), Map.of());
        assertThat(environment.getProperty("spring.ai.model.chat")).isEqualTo("none");
        assertThat(environment.getProperty("stock-system.agent.budget-yuan", BigDecimal.class))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(environment.getProperty("spring.ai.deepseek.api-key")).isEmpty();
    }

    @Test
    void existingKeyAloneDoesNotEnablePaidGeneration() throws IOException {
        var environment = environment("dev", Map.of(), Map.of("deepseek.api-key", "offline-existing-key"));
        assertThat(environment.getProperty("spring.ai.deepseek.api-key")).isEqualTo("offline-existing-key");
        assertThat(environment.getProperty("spring.ai.model.chat")).isEqualTo("none");
        assertThat(environment.getProperty("stock-system.agent.budget-yuan", BigDecimal.class))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void localOptInUsesExistingKeyAndExplicitCumulativeBudget() throws IOException {
        var environment = environment("dev", Map.of(), Map.of(
                "deepseek.api-key", "offline-existing-key", "local.research-agent.chat-model", "deepseek",
                "local.research-agent.budget-yuan", "10"));
        assertThat(environment.getProperty("spring.ai.model.chat")).isEqualTo("deepseek");
        assertThat(environment.getProperty("spring.ai.deepseek.api-key")).isEqualTo("offline-existing-key");
        assertThat(environment.getProperty("stock-system.agent.budget-yuan", BigDecimal.class))
                .isEqualByComparingTo(BigDecimal.TEN);
    }

    @Test
    void environmentValuesOverrideAllLocalChoices() throws IOException {
        var environment = environment("dev", Map.of("DEEPSEEK_API_KEY", "offline-environment-key",
                "RESEARCH_AGENT_CHAT_MODEL", "none", "RESEARCH_AGENT_BUDGET_YUAN", "0"), Map.of(
                "deepseek.api-key", "offline-existing-key", "local.research-agent.chat-model", "deepseek",
                "local.research-agent.budget-yuan", "10"));
        assertThat(environment.getProperty("spring.ai.deepseek.api-key")).isEqualTo("offline-environment-key");
        assertThat(environment.getProperty("spring.ai.model.chat")).isEqualTo("none");
        assertThat(environment.getProperty("stock-system.agent.budget-yuan", BigDecimal.class))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void devImportsOptionalFilesWhileProductionUsesOnlyItsSecrets() throws IOException {
        var dev = environment("dev", Map.of(), Map.of());
        assertThat(dev.getProperty("spring.config.import").split(",")).containsExactly(
                "optional:file:./.env.longbridge-local.properties", "optional:file:./.env.deepseek-local.properties",
                "optional:file:./.env.research-agent-local.properties");
        var prod = environment("prod", Map.of(), Map.of("deepseek.api-key", "offline-existing-key",
                "local.research-agent.chat-model", "deepseek", "local.research-agent.budget-yuan", "10"));
        assertThat(prod.getProperty("spring.config.import")).isEqualTo("configtree:/run/secrets/");
        assertThat(prod.getProperty("spring.ai.deepseek.api-key")).isEmpty();
        assertThat(prod.getProperty("spring.ai.model.chat")).isEqualTo("none");
        assertThat(prod.getProperty("stock-system.agent.budget-yuan", BigDecimal.class))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    private StandardEnvironment environment(String profile, Map<String, Object> variables,
                                            Map<String, Object> localProperties) throws IOException {
        var environment = new StandardEnvironment();
        var sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addLast(new MapPropertySource("isolated-environment", variables));
        sources.addLast(new MapPropertySource("fabricated-local-properties", localProperties));
        sources.addLast(new ResourcePropertySource(new ClassPathResource("application-" + profile + ".properties")));
        sources.addLast(new ResourcePropertySource(new ClassPathResource("application.properties")));
        return environment;
    }
}
