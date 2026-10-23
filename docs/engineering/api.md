# 本地接口文档与契约

本项目用 springdoc `3.1.1` 从实际 Spring MVC 路由、DTO 和校验注解生成 OpenAPI `3.1`，Swagger UI 负责把契约显示成可浏览的页面。这是本项目对前端提供的 HTTP API 文档；行情供应商 Longbridge OpenAPI 是另一套服务。

## 1. 打开与导出

在项目根目录按 [开发启动说明](development.md#4-一键启动) 启动后端，默认本地端口为 `8082`。本页不要求额外启动文档服务。

| 入口 | 用途 |
| --- | --- |
| `http://localhost:8082/swagger-ui.html` | 按账号、空间、研究、行情、助手和治理模块浏览接口 |
| `http://localhost:8082/v3/api-docs` | 导出当前运行版本的 OpenAPI JSON，可导入 Apifox 等工具 |
| `http://localhost:8082/v3/api-docs.yaml` | 同一份契约的 YAML 表示 |

使用自定义后端端口时替换地址中的 `8082`；这是后端地址，前端开发代理只承接 `/api`。源码中的注解修改后，需要重启后端才能重新生成文档。导出的 JSON/YAML 是该次运行的快照，不能替代源码成为第二份手工维护的接口清单。

共用配置默认关闭 `springdoc.api-docs.enabled` 和 `springdoc.swagger-ui.enabled`，`dev`、`test` 分别默认开启；`prod` 沿用关闭值，本轮没有部署或开放云端文档。仅需本地关闭时同时把这两个属性设为 `false`，再重启。关闭模式既不注册文档路由，也不对匿名请求放行文档地址。

## 2. 怎样阅读与调试

每个操作显示 HTTP 方法、路径、用途、参数位置、请求 DTO、返回结构和状态。路径中的 `workspaceId`／`workspaceStockId`／`researchCycleId` 是对象定位信息；当前登录用户从 JWT 解析，不由前端填写 `LoginUser` 或任意用户身份。

除注册、登录外，业务接口都需要 JWT。登录成功后的 `data.accessToken` 是本应用的凭证；点 Swagger UI 的 **Authorize**，仅填 Token 本体，界面添加 `Authorization: Bearer ...`。这里不使用 Longbridge Token 或模型 Key。授权信息不配置持久化，刷新文档页后需重新填写。

Swagger UI 的 **Try it out → Execute** 会发出真实 HTTP 请求，仍走同一条 Security → Controller → Service → Mapper 链。空间成员、OWNER、归档只读、个人助手记录隔离和超级管理员资格继续在后端判断；能够打开文档不代表取得业务权限。自动化验证仍按 [测试库与进程规则](testing.md#4-测试数据清理) 执行，不能对开发库创建测试账号。

普通 JSON 使用 `Result<T>` 的 `code/message/data` 外壳；成功 `code` 为 `SUCCESS`。失败使用真实 HTTP 状态和稳定错误码，前端按 `code` 判断，不能匹配中文 `message`。文档集中给出参数失败、未认证、越权、服务错误及业务失败外壳；`default` 是其他业务错误的契约，不是 HTTP 状态码，也不表示逐接口穷举了所有错误。准确业务规则继续见阶段 design 和 [ErrorCode](../../src/main/java/com/stock/system/common/api/ErrorCode.java)。

请求字段的必填、长度和格式优先从 Jakarta Validation 推导。密码标为只写字段；助手 `requestId` 标明 UUID，同一请求的恢复或重试保持原标识和内容，不能复用于另一条问题。时间参数沿 DTO：`LocalDate` 使用 `yyyy-MM-dd`，`Instant` 使用带时区的 ISO-8601；游标按接口返回值传递，不自行拼接。

## 3. 助手的 SSE 契约

助手的 `/summaries/stream`、`/messages/stream` 是带 JWT 的 **POST + `text/event-stream`**。它们返回持续事件，不能把 `SseEmitter` 的 Java 内部结构当成一次性 JSON 结果，也不能直接用只支持 GET 的原生 `EventSource` 替代当前页面的流请求。

| 事件 | `data` 中的字段与含义 |
| --- | --- |
| `progress` | `requestId/sequence/runId/stage`，表示实际执行阶段；运行尚未绑定时 `runId` 可空 |
| `draft` | `requestId/sequence/runId/paragraphs`，已校验的临时段落快照；空数组撤回临时正文 |
| `result` | `requestId/sequence/runId/result`，`result` 与运行查询返回的 `Run` 相同 |
| `failure` | `requestId/sequence/runId/errorCode/message`，连接建立后的流内失败 |

这些事件不套 `Result` 外壳；`keep-alive` 是注释心跳，不是业务事件。连接建立前的参数、身份和权限失败仍使用 JSON 与 HTTP 错误。断开订阅不会自动停止生成，停止必须调用本人对应请求的 `cancel`；恢复时先按 `requestId` 查运行状态。生成可能消耗已经配置的模型预算，Swagger UI 适合查看契约，持续显示、停止和刷新恢复使用现有研究助手页面。

## 4. 实现与维护入口

- [pom.xml](../../pom.xml)：固定依赖版本。官方 [springdoc 说明](https://springdoc.org/#getting-started) 对应 Boot 4 使用 3.x；项目兼容性仍由本仓库实际运行验证。
- [OpenApiConfiguration](../../src/main/java/com/stock/system/config/OpenApiConfiguration.java)：补充自动推导不到的 JWT、安全错误外壳和 SSE 语义；`servers` 使用当前后端相对地址，不写死测试端口或云端地址。
- 各 Controller 的 `@Tag`／`@Operation` 说明模块和用途；请求、响应的结构从真实 DTO/VO 推导。测试探针 `/api/test/**` 排除在契约外。
- [SecurityConfig](../../src/main/java/com/stock/system/config/SecurityConfig.java)：按开关放行文档入口，业务安全链保持实际鉴权。文档注解的锁图标是调用说明，真正的权限仍由安全链和 Service 执行。

统一错误 schema 使用 OpenAPI 3.1 的类型集合声明 `null`；示例用 Swagger 自有 JSON 节点显式保留 `data: null`，避免文档序列化省略这个字段。Swagger 内部兼容的 Jackson 2 只处理文档，业务仍注入 Boot 管理的 Jackson 3 `ObjectMapper`。

## 5. 验证范围

[OpenApiIntegrationTest](../../src/test/java/com/stock/system/config/OpenApiIntegrationTest.java) 对照当前 MVC 注册路由检查覆盖，递归核对本地 `$ref`，检查 DTO 校验、泛型返回、匿名入口、JWT 和 SSE，并验证文档错误示例与真实 401 相同。[OpenApiDisabledIntegrationTest](../../src/test/java/com/stock/system/config/OpenApiDisabledIntegrationTest.java) 检查关闭模式；原登录和管理员治理回归继续保留。

最新运行、打包与真实 Chrome 结果只记录在 [CODE-012 交付证据](../specs/001-workspace/tasks.md#code-012全站接口文档交付2026-10-23)。文档生成和浏览不需要调用模型或供应商，也不证明外部行情权限、回答质量或云端可用性。
