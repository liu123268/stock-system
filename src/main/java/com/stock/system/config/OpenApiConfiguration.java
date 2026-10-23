package com.stock.system.config;

import com.stock.system.common.api.ErrorCode;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Set;

/** 文档从 MVC/DTO 生成；这里只补充框架无法推断的鉴权、统一错误和 SSE 语义。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class OpenApiConfiguration {
    private static final String BEARER = "bearerAuth";

    @Bean
    OpenAPI stockSystemOpenApi() {
        Schema<Object> error = new Schema<>();
        error.setTypes(Set.of("object"));
        error.addProperty("code", new StringSchema().description("稳定错误码，按项目 ErrorCode 判断"));
        error.addProperty("message", new StringSchema().description("面向用户的提示，可包含参数校验说明"));
        error.addProperty("data", new Schema<>().types(Set.of("null")));
        error.setRequired(List.of("code", "message", "data"));
        Components components = new Components()
                .addSecuritySchemes(BEARER, new SecurityScheme().type(SecurityScheme.Type.HTTP)
                        .scheme("bearer").bearerFormat("JWT")
                        .description("登录返回 data.accessToken。Authorize 中仅填写 Token，界面自动添加 Bearer 前缀。"))
                .addSchemas("ApiError", error)
                .addResponses("InvalidRequest", errorResponse(ErrorCode.INVALID_REQUEST))
                .addResponses("Unauthenticated", errorResponse(ErrorCode.UNAUTHENTICATED))
                .addResponses("LoginRejected", new ApiResponse()
                        .description("账号或密码错误，或账号已不可用；错误码为 INVALID_CREDENTIALS 或 ACCOUNT_DISABLED。")
                        .content(errorContent(ErrorCode.INVALID_CREDENTIALS)))
                .addResponses("Forbidden", errorResponse(ErrorCode.FORBIDDEN))
                .addResponses("InternalServerError", errorResponse(ErrorCode.INTERNAL_SERVER_ERROR))
                .addResponses("BusinessFailure", new ApiResponse()
                        .description("业务失败：HTTP 状态按实际错误码返回；此项不是全部业务错误的逐接口枚举。")
                        .content(errorContent(null)));
        return new OpenAPI()
                .info(new Info().title("共享股票研究系统 API").version("v1-v2")
                        .description("由当前 Controller 与 DTO 生成的接口契约。普通 JSON 响应采用 code/message/data 外壳；"
                                + "助手推送接口采用 SSE。除注册和登录外需要 JWT；空间成员、OWNER、归档状态及"
                                + "个人助手记录的访问条件继续由后端检查。/api/admin/** 仅超级管理员可用。"
                                + "文档不包含测试探针，也不执行真实行情或模型请求。"))
                .servers(List.of(new Server().url("/").description("当前文档所在后端")))
                .components(components)
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    @Bean
    OperationCustomizer stockSystemApiContract() {
        return (operation, handler) -> {
            // 两个匿名入口在 Controller 上用 @SecurityRequirements 明确覆盖全局 JWT 声明。
            SecurityRequirements requirements = handler.getMethodAnnotation(SecurityRequirements.class);
            boolean anonymous = requirements != null && requirements.value().length == 0;
            if (anonymous) operation.setSecurity(List.of());
            operation.getResponses().putIfAbsent("400", responseRef("InvalidRequest"));
            if (!anonymous) {
                operation.getResponses().putIfAbsent("401", responseRef("Unauthenticated"));
                operation.getResponses().putIfAbsent("403", responseRef("Forbidden"));
            }
            operation.getResponses().putIfAbsent("500", responseRef("InternalServerError"));
            operation.getResponses().putIfAbsent("default", responseRef("BusinessFailure"));
            if (SseEmitter.class.isAssignableFrom(handler.getReturnType().getParameterType())) {
                operation.getResponses().addApiResponse("200", new ApiResponse()
                        .description("SSE 事件流。progress：阶段；draft：已校验临时段落快照；result：最终运行；"
                                + "failure：流内错误。连接建立前失败仍用 JSON/HTTP 错误。")
                        .content(new Content().addMediaType(MediaType.TEXT_EVENT_STREAM_VALUE,
                                new io.swagger.v3.oas.models.media.MediaType().schema(new StringSchema())
                                        .example("event: progress\ndata: {\"requestId\":\"00000000-0000-4000-8000-000000000001\",\"sequence\":1,\"runId\":null,\"stage\":\"READING\"}\n\n"))));
                String details = "使用带 Authorization 头的 POST 流。事件 data 直接包含 requestId、sequence、runId，"
                        + "不套 Result 外壳；progress 另含 stage，draft 另含 paragraphs（空数组撤回临时正文），"
                        + "result 另含 result（与运行查询返回的 Run 相同），failure 另含 errorCode/message。"
                        + "keep-alive 是注释心跳。断开订阅不会停止生成，停止须调用对应请求的 cancel 接口；"
                        + "重连前可按 requestId 查询状态，不能盲目创建新请求。生成可能消耗已配置的模型预算。"
                        + "Swagger UI 用于查看契约，持续显示与恢复请使用研究助手页面。";
                operation.setDescription(operation.getDescription() == null ? details : operation.getDescription() + "\n\n" + details);
            }
            return operation;
        };
    }

    private static ApiResponse responseRef(String name) {
        return new ApiResponse().$ref("#/components/responses/" + name);
    }

    private static ApiResponse errorResponse(ErrorCode code) {
        return new ApiResponse().description(code.message()).content(errorContent(code));
    }

    private static Content errorContent(ErrorCode code) {
        var media = new io.swagger.v3.oas.models.media.MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiError"));
        // Swagger 自有的 Jackson 2 mapper 默认省略 POJO 的 null；显式 JSON 节点保留实际 data:null。
        if (code != null) media.example(Json31.mapper().createObjectNode()
                .put("code", code.name()).put("message", code.message()).putNull("data"));
        return new Content().addMediaType(MediaType.APPLICATION_JSON_VALUE, media);
    }
}
