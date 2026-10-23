package com.stock.system.config;

import com.stock.system.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OpenApiIntegrationTest extends MySqlIntegrationTest {
    private static final String AGENT = "/api/workspaces/{workspaceId}/stocks/{workspaceStockId}/research/cycles/{researchCycleId}/agent";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private RequestMappingHandlerMapping mappings;

    @Test
    void generatedContractCoversActualBusinessRoutesAndResolvesEveryLocalReference() throws Exception {
        JsonNode document = document();
        assertThat(document.path("openapi").asText()).startsWith("3.1.");
        assertThat(document.at("/info/title").asText()).isEqualTo("共享股票研究系统 API");
        assertThat(document.at("/servers/0/url").asText()).isEqualTo("/");
        JsonNode paths = document.path("paths");
        Set<String> expected = new HashSet<>();
        mappings.getHandlerMethods().forEach((mapping, handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith("com.stock.system")) return;
            for (String path : mapping.getPatternValues()) {
                if (!path.startsWith("/api/") || path.startsWith("/api/test/")) continue;
                mapping.getMethodsCondition().getMethods().forEach(method -> expected.add(method.name().toLowerCase() + " " + path));
            }
        });
        Set<String> actual = new HashSet<>();
        paths.properties().forEach(path -> path.getValue().properties().forEach(method -> {
            actual.add(method.getKey() + " " + path.getKey());
            assertThat(method.getValue().path("summary").asText()).as(path.getKey()).isNotBlank();
        }));
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(actual).hasSizeGreaterThan(40);
        assertReferences(document, document);
        assertThat(document.path("components").path("schemas").has("LoginUser")).isFalse();
        assertThat(document.toString()).doesNotContain("passwordHash", "credentialVersion", "jdbc:mysql", "deepseek.api-key");
    }

    @Test
    void bearerContractDistinguishesAnonymousLoginAndKeepsBusinessRoutesProtected() throws Exception {
        JsonNode document = document();
        assertThat(document.at("/components/securitySchemes/bearerAuth/type").asText()).isEqualTo("http");
        assertThat(document.at("/components/securitySchemes/bearerAuth/scheme").asText()).isEqualTo("bearer");
        assertThat(document.at("/security/0/bearerAuth").isArray()).isTrue();
        assertThat(document.at("/components/schemas/ApiError/type").asText()).isEqualTo("object");
        assertThat(document.at("/components/schemas/ApiError/properties/data/type").asText()).isEqualTo("null");
        JsonNode errorExample = document.at("/components/responses/Unauthenticated/content/application~1json/example");
        assertThat(errorExample.has("data")).isTrue();
        assertThat(errorExample.path("data").isNull()).isTrue();
        for (String path : new String[]{"/api/auth/register", "/api/auth/login"}) {
            JsonNode security = document.path("paths").path(path).path("post").path("security");
            assertThat(security.isArray()).isTrue();
            assertThat(security).isEmpty();
        }
        var rejected = mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andReturn().getResponse();
        JsonNode actualError = json.readTree(rejected.getContentAsByteArray());
        assertThat(actualError).isEqualTo(errorExample);
        mvc.perform(get("/api/workspaces"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/admin/users").with(user("docs_regular").roles("REGULAR")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void requestValidationJsonEnvelopesAndStreamingMediaReflectActualContracts() throws Exception {
        JsonNode document = document();
        JsonNode register = document.path("paths").path("/api/auth/register").path("post");
        JsonNode request = resolve(document, register.at("/requestBody/content/application~1json/schema"));
        assertThat(request.path("required").toString()).contains("username", "password", "nickname");
        assertThat(request.at("/properties/username/minLength").asInt()).isEqualTo(2);
        assertThat(request.at("/properties/username/maxLength").asInt()).isEqualTo(20);
        assertThat(request.at("/properties/username/pattern").asText()).isEqualTo("^[A-Za-z0-9_]+$");
        assertThat(request.at("/properties/password/format").asText()).isEqualTo("password");
        assertThat(request.at("/properties/password/writeOnly").asBoolean()).isTrue();
        JsonNode envelope = resolve(document, register.at("/responses/201/content/application~1json/schema"));
        assertThat(envelope.path("properties").propertyNames()).contains("code", "message", "data");
        JsonNode result = resolve(document, envelope.at("/properties/data"));
        assertThat(result.path("properties").has("userId")).isTrue();
        assertThat(result.path("properties").has("password")).isFalse();
        JsonNode query = document.path("paths").path("/api/admin/users").path("get").path("parameters");
        assertThat(query).hasSize(4);
        assertThat(query.toString()).doesNotContain("currentUser", "credentialVersion");
        for (String suffix : new String[]{"/summaries/stream", "/messages/stream"}) {
            JsonNode operation = document.path("paths").path(AGENT + suffix).path("post");
            JsonNode command = resolve(document, operation.at("/requestBody/content/application~1json/schema"));
            assertThat(command.at("/properties/requestId/format").asText()).isEqualTo("uuid");
            assertThat(operation.at("/responses/200/content").propertyNames()).containsExactly(MediaType.TEXT_EVENT_STREAM_VALUE);
            assertThat(operation.at("/responses/200/content/text~1event-stream/schema/type").asText()).isEqualTo("string");
            assertThat(operation.path("description").asText()).contains("draft", "cancel", "requestId", "预算");
            assertThat(operation.at("/responses/401/$ref").asText()).isEqualTo("#/components/responses/Unauthenticated");
        }
    }

    @Test
    void swaggerUiAndItsConfigurationLoadFromTheApplicationWithoutExternalValidator() throws Exception {
        mvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/swagger-ui/index.html"));
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
        mvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs"))
                .andExpect(jsonPath("$.persistAuthorization").value(false))
                .andExpect(jsonPath("$.validatorUrl").value(""));
        mvc.perform(get("/v3/api-docs.yaml"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("openapi: 3.1.")));
    }

    private JsonNode document() throws Exception {
        var response = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray());
    }

    private JsonNode resolve(JsonNode document, JsonNode schema) {
        return schema.has("$ref") ? document.at(schema.path("$ref").asText().substring(1)) : schema;
    }

    private void assertReferences(JsonNode document, JsonNode node) {
        if (node.has("$ref") && node.path("$ref").asText().startsWith("#/")) {
            String ref = node.path("$ref").asText();
            assertThat(document.at(ref.substring(1)).isMissingNode()).as(ref).isFalse();
        }
        if (node.isContainer()) node.forEach(child -> assertReferences(document, child));
    }
}
