# 项目实际技术栈

更新时间：2026-10-18（SSE 阶段推送／停止及本机只读 MCP 已验证；云端尚未发布本轮构建）

本文档记录项目技术的真实状态。用户最初提供的学习目标和候选技术清单保存在 [技术目标与选型参考](../project/technology-reference.md)，两者不得混淆。

状态含义：

- `已使用`：代码或配置中已经存在，并有一定验证；
- `计划`：路线已考虑，但尚未实现；
- `候选`：需要进一步验证场景和方案；
- `不采用`：当前阶段明确不引入。

## 1. 后端与数据

| 技术               | 当前版本/形式        | 状态               | 当前用途与证据                                                         |
| ------------------ | -------------------- | ------------------ | ---------------------------------------------------------------------- |
| Java               | 17                   | 已使用             | 后端运行版本，现有测试基于 JDK 17                                      |
| Spring Boot        | 4.1.1                | 已使用（本地源码） | 完成升级前／副本／正式工程回归及 test JAR HTTP 验证；云端仍为旧发布 |
| Spring MVC         | Spring Boot 管理     | 已使用             | REST Controller 和统一 JSON API                                        |
| Servlet / 内嵌 Tomcat | Spring Boot 管理 | 已使用 | WebMVC starter 带入 Servlet 容器；请求经过过滤器链与 DispatcherServlet，JWT Filter 使用 Jakarta Servlet API |
| Spring Security    | Spring Boot 管理     | 已使用             | 无状态认证过滤链、角色入口与统一 401／403                              |
| JWT / JJWT         | 0.12.3               | 已使用             | 24 小时访问 Token，包含用户 ID、用户名和凭据版本；改密后校验版本使旧 Token 失效 |
| BCrypt             | Spring Security 提供 | 已使用             | 密码摘要和匹配                                                         |
| Jakarta Validation | Spring Boot 管理     | 已使用             | 注册、登录请求参数校验                                                 |
| MyBatis-Plus       | 3.5.17，Boot 4 starter | 已使用             | BaseMapper 持久化；当前尚无自定义 SQL XML                              |
| JDBC / DataSource / HikariCP | Spring Boot 管理 | 已使用 | Mapper 与 JdbcTemplate 经 JDBC 访问数据库；Web 主应用由连接池提供连接，生产池配置见 `application-prod.properties`；目录命令另用 DriverManagerDataSource，不能把全部入口视为池化 |
| MySQL              | dev 8.0.34；prod 8.4 系列 | 已使用             | 业务关系与约束；云端环境快照见 [部署说明](../engineering/deployment.md#2-当前云端部署)，各库迁移状态见 [状态](../project/status.md) |
| Flyway             | Spring Boot 管理     | 已使用             | 版本化迁移；环境应用版本只在 [状态](../project/status.md) 保存                          |
| Maven              | 系统 Maven           | 已使用             | 依赖、测试和构建                                                       |
| Jackson            | 3.1.5（业务 JSON） | 已使用 | Boot 4 管理；JWT 库内部兼容依赖不作为业务 ObjectMapper 使用 |
| SLF4J / Logback | Spring Boot 管理 | 已使用 | LoggerFactory 作为日志 API，Boot logging starter 提供 Logback；全局异常、行情与 Redis 降级已有日志，不等于结构化追踪平台已完成 |
| JUnit Jupiter／Mockito | 6.0.3／5.23.0，Boot BOM 管理 | 已使用 | 保留 Jupiter 测试 API，测试替身改用 MockitoBean／MockitoSpyBean |

## 2. 前端

| 技术                          | 当前版本/形式            | 状态   | 当前用途与证据                                                             |
| ----------------------------- | ------------------------ | ------ | -------------------------------------------------------------------------- |
| Vue                           | 3.5.x                    | 已使用 | 页面与组件                                                                 |
| TypeScript                    | 6.0.x                    | 已使用 | 前端类型约束，使用 `vue-tsc` 检查                                          |
| Vite                          | 8.1.x                    | 已使用 | 开发服务器、代理和生产构建                                                 |
| Vue Router                    | 5.2.x                    | 已使用 | 页面路由、懒加载和认证守卫                                                 |
| Pinia                         | 4.0.x                    | 已使用 | 当前用户、JWT、空间、股票列表及图表加载状态                                 |
| Axios                         | 1.19.x                   | 已使用 | API 请求和 Bearer Token 拦截器                                             |
| Element Plus                  | 2.14.x                   | 已使用 | 表单等基础组件，采用按需引入                                               |
| Vitest                        | 4.1.x                    | 已使用 | 前端单元测试                                                               |
| ESLint / Oxlint / Prettier    | 当前 `package.json` 版本 | 已使用 | 静态检查与格式化                                                           |
| Playwright Core + 本机 Chrome | 1.62.x                   | 已使用 | 双账号注册登录、邀请加入、退出、移除、重新加入和所有权转让的浏览器闭环测试 |

## 3. 工程与交付

| 技术                            | 状态／版本   | 用途                                           | 证据或引入条件                                                             |
| ------------------------------- | ------------ | ---------------------------------------------- | -------------------------------------------------------------------------- |
| OpenAPI / springdoc             | 计划         | 接口契约和 Swagger UI                          | 空间接口进入稳定实现时                                                     |
| Apifox                          | 计划         | 人工联调和流程测试                             | OpenAPI 契约形成后                                                         |
| Longbridge OpenAPI / Java SDK / CLI | 已使用（SDK `4.5.0`，CLI `v0.28.5`） | dev 官方授权及真实行情 SDK 探测通过；prod 保留已验证 CLI | dev 采用官方 SDK OAuth 和单个 QuoteContext；prod 继续管理 `longbridge serve`；两种传输共用四类行情网关与缓存，账号分离 |
| 行情适配层                      | 目录、Mock、Longbridge | 已使用       | 三个 provider 分别管理标准身份／最新报价、K 线和分时；隔离业务与供应商 DTO |
| Redis / Spring Data Redis / Lettuce | 已使用 | 只读行情共享缓存、同 Key 请求合并和故障降级 | 依赖、网关装饰器和开关已接入；单元、真实 Redis 与页面验收的范围见 [任务证据](../specs/002-stock-research/tasks.md)；不与 WebSocket/SSE 捆绑 |
| 定时任务                        | 已使用（systemd timer） | 每日股票目录同步与 HTTPS 证书续期 | 已安装、首轮目录同步及证书续期 dry-run 通过；目录任务使用幂等导入、锁和运行回执。长期运行／主动告警未验收，见 [部署说明](../engineering/deployment.md) |
| MQ（RocketMQ / Kafka 为原候选） | 未接入；无确定实施计划 | 使用场景尚待评估 | 下一次评估落在 [报告处理的异步方案](../product/backlog.md#9-报告处理的异步方案与-mq)，先比较同步、受控线程池／任务表与 MQ，再决定是否采用；V1 近期动态由业务记录查询生成 |
| Actuator                        | 计划         | 健康检查和运行状态                             | 部署与可观测性阶段                                                         |
| 结构化日志 / 请求追踪 ID        | 计划         | 定位跨层请求问题                               | 核心业务闭环后                                                             |
| Docker Compose                  | 已使用       | 统一运行云端数据库、缓存、后端和前端代理       | 配置、发布与隔离故障验收见 [部署说明](../engineering/deployment.md#2-当前云端部署) |
| Nginx                           | 已使用       | 前端静态资源、`/api` 反向代理与 HTTPS          | 公网 HTTPS、HTTP 跳转、API 与静态资源行为已验收，见 [部署说明](../engineering/deployment.md#2-当前云端部署) |
| ECharts                         | `^6.1.0`，已使用 | 按需展示九种周期 K 线／成交量和当日／历史分时 | 两个图表组件随展开面板异步加载；K 线与量柱同步缩放并支持左边界补载        |
| 前端轮询                        | 已使用（分时最小范围） | 当前可见单股票盘中分时受控刷新              | 固定 30 秒；折叠、切回 K 线、页面隐藏时不访问上游，后端返回非更新状态后不再调度 |
| SSE                             | 已使用（本地） | Agent 实时阶段与已校验最终结果 | 带认证头的 fetch、断线恢复和协作式停止已验证；逐字／逐段正文未实现，007 总体质量另验 |
| WebSocket                       | 候选         | 有真实需要时的双向通信或行情推送               | 不与 SSE 或当前行情轮询绑定                                               |
| LLM API + 结构化输出            | 独立实验及正式业务已接入 | 观点整理、工具追问与资料缺口                 | 个人权限、会话、独立助手页及有限财务工具已接；分项真实质量见 SPEC-004 tasks，不代表完整 V2       |
| Spring AI                       | 2.0.1，框架已接入，版本默认模型关闭 | V2 主框架：模型调用、Tool Calling、会话、RAG 与观测 | 生成、受限工具及保存恢复已接；报告首段沿 EmbeddingModel／VectorStore 接入，完整 RAG 与 MCP 分别验收 |
| 向量数据库                      | Qdrant 1.19.2 本机 Windows 服务端已接 | 当前股票有限年报段落的语义检索 | Java 回环 REST、持久化重开、维护与页面引用已接；生产部署另验，不替换 MySQL |
| 本地文本向量模型                | BGE-small-zh-v1.5 ONNX，512 维，Java 业务已接入 | 中文报告段落与问题向量化 | 固定模型提交／文件哈希与编码依赖，CPU 运行，不等同于 DeepSeek 回答模型；正文向量化不外发 |

### 3.1 外部行情调用与密钥边界

服务端调用、凭据隔离与浏览器验收要求统一见 [SPEC-002 行情安全边界](../specs/002-stock-research/design.md#40-外部行情调用与密钥边界)，不在技术清单重复维护调用图和规则。传输取舍见 [ADR-002](decisions/ADR-002-longbridge-cli-sidecar.md)，环境化要求见 [部署说明](../engineering/deployment.md#3-longbridge-sidecar-部署前置条件)。

### 3.2 V2 Agent 技术规划基线

2026-10-08：按用户要求复核后采用以下讨论与接入规划；2026-10-09 独立模型实验已执行，2026-10-12 框架与正式周期资料读取首段进入主工程，研究生成、会话、工具和页面尚未接入。产品产出和全局边界只维护在 [V2 需求](../product/requirements-v2.md#3-正式讨论采用的规划基线)，岗位证据与难度判断见 [市场对标](../project/java-agent-market-benchmark.md)，先后顺序见 [路线第 6 阶段](../project/roadmap.md#阶段-6java-agent-增量)。

架构沿用 [模块化单体](decisions/ADR-001-modular-monolith.md)：在现有后端增加 Agent 业务包与受限工具适配层，调用公开 Java Service；Vue 沿用当前登录和空间上下文。数据库、行情网关与 Redis 缓存仍由各自业务模块管理。原有技术的复用不代表对应 Agent 能力已经实现。

| 部分 | 主选与实际状态 | 问题、代价和验证重点 |
| --- | --- | --- |
| Java 与服务端 | Java 17／Boot 4.1.1，已在本地源码升级 | 已核对安全、Mapper、JSON／时间、Flyway、行情传输回归与关闭模型的 API；具体证据沿 AG-CODE-002，云端发布另验 |
| AI 框架 | Spring AI 2.0.1 已接入，默认 `spring.ai.model.chat=none`；直接 Java HTTP API 保留为能力实验路径 | 无密钥可运行原功能；正式 DeepSeek 失败零重试由 Agent 配置控制。框架不代替授权、会话、引用或预算；LangChain4j 仍为候选 |
| 模型与输出 | 用户已选 DeepSeek；`deepseek-flash` 已完成独立及正式页面四组自造资料核对，见 [005 证据](../specs/004-research-agent/tasks.md#14-ag-code-005工具追问与真实样本核对2026-10-14) | 用相同问题核对正确性、时延与费用；JSON／DTO 校验只证明结构，不自动证明内容正确。密钥仅在服务端配置；总体真实质量仍分项验收 |
| 工具与上下文 | 观点／报价／日 K／限定财务／报告段落五类工具与有限原生循环已接入；ChatModel 负责一次传输，Java 显式执行工具 | 工具从业务 Service 取数据，可信对象由 Java 绑定并重校验；观点按成员／日期，报告按当前股票及可选年度。每轮仍最多三次工具尝试、四次模型调用；有限年报章节不等于任意全文 |
| 会话存储 | 已用 MySQL 保存个人 session／message／run 和独立费用 budget；不为当前功能另建 Redis 聊天存储 | 同对象个人记录、有效历史整条裁剪、清空版本及恢复已接入；完整变化／断线场景沿 006，运行明细保留期限上线前落实。保存会话与独立报告分开，不机械建立两份状态存储 |
| 报告维护 | 当前单机小语料采用原子文件保存公开正文／发布版本／状态，Java 命令同步处理，查询按当前发布切换索引 | 短文件锁、任务代次校验、失败保留旧版、显式重试／恢复；不新增 MySQL 报告表或 MQ。生产共享状态、自动任务推进与物理回收另评，边界只见 [设计 4.4](../specs/004-research-agent/design.md#44-报告资料库与-rag-接入) |
| 页面与响应 | Vue／TypeScript 梳理／追问改用带认证头的 fetch SSE，原 JSON 接口保留 | 已校验正文一次交付，实时阶段与主动停止已验证；连接断开只解除订阅，不代表停止在途计费。状态恢复沿原编号 GET，不自动重发模型；逐字正文和总体质量另验 |
| MCP 本机入口 | 官方 Java SDK 2.1.0／Jackson 3，独立 stdio，实际协商 2025-11-25 | 一个账号／周期绑定的只读观点工具，官方客户端实际发现与调用原 HTTP／JWT／Service／MySQL；参数、权限变更、失败／超时和进程退出已验证。普通 Agent 不绕 MCP；远程 OAuth、产品客户端接入及个人能力另验，见 [009 证据](../specs/004-research-agent/tasks.md#30-本机只读-mcp-服务与真实客户端2026-10-18) |
| 观测与可靠性 | 运行版本、模型／工具次数、有限轨迹、耗时、Token／费用及白名单失败分类已保存；额外观测平台按需要接入 | 失败诊断只含阶段／轮次／HTTP 状态／固定分类和编号，不保存异常消息或原始响应；超时分类、零重试与未知用量保留占用有定向证据，旧失败不能补造根因 |
| 质量验证 | 复用 JUnit／Mockito 与隔离业务测试，加固定问题集和独立真实模型评测，增量计划 | 替身测试验证工具、权限和错误边界；真实模型检查工具选择、归纳和引用。评分结合程序与人工，保留运行条件、版本、执行记录、坏例子与修改前后比较；具体验收沿 AG-CODE-007／008，不以文案完全相同作为正确标准 |

2026-10-11 的独立组件预检及教学工程撤回事宜保留为历史记录。2026-10-12 主工程已锁定 Java 17／Boot 4.1.1／Spring AI 2.0.1／MyBatis-Plus 3.5.17，兼容接入证据见 [任务第 9 节](../specs/004-research-agent/tasks.md#9-ag-code-002正式后端框架接入2026-10-12)。梳理、个人页面及观点／报价／日 K 沿 004／005，限定财务沿 001F／005F；008 已在两份受审报告范围完成本地向量／关键词混合、重排对照、受审摘录及维护，009 已验证本机只读 stdio。生产共享维护、005F 原年度题真实恢复、007 总体真实质量和更广财务覆盖仍待完成，不以本机协议替代模型质量。

版本依据：官方 [Spring AI 兼容矩阵](https://github.com/spring-projects/spring-ai#getting-started) 列出 2.x 对应 Boot 4.x、1.1.x 对应 Boot 3.5.x；但 [Boot 3.5.16 发布说明](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/) 已声明 3.5.x 结束开源支持。因此 3.5／AI 1.1 只作为历史兼容关系或迁移中间步骤，不能默认当作新的长期目标。[Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html) 允许 Java 17；[MyBatis-Plus 官方说明](https://baomidou.com/getting-started/) 提供自 3.5.13 起的 Boot 4 starter，原项目的 Boot 3 starter 3.5.5 已更换为 Boot 4 starter 3.5.17。业务 ObjectMapper 已迁移到 Jackson 3；注入／序列化和时间行为按 [官方迁移说明](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide) 验证，不能由官方组合存在推断项目已经兼容。

接入原则：先在隔离的 Java 17 能力实验中验证少量数据、一次模型调用和一个受限工具；主框架及 Boot 迁移做独立兼容实验，不先投入整项目升级再判断模型是否适合。后续接入在代码副本和隔离环境验证，避免改动生产配置；必要时经过中间版本迁移，部署目标仍需处于支持期。LLM 网络请求不放进长期数据库事务，先读限定数据再调用模型；结果注明范围和时间。模型失败时可关闭 Agent，核心业务继续运行。框架不适用时评估直接 API 或已验证的核心库适配，保持业务工具契约，不无限扩大升级范围。

补充依据：[LangChain4j 官方支持版本](https://docs.langchain4j.dev/tutorials/spring-boot-integration/) 要求其 starter 使用 Boot 3.5+／4；[EventSource 接口](https://developer.mozilla.org/en-US/docs/Web/API/EventSource/EventSource) 的构造选项没有自定义 Bearer Header。官方 [DeepSeek 工具调用](https://api-docs.deepseek.com/guides/tool_calls/) 与 [千问工具调用](https://help.aliyun.cn/zh/model-studio/qwen-function-calling) 均提供模型提出调用、应用执行、回传结果的 API 路径；本轮选择及费用只见第 3.5 节，后续调整按同一问题集验证。

RAG 是 [完整 V2 必做功能](../product/requirements-v2.md#33-必做阶段受控文档-rag)，须完成系统维护的公开报告资料检索与带依据回答并接入研究助手；独立小实验用于验证，不能替代业务交付。掌握解析、分块、Embedding、检索、权限过滤、重排、来源和评估，只选一个向量库，Qdrant 为优先评估候选，不改变 MySQL 业务库。维护与脚本导入方向已确认，具体报告覆盖和技术方案经小样本验证后落实。

既有 MCP 学习目标按 [至少一个 MCP Server 的实践边界](../product/requirements-v2.md#34-必做学习实践至少一个-mcp-server) 落实到 AG-CODE-009：实际完成客户端发现和调用一个受控只读工具，优先评估发布已有研究查询。站内同应用工具先走 Java Service；不要求自研完整 Client、Server 和网关平台，客户端可用现成实现。身份与数据权限不能由连接成功代替。Python 先满足示例与评测脚本阅读修改；复杂工作流、长期偏好记忆和完整观测平台按真实问题选型。

以上区分独立实验与业务计划，具体数据关系、工具参数、额度和页面交互沿正式 specs。未通过主项目兼容性、真实业务模型与功能验收前，不把业务计划改成“已使用”，也不将实验范围扩大为简历中的正式产品成果。

### 3.3 外部数据、MCP 与现成平台复用

2026-10-08 补充核实：用户已确认首个研究梳理场景，并保留观察条件核对、空间变化简报的后续需求；产品结果只见 [V2 场景与后续方向](../product/requirements-v2.md#31-已确认首个场景单股研究梳理与追问)。本节记录可行性证据与方案建议，未接入新数据源、购买服务或修改已有行情传输。

| 对象 | 当前核实到的能力与依据 | 本项目接入判断 |
| --- | --- | --- |
| WorkBuddy | [官方连接器文档](https://open.workbuddy.cn/docs/connector) 支持 MCP＋Skill 或 CLI＋Skill，可连接第三方工具 | 可作为现成调用入口；需要向它提供本项目的工具、身份授权和业务契约，不能由支持 MCP 推断已经接好所有金融供应商 |
| 企业微信智能机器人 | [腾讯发布的产品更新说明](https://apps.apple.com/cn/app/%E4%BC%81%E4%B8%9A%E5%BE%AE%E4%BF%A1/id1087897068) 已列出知识集、API／MCP 插件、工作流与自研智能体接入 | 文档问答和部分工具流程可配置实现；平台能力需按实际需求、权限和费用试用比较，不能假定平台只能读静态文档 |
| 通达信 | [官方 AI 服务说明](https://help.tdx.com.cn/tdxclaw/docs/markdown/buy_points.html) 列出 MCP、词元和数据接口服务并使用 AI 积分；[官方 TdxAiData 文档](https://help.tdx.com.cn/quant/docs/markdown/mindoc-1hjbgqpdhv114.html) 提供有 Key 的跨平台后台数据接口 | 存在官方 MCP／API 路径；选用前确认具体工具、认证、额度、实时权限和数据使用范围，优先验证一个受限只读工具 |
| Wind | [官方 Client API](https://www.wind.com.cn/mobile/ClientApi/zh.html) 提供行情及其他金融数据；[开发者 Wind-MCP 项目](https://github.com/QmQsun/Wind-MCP) 将 WindPy 封装为 MCP，当前该实现要求 Windows、已登录 Wind 终端与 WindPy | API 加 MCP 适配在设计上可行；本次未确认可直接使用的官方公共 Wind MCP 服务。该开源实现与 Wind 官方服务分清，源码许可不替代账号／数据授权，云端运行条件须另验 |

MCP 负责统一工具发现和调用，不提供数据许可或实时性保证。模型按问题发起读取；适配层统一股票身份、字段、来源、数据时间与错误，Java 仍控制本项目授权范围。实时或延迟数据以所购数据权限与返回时间为准；纯行情接口不自动提供新闻、公告或所有财务证据。

2026-10-09 财务接入补核：按用户补充的股票问答要求，将有界财务事实查询纳入 V2 先行研究、行情与财务闭环，先用已有 Longbridge 官方能力评估，无需以外部 MCP 为前置。当前 `openapi-sdk:4.5.0` 已含 FundamentalContext 财报方法，独立程序已取得 A／港代表样本的年度／半年度数字；财务业务适配尚未实现，原报与供应商展示口径、季度及生产路由未全部验收。数据能力表、实际响应差异及受限读取方案只见 [SPEC-004 设计第 4 节](../specs/004-research-agent/design.md#4-首批工具适配)，实际运行只见 [任务证据](../specs/004-research-agent/tasks.md#5-独立财务源预检2026-10-09)。开发复用 SDK 授权，生产保留 CLI 分工；正式接入前先验证两条路径。结构化财务数字增量为中等难度，主要增加字段／口径／期间、来源及可比性处理；全文附注、扫描文件和复杂因果分析须另验，不将它们捆绑为财报问答起步条件。

建议保留两条可复用路径：主项目 Vue＋Java Agent 调用本地 Service，外部工具经条件验证后接入；AG-CODE-009 的 MCP Server 实践优先评估发布同一只读研究能力，实际 WorkBuddy 产品接入按场景与授权另验。外部 MCP Client 与本项目 MCP Server 是两个不同角色，可分别实践，均不要求修改业务库或复制权限规则。WindPy 等专用 SDK 必要时由小型 Python 适配进程承接；已有 Java 业务与行情网关保持各自职责。

后续方向的难度是工程评估，尚无真实模型／外部连接验收：

- 观察条件核对：将条件限定为明确的标的、指标、期间和阈值，并由程序计算，增量为中等难度；从任意自然语言自动抽取条件、核对财报／公告并解释复杂因果，增量为中等偏高到高。先确认条件含义，再区分有证据支持、未满足与无法核对，具体状态名称待规格确定。
- 空间变化简报：用户主动生成一个空间、有限时间范围的变化摘要，增量为中等难度；跨大量股票、持续刷新、定时生成、通知和失败恢复会增加排序、成本与异步任务难度，需分阶段实现。
- 外部 MCP：一个已有授权的只读工具、固定任务与来源验证，增量通常为中等难度；多供应商、多用户凭证、跨主机终端适配和持续生产运行可达到中等偏高到高，不能仅按 MCP 配置行数估算。

选型意义：相同数据、工具和规则配置给现成平台，也可能实现类似功能；自写代码不天然带来更强模型、独有功能或产品优势。当前学习目标是能够实现和解释研究查询、权限、工具执行、会话、结果校验及质量验证，这些也可封装给平台复用。实际产品收益需通过用户使用效果证明，尚无商业竞争力验证。新依赖只在有代表性问题、可解释收益、数据授权与可控费用时进入规格。

### 3.4 可行性验证与知识安排

2026-10-08 最后复核：以下是基于实际代码、官方 API 与 [岗位／面经／作者复盘](../project/java-agent-market-benchmark.md) 的规划。代码可确认已有研究、成员权限、行情和持久化基础；当时真实模型质量、Agent 运行及升级兼容均未验证。不能承诺绝对不会失败，通过最早小实验和退出路径控制投入；后续已验证状态见本节的任务证据链接。

研究资料的两个数据边界仍须区分：现有 [研究时间线](../../src/main/java/com/stock/system/stock/service/StockResearchLifecycleService.java) 跨周期分页，每页 20 条；周期资料 Service 按选定周期直接查询，返回完整／超限范围、成员／日期缩小条件并在交付前重校验，证据见 [任务第 12 节](../specs/004-research-agent/tasks.md#12-ag-code-003范围行情与上下文接入2026-10-13)。结束周期未保存结束价快照，不能直接承诺精确结束期涨跌或投资收益。财务／订单类观察条件不由现有行情自动验证，缺资料则说明无法核对。

| 最早验证 | 最小实验与可检查结果 | 条件不成立时的处理 |
| --- | --- | --- |
| 数据与模型结果 | 少量固定观点样本，含两成员、判断变化、撤回和缺资料；核对归纳及引用是否对应实际资料 | 缩小输出、分步归纳或比较另一种模型；不先处理无限历史，不把结构正确当内容正确 |
| 工具与追问 | 一个周期查询工具和几种代表问题，检查模型请求、Java 校验、返回结果与停止原因；重复执行观察波动 | 改工具描述／模型；固定梳理流程可先交付，但明确自主工具选择尚未通过，不假称完整 Agent 已完成 |
| 主框架与主项目 | 隔离副本验证认证、Mapper、JSON／时间、Flyway、行情及既有 API；同时实际使用目标框架的模型、工具和检索接口，关闭 Agent 时核心流程仍可用 | 直接 Java HTTP API 可保留阶段成果，不能据此关闭“掌握一种 Java AI 框架”的学习目标；再验证可兼容的核心库或另一候选框架，避免只安装依赖即宣布完成。现有旧 Boot 的维护问题另处理 |
| 财务数据源 | 尽早在不调用模型／不额外建立行情连接的环境中验证 A 股、港股代表样本及 SDK／CLI 路由；核对源字段、口径、报告期、币种／单位、缺失和一个对应披露数字 | 先定位未覆盖、无权限或接口故障；评估合法公开披露或另一可用来源，限定可验证范围。首次研究＋报价页面可以先展示，但不能将财务预检失败写成先行财务闭环通过；新增付费授权另定 |
| RAG 资料与检索 | AG-CODE-001R 提前以少量自造文字和选定的公开报告章节验证解析、来源定位、中文 Embedding、一个向量库与相关／无答案检索；不用等四工具或 SSE 完成。独立读取和检索可先验证，真实材料发送范围先落实 | 分别定位解析、模型、运行资源和检索质量问题；可限定已核实的可读章节或更换经过验证的文本向量模型。不能以关键词或整份文字塞入模型假称完成向量检索目标；新服务账号／费用沿已有授权边界另定 |
| MCP 与外部数据 | 以一个受控只读工具验证协议协商、发现、调用和权限；外部金融来源另查字段、时间、授权与故障 | 可先对本项目已有 Service 做协议适配；不把 Wind 订阅、通达信接入或批量公告采集作为该学习目标的唯一实现路径 |

首轮小实验及退出方法见 [SPEC-004 设计](../specs/004-research-agent/design.md#7-最早实验与退出路径)，具体问题和证据准入见 [任务计划](../specs/004-research-agent/tasks.md#2-小实验固定问题与质量核对)。输入、调用、并发与预算只维护在 [设计第 5 节](../specs/004-research-agent/design.md#5-调用费用和失败限制)，正式保护已沿 004／005 验证。替身资料、真实模型、正式 Service 和实时行情分别验证，不互相替代；真实资料发送范围接入前落实。

最小实验通过后再扩固定问题集和真实模型回归。权限、工具参数、来源 ID 等程序边界必须由 Java 校验；阅读代码或跑替身测试不能替代真实模型验证。独立 Java HTTP 与单工具的自造样本核对见 [历史实验](../specs/004-research-agent/tasks.md#7-真实模型实验与修正验收2026-10-09)，正式生成／工具四例见上表 005，配置和代码链路见 [实验说明](../engineering/agent-experiment.md)。完整 V2、真实供应商效果与精确账单仍未验收。

#### 原始 Agent 目标覆盖与验收

2026-10-09 对照原始清单第十一～二十一项复核。下表只维护原始目标、技术机制与任务的对应关系；教学范围、深度、顺序和个人完成标准只见 [V2 学习地图](../project/roadmap.md#v2-功能与学习地图)，正式需求和功能证据分别见 product／specs，不以“已写入规划”或某个实验通过代替全项验收。原始第十一～十九项的核心目标均保留；第二十项明确后学，第二十一项保持 Java 主语言及基础 Python 能力。

| 原始条目／知识组 | 对应技术机制 | 实践与证据落点 |
| --- | --- | --- |
| 十一：LLM 应用与 Context Engineering | 概念级生成原理、Transformer／Attention 的作用、Token／窗口、Embedding、RAG 与微调区别；消息／System Prompt、参数、JSON Schema 与输出校验；何时取历史、检索或调用工具；模型切换与路由基础 | 能结合本项目解释原理与局限，不要求模型训练、数学推导或框架内核。AG-CODE-001 仅在限定样本通过。正式上下文、结构化输出、SSE、调用限制与用量沿 004／005／007；理解模型替换与压缩，不起步建设多模型自动路由或自动付费摘要 |
| 十二：Tool Calling 与 Agent | Tool Schema、Java 执行与回传、Agent Loop、ReAct、任务拆解、Workflow 区别、状态及停止／失败行为 | AG-CODE-003／005／005F；实际 Service 与多工具组合、错误回传和有界循环须验收，不由固定摘要代替。能解释一次问题怎样查证、继续补查与结束 |
| 十三：Memory 与上下文管理 | 历史和有效上下文区别、短期／长期记忆、摘要与整轮裁剪、Token 控制、持久化及隔离 | AG-CODE-004／006；实现个人会话状态、有效依赖、有界历史与清空。摘要压缩及长期记忆理解原理，当前不增加长期偏好库 |
| 十四：RAG | Parse→Chunk→Embedding→Vector Store→Retrieval→Rerank→Context→LLM；相似度、TopK、元数据过滤、关键词／向量／混合检索、来源和评测 | 必做 AG-CODE-001R／008；提前预检，随后接同一研究助手。实际对照关键词、向量与混合检索，并做一次重排前后对照；是否默认启用重排由质量、延迟与费用决定，向量库只选一种 |
| 十五：MCP | Client／Server、Tool／Resource／Prompt、协议与工具发现；与 REST、Tool Calling 的关系 | 必做 AG-CODE-009；实现或接入一个 Server，客户端实际发现与调用，核对权限及错误。Resource／Prompt 等先理解，不强制全部发布；不以 Wind／通达信许可为前置 |
| 十六：一种 Java AI 框架 | ChatModel／ChatClient、结构化输出、工具、Memory、RAG／Vector Store、MCP、Advisors、流式与观测各自作用 | 主选 Spring AI。AG-CODE-002 验证实际组件使用与兼容，后续业务沿通过的框架接入；直接 HTTP 退出路径保留阶段成果，框架学习目标未完成时须显式保留。LangChain4j 为经验证的候选，不同时深学多套 |
| 十七：Agent 工程化 | 超时、有限重试、限流、熔断／降级、幂等、并发；运行状态、版本、Tool Call、延迟、Token、Cost、Error、Trace | AG-CODE-004～007／008／009；有请求／运行关联 ID、失败阶段和调用轨迹、基本指标，能从一轮问题定位故障。线程池／CompletableFuture 结合实需；熔断先理解并在故障演练中评估，MQ／长任务恢复及完整观测平台不作首个页面前置 |
| 十八：Evaluation | 标准问题与答案／依据、自动与人工核对、回归；工具成功和任务成功区别，检索、时延及成本怎样比较 | AG-CODE-007／008／009；记录固定问题集与评分规则、实际次数和分母、必要重复运行及失败样本。分开评估工具选择、检索命中、引用支持和任务完成；实际完成基线、修改和复测，沿 007／008 保留比较及故障定位证据，不只声明能比较 |
| 十九：Agent 安全 | Prompt／Tool Injection、参数及权限、数据与凭证隔离、输出校验、高风险操作人工确认 | 贯穿正式任务；程序拒绝越权、文档诱导、伪造来源和敏感信息泄露。当前只读；高风险写入确认理解原理，不为演示增加交易或业务写权限 |
| 二十：后学内容 | Multi-Agent、高级 Planning／Reflection、Deep Research、A2A、Graph RAG 与高级长期记忆解决什么问题 | 按原清单后学，不进入本版必做能力，不以技术名称数量扩大本版 |
| 二十一：基础 Python | 读改脚本、requests／FastAPI 基础、与 Python 服务的调用关系 | 随评测与必要适配练习，Java 保持主语言；未有专用 Python 数据接口需求时不增加生产 Python 服务。若明确投 Python 为硬要求的应用岗，另补独立 Python Web／API 能力；本路线不由脚本基础声称覆盖 Python／PyTorch 算法岗 |

原始第一～十项的 Java 基础、一次 SQL 优化、Redis 深度、真实异步 MQ 场景、测试、交付和分布式基础继续沿既有路线，不由上述 Agent 表取代，也不因暂未使用而删除。RAG 资料导入／重建可作为真实异步场景的候选，先比较耗时、失败恢复、受控线程池／任务表与一种 MQ 的成本；尚未选择或接入 MQ，不能把它默认为 RAG 必需依赖或已完成学习成果。

学习顺序、阶段深度与本人产出沿 [V2 地图](../project/roadmap.md#v2-功能与学习地图)，目标岗位的语言／框架／资格差距沿 [岗位分流](../project/roadmap.md#岗位分流与学习优先级)。原始完整目标保留在 [技术参考](../project/technology-reference.md#用户原始文字)；本表不另设课表或个人掌握记录。

RAG 技术依据：[Spring AI 文档检索与上下文增强](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)、[资料读取与分块管线](https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html) 和 [Embedding API](https://docs.spring.io/spring-ai/reference/api/embeddings.html)。回答模型与文本向量模型分别验证；不能将默认 similaritySearch 调用或空检索后的流畅回答当作通过。008 的限定范围、真实本地向量及最终来源支持证据只见 tasks。MCP 发现／调用依据 [MCP 官方架构](https://modelcontextprotocol.io/docs/learn/architecture)、[Java SDK](https://java.sdk.modelcontextprotocol.io/latest/server/)及 [stdio 传输](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)；本机实现直接使用核心 SDK，未引入 Spring MCP starter 自动发布业务工具。官方组件存在不证明本项目兼容，009 的实际打包客户端和权限证据另列 tasks。

### 3.5 首轮模型与费用安排

2026-10-08：用户指定 DeepSeek，并表示了解小额接口调用费用。首轮推荐官方接口的 `deepseek-flash`，当前 [官方模型说明](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/) 对应 DeepSeek-V4.1-Flash，支持工具调用与 JSON 输出；先用非思考模式验证本项目任务。记录请求／响应中的模型名称、验证日期与提示词版本，模型名对应版本变化后重新核对，不把别名当成永久固定版本。官方也有 [WorkBuddy 接入说明](https://api-docs.deepseek.com/zh-cn/quick_start/agent_integrations/workbuddy/)，其模型接入同样需要地址、API Key 和模型名；本项目由 Java 后端调用，并提供自己的业务工具与权限边界。

2026-10-17 六题运行前复核同一官方页，deepseek-flash 的公开对应版本已为 DeepSeek-V4.1-Flash-1017，高峰未缓存输入 2 元／百万 Token、输出 8 元／百万 Token 保持。保存该日期与官网版本说明，实际 API 响应仍只返回模型别名，不能据此宣称锁定内部权重；本轮继续使用非思考模式及保守高峰费用上界，结果和局限见 [任务第 21 节](../specs/004-research-agent/tasks.md#21-财务六题真实检查与问答存储说明2026-10-17)。

选 Flash 的理由是能以较低成本先验证所需流程；没有做模型效果排名。若固定问题上反复出现错误引用或工具选择不当，先调整输入／工具描述，再用同一问题集比较 DeepSeek Pro，费用另核，不直接断言更贵就更合适。新增成本是网络调用、用量控制与错误处理；AI 框架不影响先用 Java HTTP 接口检验能力，失败时沿第 3.4 节缩小结果或替换适配。

按官方当前人民币价格，高峰时段 Flash 每百万未命中缓存的输入 Token 2 元、输出 Token 8 元；空闲时段分别为 1 元和 4 元。Token 是模型计量文本的单位，不能按汉字或观点条数直接换算。按较高价格估算，一次模型请求输入 5,000、输出 1,000 Token 为 `5000 × 2 / 1000000 + 1000 × 8 / 1000000 = 0.018 元`，同样大小的 100 次请求为 1.8 元。以上是输入假设下的估算，实际请求的历史消息、工具清单、工具结果和多次调用都计入用量；真实费用以对应接口与账单为准，不能把官方价用于未经核对的中转平台。

2026-10-09：用户确认首轮累计最多 10 元的实验费用上限，并确认使用 DeepSeek 官方平台；独立实验已发起请求，用量与费用上界只见 [任务第 7 节](../specs/004-research-agent/tasks.md#7-真实模型实验与修正验收2026-10-09)。运行前核对可用额度并预留下一请求的费用；限制请求次数、输入与输出，每次记录实际用量，达到预算停止，不靠提示词约束自动循环。该上限只控制本项目实验，不控制同一账号在 WorkBuddy 或其他程序中的用量。API Key 在本地服务端配置，不在聊天、前端或仓库中保存明文；电脑运行 Java，模型由服务商运行，不以本地显卡、训练或自部署大模型为本轮前置。

接口依据：[工具调用](https://api-docs.deepseek.com/zh-cn/guides/tool_calls/) 明确实际函数由应用执行；其 strict 工具参数模式仍标为 Beta。首轮普通工具调用的参数由 Java 校验，不把 Beta 功能作为接入前置；[JSON 输出](https://api-docs.deepseek.com/zh-cn/guides/json_mode/) 需设置输出格式和提示样例，并注意空结果及截断。工具参数 Schema 与最终回答 DTO 分别校验，不把工具的 strict 能力推断成所有最终文本都能严格符合 Schema。

2026-10-08 模式参数补核：[官方思考模式](https://api-docs.deepseek.com/guides/thinking_mode/) 说明默认开启思考。首轮 HTTP 请求显式设置 `thinking: {"type": "disabled"}`，不能只在提示词里要求“不思考”；以后启用思考工具调用时，另验 reasoning_content 的协议回传要求。补核时仅有文档证据；2026-10-09 独立真实实验已使用这一参数，业务接入仍待后续任务。

### 3.6 报告检索预检与 Java 首段接入

001R 先解决中文报告能否准确读出、定位及检索的问题。当前本机没有 Docker，预检选择已有公共 PDF、隔离 Python 与本地 CPU 中文模型，采用 [Qdrant 官方支持的本地模式](https://qdrant.tech/documentation/tutorials-basics/generate-embeddings-fastembed/)验证持久化／过滤；不为这一步安装 Docker 或新增远程服务。候选替代是服务端 Embedding API 与正式 Qdrant 服务，但外发、费用和 Java 通信需分别落实，不默认借用原 DeepSeek 实验预算。

[BGE-small-zh-v1.5 原模型说明](https://huggingface.co/BAAI/bge-small-zh-v1.5) 给出中文用途、512 维及查询指令；本轮使用 Qdrant 仓库固定提交的 ONNX 文件，资产约 95 MB，模型清单与哈希只维护在 [model.json](../../scripts/rag-preflight/model.json)。FastEmbed 0.9.0、ONNX Runtime 1.31.0、tokenizers 0.23.3 和 Qdrant client 1.19.1 仅进入 [隔离预检依赖](../../scripts/rag-preflight/requirements.txt)，不加入 Spring 运行依赖或自动建立 Python 服务。实际资源及成绩只见 [任务第 22 节](../specs/004-research-agent/tasks.md#22-中文报告解析与本地检索预检2026-10-17)。

解析候选采用 PyMuPDF 1.28.2，已核对腾讯中文原版能恢复原 pypdf 6.11.0 未读出的汉字；原有英文财务提取路径保留。哈希、页数、受审段落哨兵与码点定位共同检查，失败不猜原文。模型、分词器或编码依赖变化必须重建相应索引，未知维度／截断／零向量拒绝。候选门槛按固定题校准并留出改写验证，不把分数当答案证据。Java 已加入受审标题／正文的本地 BM25、RRF 混合与查询词覆盖重排，实际对照后默认混合、重排关闭；不新增模型或收费服务，参数和退出方式只见 [设计 4.4](../specs/004-research-agent/design.md#44-报告资料库与-rag-接入)，008 已在两份受审年报的摘录交付路径通过真实六题关键来源核对，具体范围和模型自由概括限制只见 [任务第 28 节](../specs/004-research-agent/tasks.md#28-报告六题真实对照与财务正文防错2026-10-18)。若中文解析或检索不能成立，先缩到可读章节或验证替代，不能仅以组件能安装标为业务 RAG 可用。

2026-10-18 的 008 首段在主工程沿用同一权重和受审正文，加入 ONNX Runtime Java 1.20.0、DJL tokenizers 0.38.0 与 Spring AI VectorStore 运行依赖，由 Java 直接本地编码并查询 Qdrant 1.19.2 官方 Windows 服务端，未引入 Python 常驻服务。选择该 ONNX 版本是因为 1.31.0 在当前 JDK17 附带的旧 MSVC 库初始化时发生原生崩溃；更换兼容版本后实读问题集及浏览器，未修改系统 DLL 或 JDK。Python 预检的 1.31.0 保留，不能混称两条运行路径。BGE 需要 CLS 池化，自建薄适配实现 EmbeddingModel；不直接使用默认平均池化的通用 Transformer 适配。技术依据见 [Spring AI 本地 ONNX](https://docs.spring.io/spring-ai/reference/api/embeddings/onnx.html)、[ONNX Java](https://onnxruntime.ai/docs/get-started/with-java.html)、[Windows 运行库要求](https://onnxruntime.ai/docs/install/) 和 [Qdrant 固定发布](https://github.com/qdrant/qdrant/releases/tag/v1.19.2)。当前方案及完整边界只见 [报告接入设计](../specs/004-research-agent/design.md#44-报告资料库与-rag-接入)；本机开关、安装资产与复现见工程笔记，不代表生产部署或完整 008。

## 4. 当前明确不采用

| 技术/方案                           | 原因                                                                                                            |
| ----------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| Spring Cloud 微服务                 | 当前没有独立部署、扩缩容或团队边界                                                                              |
| Elasticsearch                       | A 股代码和名称搜索当前可由 MySQL 和索引满足                                                                     |
| Kubernetes                          | 个人项目当前没有多节点编排需求                                                                                  |
| 分库分表                            | 没有相应数据规模和容量证据                                                                                      |
| 完整元数据平台 / EAV / 动态表单引擎 | V1 的空间、股票、观点和研究周期结构明确；采用强类型业务字段与少量审计、来源元数据更容易约束、查询、测试和讲清楚 |
| AI 自动荐股                         | 偏离产品定位，并存在准确性、合规和风险问题                                                                      |
| Multi-Agent                         | 当前没有多个自主角色协作的真实必要性，单 Agent 工具闭环更易测试和解释                                           |
| Spring Modulith / ArchUnit          | 当前只采用按业务包组织和依赖方向规则，尚无边界失控证据，不增加结构校验依赖；需要自动检查模块依赖时再评估        |

## 5. 维护规则

当前目录采集辅助工具使用 Python 3（2026-09-21 以 3.14.7 验证）、pandas/openpyxl 解析公开 JSON/Excel，使用 `opencc-python-reimplemented==0.1.7` 批量繁简转换，并使用 `pypinyin==0.55.0` 的词组词典离线生成小写拼音首字母。它们仅作为本地目录准备工具，Java 继续负责校验、事务入库及 API 搜索；没有引入 Python 常驻服务或 AKShare 运行依赖。部署/复现方式见 [目录导入操作](../engineering/development.md#8-股票目录导入)。

官方年报的限定数字导入使用 [pypdf 6.11.0](../../scripts/requirements-report-source.txt)，只在项目隔离 Python 环境运行。它解决受审文本 PDF 的金额、年份、单位和页码提取，不进入 Spring 运行依赖或建立 Python 服务；不支持扫描件／通用表格，该财务脚本也不生成 Embedding 或写向量库。哈希／布局变化失败后保留旧目录，退出路径是重新审阅来源与映射，不能用模型补猜。机制见 [财务设计](../specs/004-research-agent/design.md#42-财报查询的边界与来源)，复现见 [工程笔记](../engineering/agent-experiment.md#9-财务读取准备数字必须带口径)。财务数字导入阶段的 PyMuPDF 1.28.2 仅用于原页渲染，没有加入该导入依赖；后续 001R 的中文正文解析预检另见第 3.6 节。

- 新增、移除或升级技术后必须同步更新本文件。
- `package.json`、`pom.xml` 与本文件版本冲突时，以依赖清单为事实并修正文档。
- `计划` 和 `候选` 不能写进简历的“已实现技术栈”。
- 技术达到“已使用”至少需要代码或配置证据以及与风险匹配的验证结果。
