package com.stock.system.config;

import com.stock.system.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"})
@AutoConfigureMockMvc
class OpenApiDisabledIntegrationTest extends MySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private RequestMappingHandlerMapping mappings;
    @Autowired private ApplicationContext context;

    @Test
    void disabledModeDoesNotCreateDocumentRoutesOrAnonymousDocumentationAccess() throws Exception {
        assertThat(context.getBeansOfType(OpenApiConfiguration.class)).isEmpty();
        assertThat(mappings.getHandlerMethods().keySet()).noneMatch(mapping -> mapping.getPatternValues().stream()
                .anyMatch(path -> path.startsWith("/v3/api-docs") || path.equals("/swagger-ui.html")));
        for (String path : new String[]{"/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/swagger-config", "/swagger-ui.html", "/swagger-ui/index.html"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }
}
