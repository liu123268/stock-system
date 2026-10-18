# 测试与验收规则

更新时间：2026-10-14

## 1. 测试目标与选择

任何测试或临时进程任务都读取本文件。根据风险选择最小但充分的检查：纯文档检查链接与规则；小代码改动检查相关模块；纵向闭环检查后端、前端和关键浏览器流程；大阶段收尾再全量回归，不因每次修改机械执行全套测试。

测试不是只证明正常流程能运行，还要证明参数、权限、事务、数据隔离、失败响应和清理行为符合规则。

PowerShell 用 `npm.cmd`，后端使用系统 `mvn`。

## 2. 后端测试

- 使用 JUnit Jupiter 和 Spring Boot Test；版本由 Boot BOM 管理，当前版本见 [技术栈](../architecture/technology-stack.md#1-后端与数据)。核心 `@Test`／断言／Mockito 用法继续结合真实模块教学，不因版本升级重开整套课程。
- Controller/API 集成测试使用 MockMvc。
- 认证链路覆盖注册、登录、错误密码、缺失 Token、非法 Token、当前用户和统一 401。
- 空间创建、列表、详情、非成员访问、邀请状态、成员退出/移除/重入、所有权转让、归档恢复、归档只读和无效操作已经有真实 MySQL 集成测试；超级管理员测试覆盖普通用户越权、分页治理查询、禁用／恢复、旧 Token 全局失效、治理归档／恢复、邀请码撤销、敏感研究字段隔离及成功／失败日志事务。后续继续覆盖其余并发竞争。
- 使用真实 MySQL 验证 Flyway、生成列、检查约束、外键和行锁；连接保护统一执行第 4 节。
- 当前本机没有 Docker 命令，阶段 1 不依赖 Testcontainers 或 H2；后续具备 Docker 环境且需要 CI 隔离时再评估 Testcontainers。
- 邀请并发测试使用 6 个独立线程与 `CyclicBarrier` 同步起跑，验证 5 人成功、1 人收到 `INVITATION_EXHAUSTED`；所有权并发测试验证旧 OWNER 同时向两个目标转让时只有一个成功。后续继续覆盖并发同名创建和并发重新生成邀请。
- 多步事务回滚通过测试配置中的可控替身制造中间失败，不在生产代码加入测试故障开关。
- 股票研究测试已覆盖 V3 表与生成列唯一约束、模拟搜索、可信行情字段、OWNER/MEMBER 添加、非成员和归档拒绝、重复添加、跨空间隔离，以及观点写入受控失败时前三类写入整体回滚。
- Maven 普通回归会启动 Spring `test` profile 并连接本机 `stock_system_test`。运行前必须让启动 Maven 的进程能够读取 `DB_PASSWORD` 和不少于 32 个 UTF-8 字节的 `JWT_SECRET`；当前本机数据库用户名固定为非敏感配置 `root`，不要求 `DB_USERNAME`。在 Windows 用户环境变量中新增值后，已经打开的 IDEA、终端或 Maven 不会自动获得新值，需要完全退出并重新打开对应程序。缺少变量时看到 `Could not resolve placeholder 'DB_PASSWORD'` 或 `'JWT_SECRET'`，表示测试进程没有取得运行配置，不是业务断言失败。
- Maven 常用命令：`mvn "-Dmaven.repo.local=D:\Java\stockweb\.m2repo" test`。报告中的测试数必须来自本次完整命令，不能把源码中的 `@Test` 数量或多次残留 XML 相加后写成“全部通过”。

### 2.1 Longbridge 行情传输测试

- 每日交易状态定向检查：`StockStatusChecksIntegrationTest` 在受 catalog 保护的测试库中验证无价格停牌／退市、复牌、旧响应拒绝、失败保留最后确认及过期规则，事务回滚；`StockStatusSchedulerTest` 验证每日截止、批量游标、MySQL 锁争用及上游失败释放。`LongbridgeStatusRealProbeTest` 仅在 `STOCK_LONGBRIDGE_STATUS_REAL_PROBE=true` 时使用已有 `LONGBRIDGE_CLIENT_ID` 查询七个状态样本及混合批次，不启动 Spring／数据库或读取 K 线，结束关闭本次 SDK 上下文；运行前须确认同账号没有已运行的后端行情连接。普通回归跳过该真实探测。
- `frontend/e2e/status-smoke.mjs` 使用 test profile、directory 模式的 18082 后端和代理至该后端的 18083 前端，双重探针与实际 source 校验后才创建专用测试数据；覆盖无报价状态、失败／过期、禁止新关注、核实时间和历史 K 线日期，finally 清理。状态和身份来自真实测试 API／MySQL，K 线响应受控，用于验证页面日期说明，不当作真实长桥历史数据验收。
- `dev` 现选择 SDK OAuth、`prod` 保留 CLI，test 仍 mock。SDK 离线验证在 `LongbridgeSdkClientTest`，覆盖批量报价／交易状态、最近与按日期 K 线、严格 before 边界、分时字段、半日交易日、短缓存、超时关闭重建和错误脱敏；`LongbridgeTransportWiringTest` 覆盖两种 Bean 的互斥选择、真实配置文件及 mock 不建连。离线用例使用 SDK 对象替身，不建立真实行情连接，不能当作 OAuth 成功。
- `LongbridgeSdkClientTest#readsFourMarketCapabilitiesThroughAuthorizedSdkWithoutDatabase` 是同文件中显式启用的真实探测，由 `STOCK_LONGBRIDGE_SDK_REAL_PROBE=true` 控制并读取进程 `LONGBRIDGE_CLIENT_ID`；只有已授权且用户批准验证的账号才能运行。仅读取 `600519.SH` 的报价、日 K、分时、指定历史分钟 K 及交易日历，不启动 Spring 或写数据库，结束关闭 SDK 上下文。默认回归跳过，不能把这项跳过算成真实行情通过；历史 K 会占用该账号的标的额度。
- `scripts/authorize-longbridge.cmd` 通过 `STOCK_LONGBRIDGE_OAUTH_SETUP=true` 显式启用 `LongbridgeOAuthSetupTest`，只执行官方浏览器授权，由 SDK 保存／刷新 Token；不连接 Spring、数据库或行情。公开 client_id 从子进程环境取得，Token／验证码不进入 Maven属性或报告。运行步骤见[开发说明](development.md#92-新账号与官方-oauth-准备)。真实行情须等新账号授权及权限确认后单独验收，不能复用生产账号来证明本地独立账号。
- 普通 CLI 回归不依赖真实账号：`LongbridgeJsonRpcClientTest` 用内存假进程覆盖报价、最近／历史 K 线、当天分时和交易日历协议、乱序 ID 对应、短缓存、`301607`／分钟最早日期保留、超时和关闭；provider 测试覆盖目录身份合并、K 线转换、历史分时均价重建、配额映射、休市日拦截、当天点日期过滤与不可用降级。分时交易时段响应另由 provider 和前端坐标测试覆盖，不能用固定工作日逻辑替代官方交易日历。
- 真实 CLI 测试默认跳过，只有明确验证本机/部署账号授权时才用系统属性 `stock.longbridge.cli.integration=true` 单独启用。测试不得输出 Token、CLI 私有缓存或完整账号信息。
- `LongbridgeCliIntegrationTest` 只证明 Java 管理器能启动/关闭 sidecar 并取得真实报价；`LongbridgeMarketDataIntegrationTest` 还必须通过 `MySqlIntegrationTest` 核对实际 catalog 为 `stock_system_test`，临时目录记录使用专用来源并在事务后核对剩余为 0。
- `LongbridgeCandlestickRealProbeTest` 由 `STOCK_LONGBRIDGE_KLINE_PROBE=true` 显式启用，只读取已批准的 `600519.SH` 两根日 K；`LongbridgeIntradayRealProbeTest` 由 `STOCK_LONGBRIDGE_INTRADAY_PROBE=true` 启用，读取同一股票当天分时和指定交易日 1 分钟 K。两者都不启动 Spring、不写数据库；历史 K 线会占月度不同标的额度，新增探测代码前必须先说明并优先选择已经计入过的标的。
- 启用真实测试前后都要精确核对 `longbridge` 进程。只关闭本轮 Java 客户端创建的进程，不能批量结束用户已有 CLI；失败、超时和测试结束都必须确认无残留。
- 受限执行环境还必须允许 CLI 读取服务账号授权缓存并写其正常日志目录。若只开放项目目录，sidecar 可能启动后立即退出；这不等于 OAuth 或行情权限失效，应与同账号直接执行 `longbridge quote` 做对照。

### 2.2 单元测试怎样运行与阅读

单元测试把一个类或一小段协作逻辑与外部系统隔离，用固定输入检查输出、异常或交互；它通常快、定位清楚，但不能代替真实 MySQL、Redis、Longbridge 或浏览器验收。这个项目的 `*Test.java` 中，使用假进程、替身或内存对象的测试主要属于单元测试；带 `@SpringBootTest`、真实 JDBC 或显式 Redis 的测试属于集成测试，分类以测试实际连接的资源为准。

在项目根目录运行 `mvn "-Dmaven.repo.local=D:\Java\stockweb\.m2repo" test` 会执行普通后端回归。测试报告在 `target/surefire-reports/`：先看 `tests`、`failures`、`errors`、`skipped`，再按失败测试名回到源码中的 `@Test` 方法。Redis 真实连接、Longbridge 账号和浏览器流程都不是普通单元测试会自动启用的能力，必须按本文件相应开关和环境前置单独运行；“测试通过”要同时说明测试层次和未执行的外部探测。

### 2.3 每日目录同步测试

在项目根目录运行 `python -B scripts/test_stock_directory_sync.py`，用临时样本验证集合缺失、新增未知证券、旧下载、错误排除身份、采集失败和 Java 回执不匹配；它不联网、不连接数据库。`StockDirectorySyncCommandTest` 是真实 MySQL 集成测试，核对实际 catalog、幂等提交后 ID／状态保持、缺席股票保留、验证模式双次导入整体回滚、旧快照拒绝和其他连接持锁时拒绝写入；提交型 fixture 带本轮标识，并在 `@AfterEach` 精确清理。既有目录搜索和拼音测试继续保留。

真实公开源＋打包后 Java 的完整验证使用 [开发环境 8.1](development.md#81-每日同步入口与打包后验证) 的 `test verify`。Linux 云端演练同样仅用 `stock_system_test`，生产镜像只启动这个主程序，不启动 Web 服务或额外真实行情连接；数据库密码在临时容器内部读取挂载的测试 Secret，不放入命令行。生产 `prod apply` 属于经授权的数据同步，不包装为测试，也不得清理既有业务记录。Windows 回滚验证和 systemd 语法检查均不能代替定时器实际安装及首次真实同步。

### 2.4 部署环境与生产只读验收

[verify-deployment-failures.py](../../scripts/verify-deployment-failures.py) 在经过核实的 ubuntu／`VM-0-8-ubuntu` 云主机执行，取 `.last-release` 对应镜像，创建绑定回环 18082 的独立容器。运行前要求该端口空闲、`stock_system_test` 的指定股票 fixture 不存在；以 `prod,test` 启动并校验 test 环境探针与实际 JDBC catalog。测试账号／空间随机生成，只在测试库写入；CLI 使用本轮替身且不挂生产长桥授权，Redis 使用随机独立前缀。

复现时先只上传这一份脚本到部署目录 `.incoming/`，再执行 `python3 .incoming/verify-deployment-failures.py`；它验证 CLI 缺失／未授权／超时、共享 Redis 在测试后端重启后命中、Redis 不可达降级及退出 sidecar 重建。生产四容器 ID 前后必须相同。finally 按随机标识删除账号／空间／研究 fixture、Redis 测试键，按容器标签确认归属再删除测试容器，并核对剩余 0。这是正式镜像在部署依赖环境中的测试，不是操作真实生产供应商故障。

[production-readonly-smoke.mjs](../../frontend/e2e/production-readonly-smoke.mjs) 在本机 `frontend` 目录用 `node e2e/production-readonly-smoke.mjs` 执行，默认公网地址在脚本中，可用 `PRODUCTION_URL` 指定 HTTPS 地址。真实 Chrome 保留严格证书校验，只访问公开页面、未登录认证接口和缺失静态资源，验证桌面／手机、401、404、HTTP 跳转、无明文子资源与页面错误；不注册账号或写业务数据。截图／JSON 默认在忽略的 `target/v1-production-readonly/`。用户自己正常登录的生产手工验收单独记录，不据此放宽自动化只能写测试库的规则。

### 2.5 研究助手的受控与真实模型检查

007 的 SSE 检查仍用本地 HTTP 模型替身，不新增付费调用。`ResearchAgentIntegrationTest` 验证初始认证／异步分派、重复订阅只有一次执行、停止前后状态、已知／未知用量、取消归属、工具轮次和停止前绑定；继续复用真实 JDBC catalog 守卫与精确清理。前端 `agent-stream.spec.ts` 检查 UTF-8 分块、CRLF／多行 data、心跳、序号／对象归属、截断、事件大小、权限错误及 AbortSignal；`research-agent-panel.spec.ts` 检查进度、停止后的草稿／焦点、换周期迟到事件及停止恢复。Chrome 的 `research-agent-smoke.mjs` 同时验证早于正文的进度、停止阻止下一轮工具／模型、刷新停止状态及旧恢复／报告行为，沿现有 `scripts/run-agent-report-probe.ps1` 的拥有进程与数据清理入口。

本地停止为协作式：等待当前 HTTP 结束再结算，测试不能只断开浏览器就声称已停止供应商调用；已发出但无用量仍占原预留。页面刷新只恢复 pending 编号与服务端状态，不把会话内存草稿当成已持久化。初轮 Chrome 若只因草稿刷新期望写错失败，保留失败证据并修正测试边界；正常 SSE 断开应由 AsyncRequestNotUsableException 专用处理收尾，不再尝试往流写 JSON 错误。实际结果、次数和证据只见 [任务第 29 节](../specs/004-research-agent/tasks.md#29-sse-进度最终结果与主动停止2026-10-18)。

助手后端定向测试覆盖范围／撤回、固定输出、工具配对与次数、个人记录／幂等／费用。ResearchExperimentBudgetTest 使用临时 JSONL，核对原累计导入、文件锁及未知用量保留，不读取实际 Key、不修改真实实验账本。报告数量从本次完整 Maven 命令取，不把未运行的外部探测算通过。

`frontend/e2e/research-agent-smoke.mjs` 配合回环 `agent-model-fixture.mjs` 使用真实 test HTTP／MySQL 和 Chrome，验证页面及原生工具循环；paidRequests 为 0。006 增加 Chrome 丢弃成功／失败 POST 回包、提交前阻断和查询期间刷新，对照替身请求数，确认恢复没有新模型请求、未知状态下手动提交复用原编号。默认证据目录为 `target/agent-recovery-browser-20261014/`，可用 AGENT_E2E_OUTPUT 指定独立目录，保留旧阶段证据。仍执行第 4 节双路径探针和 catalog 检查、finally 按本轮 ID 清理，不证明真实模型或真实行情效果；受控断线不等于物理网络故障覆盖全部。

`frontend/e2e/research-agent-real-probe.mjs` 是显式启用的付费检查，只有 `AGENT_REAL_MODEL_PROBE=true`、test／实际 catalog／前端代理确认、官方端点和共享账本探针全部满足才发送。仅新建自造观点、mock 行情和四类问题；临时后端须显式配置 `stock-system.agent.experiment-ledger`，复用原累计 10 元 JSONL／文件锁，不删除账本、不自动付费重试。Key 仅由本地忽略配置提供，不能将其值写入启动参数、结果或日志。该入口的数据外发与预算按实际授权核对，不由环境开关代替授权。每例保存结果和用量，结构成功后再对照原文／快照审查语义；任一失败先停并核对用量，finally 清理自造业务数据，保留真实已发生费用。实际授权、语义结果、视口和清理只见 [005 任务](../specs/004-research-agent/tasks.md#14-ag-code-005工具追问与真实样本核对2026-10-14)。

[财务六题入口](../../scripts/run-agent-financial-probe.ps1) 将公开年报的已核实科目加入检查，使用独立 AGENT_FINANCIAL_MODEL_PROBE 开关；默认 Fixture，Real 另核对本轮授权并共享原实验账本。--dry-run、运行前提、续做及人工审查操作只见 [实验说明第 10 节](agent-experiment.md#10-财务真实模型检查怎样复现)。对应 Node 检查执行 `node --test scripts/agent-quality/financial-cases.test.mjs scripts/agent-quality/financial-probe-guard.test.mjs`，仅用合成输出和子进程，不读取 Key、不连接服务。真实模型结果必须分别记录数字／工具契约与回答语义，失败案例不隐藏；测试库数据清理和进程关闭继续沿第 4／5 节，真实费用不可随清理重置。

001R 的 [报告预检](../../scripts/rag-preflight/preflight.py) 在项目隔离 Python 环境中处理受审 PDF 与真实本地 Embedding，不调用回答模型、不写业务 MySQL。其边界单元检查使用 scripts/rag-preflight/test_preflight.py；完整 retrieve 阶段另验证实际 Qdrant 本地持久化、重复导入、版本／股票／模型过滤和移除后空结果。临时库使用本轮唯一目录和集合，finally 删除本轮片段、查询数量为 0，关闭句柄后核对绝对路径再移除目录；模型缓存保留。题集与候选成绩不等于回答质量，更不证明 Java／网页／服务端 Qdrant 已通过。操作只见 [实验说明第 11 节](agent-experiment.md#11-报告正文检索的本地预检)，成绩只见对应 tasks。

Java 的 ReportRetrievalTest 检查有界词法检索、融合去重、重排、错误身份／股票／年度与关闭状态；ReportRetrievalEvaluationTest 由 REPORT_RAG_LOCAL_PROBE 显式启用，真实本地 ONNX／Qdrant 对同一冻结题集比较四种策略，分别保留命中、无答案、版本及耗时，finally 清理目录／16334。新报告六题及原财务选择／授权边界检查执行 `node --test scripts/agent-quality/financial-cases.test.mjs scripts/agent-quality/financial-probe-guard.test.mjs scripts/agent-quality/report-cases.test.mjs`。报告完整替身／真实入口的前置、数据外发、原累计账本及人工语义审查只维护在 [报告评估操作](agent-experiment.md#检索对照与报告回答评估)；仍执行本文件第 4／5 节的双环境探针、catalog、finally 清零与精确进程清理。

财务正文数值保护由 ResearchFinancialAnswerNumbersTest 与 ResearchFinancialQuestionTest 检查，包含真实金额错误、百分比漏单位和共同引用中的同值数字。研究助手受控浏览器另用 invalid-financial-number／invalid-financial-percent 替身返回合法引用及错误金额／缺百分号，确认 INVALID_RESULT、旧答案保留、错误不入消息、不额外派发、费用已知；该项不占真实模型预算。人工／Codex 原文审查必须同时看正文和引用快照，SUCCEEDED 或工具金额正确不能自动判为语义通过，真实失败样本见 [BUG-012](bugs/BUG-012-financial-answer-unit-conversion.md)。

ResearchQuestionModelTest 走实际框架 HTTP 序列化，覆盖工具可选轮次直接回答与原生 tool_calls：两者均须带 JSON 输出约束，后者由业务层执行。ResearchFinancialQuestionTest 的拒绝检查核对 JSON／引用校验的安全阶段、已知用量和无重试；Chrome 的错误财务段落进一步核对 QUESTION_REPLY_FINANCIAL 落库。不得为定位格式问题把原始响应或解析异常消息加入普通日志；历史没有保存的响应不能靠新诊断反推其确切失败原因。

纯报告空查询的 Java 覆盖说明检查包括 NO_REPORT／NO_MATCH 去重、模型常识句不进入最终回复、混合财务材料不被替换，以及有工具参数错误时不冒充成功空检索；报告范围与真实性仍从实际 Service 结果核对，不由用户或模型声明的“没有资料”触发。

ResearchReportAnswerSupportTest 检查报告事实段落交付完整受审原文、计划限定保留、去重及混引拒绝。ResearchReportAnswerReplayTest 只有显式指定 REPORT_ANSWER_REPLAY 为 target 内已有实验 results.json 时执行，不连模型或数据库，另存输入哈希、原始模型版本和新流程版本。回放后仍用原题程序契约与原文清单核对，不把零新增请求的回放写成新版本真实模型复测；实现取舍见 [BUG-013](bugs/BUG-013-report-scope-generalization.md)。

### 2.6 本机 MCP 协议与实际业务链路

MCP 独立入口没有新增前端页面。`ResearchMcpIntegrationTest` 启动随机回环端口 test 后端，在实际 JDBC catalog 与 `/api/test/environment` 核对后创建本轮唯一账号、空间、周期及自造观点。官方 Java MCP Client 启动真实 stdio JVM，验证 initialize／tools/list／tools/call、中文、双账号、当前权限／凭据失效、撤回、范围上限，以及受控源端错误／超时。没有 mock MCP 消息或真实业务 Service，也没有调用回答模型；源故障两例明确为本地 HTTP 故障替身。

默认测试从当前编译 classpath 启动；验证交付入口时先打包，再用 `-Dstock.mcp.packaged-jar=target/stock-system-0.0.1-SNAPSHOT.jar` 跑相同定向测试，实际由 PropertiesLauncher 启动同一个命令。调用程序必须提供自己拥有的账号／对象绑定，不能拿真实用户资料替代自造数据。关闭 SDK 客户端后核对新子 PID、父进程、可执行路径、创建时间和已退出状态；Windows 不保证提供 ProcessHandle.info.arguments，不能仅因该字段缺失而批量杀 Java。故障服务也用随机回环端口并 finally 关闭。测试不改全局客户端配置，进程和数据清理继续执行第 4／5 节。

`ResearchMcpSourceClientTest` 在无数据库的本地 HTTP 层检查参数／目标／Token 头、重定向拒绝、全响应超时、128 KiB 输入边界、输出字段与并发上限；编码回归用自造中文对照 UTF-8 数据被 GBK 解码时的损坏和协议 Unicode 转义后的无损读取。最终数量、协商版本与证据只维护在 [任务第 30 节](../specs/004-research-agent/tasks.md#30-本机只读-mcp-服务与真实客户端2026-10-18)。

## 3. 前端测试

- 类型检查：`npm.cmd run type-check`。
- 静态检查：自动修复使用 `npm.cmd run lint`，只读验收使用 `npm.cmd run lint:check`。
- 单元测试：`npm.cmd run test:unit -- --run`。
- 生产构建：`npm.cmd run build-only` 或完整 `npm.cmd run build`。
- `lint:check` 依次运行不带 `--fix` 的 Oxlint 和 ESLint，自动验收优先使用该命令，避免静态检查顺带改写任务外文件。
- 关键全链路使用 `frontend/e2e/auth-smoke.mjs` 和本机真实 Chrome；脚本使用两个隔离浏览器上下文模拟 OWNER 与 MEMBER。
- UI-01 的公共外壳定向验收在 `frontend` 运行 `npm.cmd run test:e2e:ui-shell`；它按本文件第 4 节检查后端、前端代理和实际 MySQL catalog，在 finally 精确清理。`E2E_UI_PHASE=before` 只采旧页面基线，默认 `after` 验证创建／加入写后刷新失败、切换及路由复用、归档入口、个人资料、跨账号延迟响应、键盘与六个子页视口。截图和机器结果默认保存到工作区 `ui-preview/ui-01`，不替代 auth smoke 的完整业务断言。
- 超级管理员闭环使用 `frontend` 目录的 `npm.cmd run test:e2e:admin`：写入前同时核对测试后端、Vite 代理的环境探针和 SQL 客户端实际 catalog，并要求测试库没有既有 `super_admin`；脚本只把本轮随机账号直接提升为测试管理员，随后从真实登录与管理页面验证禁用／恢复、旧 Token 401、空间治理归档／恢复和操作记录，最后精确删除本轮日志、管理员、空间与账号并核对剩余为 0。直接 SQL 提升只属于隔离测试搭建，不是产品公开接口。
- UI-09 治理定向验收在 `frontend` 运行 `npm.cmd run test:e2e:ui-governance`，需要默认 test mock 后端；沿第 4 节检查两条探针及实际 MySQL catalog，拒绝覆盖已有测试管理员。`E2E_UI_PHASE=before` 只在编辑源码前留基线，默认 after 验证真实筛选／分页／详情、中文空状态、禁用恢复／治理归档来源、数据保留／邀请码撤销、保存后 GET 失败只读重试、连点／键盘／局部表格滚动、迟到响应／跨账号及真实 401／FORBIDDEN。随机 23 个账号、22 个空间和关联成员／邀请／治理日志在 finally 按本轮 ID 精确清理并核对 0；只有测试管理员关系与批量分页样本以 SQL 搭建，其他状态变更调用真实 API。截图与 JSON 默认保存工作区 `ui-preview/ui-09`，支持 `E2E_UI_OUTPUT`。中文查询后端回归使用 AdminGovernanceIntegrationTest，实际运行证据见 SPEC-003 tasks；此脚本不替代完整业务 auth smoke。
- UI-02 的共享自选定向验收在 `frontend` 运行 `npm.cmd run test:e2e:ui-stocks`，使用默认 test mock 后端；写入前检查后端、前端代理及实际 MySQL catalog。`E2E_UI_PHASE=before` 取得旧页面／参考对照并记录参数复用现象，默认 `after` 验证摘要、搜索恢复及迟到结果、关注写后刷新失败只读重试、研究／时间线／历史入口、单图表及迟到行情、空／错误／归档状态、键盘和视口。截图与结果保存在工作区 `ui-preview/ui-02`，所有本轮数据在 finally 精确清理。
- 修改股票目录导入、搜索别名、北交所识别、“目录存在但报价不可用”降级或未关注行情页后，使用 `frontend` 目录的 `npm.cmd run test:e2e:directory` 手动运行 `frontend/e2e/directory-smoke.mjs`。先在独立测试后端的启动参数加入 `--stock-system.market.mode=directory`，仍使用 test profile、测试库及测试端口，不修改默认 properties；Maven 可将该参数与 `--server.port=18082` 一并放入 `-Dspring-boot.run.arguments`。该脚本断言实际响应的 fixture source、null 报价及未知交易状态，同时核对前后端环境探针；mock 供应商的固定样本不能验证数据库别名和无报价目录。它验证简体、繁体、英文、标准代码、BSE、无报价候选进入独立只读行情页以及三类研究业务表零写入，不替代覆盖完整成员与研究流程的 `test:e2e`。其他 mock 浏览器流程须在默认 mock 测试后端下单独执行；临时服务按第 5 节关闭。
- 当前空间 Store 单元测试覆盖列表、创建、加入、邀请读取、重新生成、成员加载、移除、转让、归档恢复、退出状态清理和快速切换时旧响应不得覆盖新空间；股票 Store 同样覆盖快速搜索竞态。访问错误测试分别验证 401 清除账号会话、成员关系失效清除当前空间；认证路由守卫另行验证 `/api/auth/me` 网络失败保留会话与目标路由、401 清除会话并跳转登录页。后续继续补齐更多页面组件测试。
- 浏览器闭环连接真实后端和 `stock_system_test`，已覆盖完整成员生命周期、所有权转让、归档、只读重入、恢复和退出登录本地状态清理。
- `application-dev.properties` 与 `application-test.properties` 都位于主运行时资源目录，不再依赖 `src/test/resources` 是否进入 `spring-boot:run` 类路径。日常开发和浏览器测试分别使用 `scripts/start-dev.cmd`、`scripts/start-test.cmd`，不用来回修改配置文件。
- 写入前的后端启动检查和浏览器环境检查统一执行第 4 节。

- UI-03 的行情／初始观点定向验收在 `frontend` 运行 `npm.cmd run test:e2e:ui-market`，需要默认 test mock 后端。脚本在写入前检查前后端探针与实际 MySQL catalog；实施前可用 `E2E_UI_PHASE=before` 留旧页面对照，默认 `after` 验证九种周期、当天／历史分时、历史读取失败及分页合并、body 提示框、路由参数复用、表单校验／草稿／提交禁用、保存后 GET 失败只读重试、迟到 POST、双成员已有周期、共享图表与归档。四种宽度还读取真实 ECharts 坐标边距，防止宽屏恢复后仍使用手机配置。输出默认在工作区 `ui-preview/ui-03`，可由 `E2E_UI_OUTPUT` 更改；所有本轮账号／空间／研究数据及本轮新建的无引用 mock 股票在 finally 精确清理。目录无报价场景仍单独使用上文的 directory 模式脚本，不能以 mock 代替。

- UI-04 的研究详情定向验收在 `frontend` 运行 `npm.cmd run test:e2e:ui-research`，连接默认 test mock 后端，写入前检查两条环境探针和实际 MySQL catalog。`E2E_UI_PHASE=before` 留原页及参考截图，默认 after 验证 47 条观点跨三批／两周期、服务端成员／倾向／类型筛选、分页失败重试与去重、独立行情及价差边界、表单校验和禁用、写后 GET 失败只读重试、迟到 POST／GET／时间线、双成员周期变化与草稿确认、结束／重开、撤销正文隐藏和五分钟窗口、归档只读与锚点刷新。四种宽度检查溢出和真实图表坐标。默认输出 `ui-preview/ui-04`，支持 `E2E_UI_OUTPUT`；所有本轮 fixture 和新增无引用 mock 股票在 finally 精确清理并核对 0。mock 或受控报价响应只验证 UI 边界，不代替 directory／真实供应商准入验收。

- UI-05 概览定向脚本为 `npm.cmd run test:e2e:ui-overview`，在 frontend 运行并连接默认 test mock 后端；写入前检查前后端 test 探针及实际 MySQL catalog。`E2E_UI_PHASE=before` 记录原 OWNER／MEMBER／空／归档页面及参考，默认 after 验证真实关注数、服务端 20 条上限、七类事件与跳转、撤销隐藏、邀请当前／历史部分失败、复制成功／拒绝、生成／停用后 GET 重试、失败／并发变化、迟到生成／旧概览错误、重新进入后的角色变化和归档。输出默认 `ui-preview/ui-05`，支持 `E2E_UI_OUTPUT`；全部随机账号、空间／邀请／成员／研究数据及本轮新增无引用 mock 股票在 finally 精确清理为 0。

UI-06 的成员／设置定向验收在 `frontend` 运行 `npm.cmd run test:e2e:ui-management`，沿第 4 节在写入前检查两条 test 探针和实际 catalog。`E2E_UI_PHASE=before` 留编辑前正常与确认区基线，默认 `after` 验证真实成员生命周期、保存后 GET 失败只读重试、名称校验／同步／草稿、归档来源和六类迟到写入；截图与 JSON 默认在工作区 `ui-preview/ui-06`。治理归档分支只对本轮空间以 SQL 构造来源，并调用真实 restore API 检查 403，不替代 admin smoke；所有本轮账号、空间及关联记录 finally 精确清理。

UI-07 的公开首页验收在 `frontend` 运行 `npm.cmd run test:e2e:ui-landing`，需要 test mock 后端，默认前后端为 18083／18082，可用 `FRONTEND_URL`／`BACKEND_URL` 指定。`E2E_UI_PHASE=before` 只采编辑前页面；默认 `after` 检查本地演示零 API 请求、真实匿名／普通／管理员入口、过期会话、锚点与历史位置、键盘、窄屏及动效偏好。截图与 JSON 默认在工作区 `ui-preview/ui-07`。写入前沿第 4 节核对两条探针及实际 SQL catalog；管理员仅以本轮随机测试账号构造，既有 super_admin 非空时拒绝搭建。finally 删除本轮管理员、账号、空间及关联数据并逐表核对 0，不替代 admin smoke 的治理写入闭环。

UI-08 的认证／资料验收在 `frontend` 运行 `npm.cmd run test:e2e:ui-account`，默认 test mock 18083／18082，也可用 FRONTEND_URL／BACKEND_URL 指定。`E2E_UI_PHASE=before` 只采编辑前 15 个视口，默认 after 检查真实校验／重复账号／错误凭据、提交锁定、回跳、角色返回、昵称同步与草稿、真实改密及另一个浏览器旧凭证，并暂停已由后端处理的响应，验证离页／换账号后的副作用归属。截图与 JSON 在工作区 ui-preview/ui-08；正常与减少动态偏好、键盘和 320px 表单均检查。沿第 4 节先验证两条探针与实际 catalog，只为本轮随机账号构造管理员关系，既有 super_admin 非空时拒绝搭建；finally 精确清理本轮账号、空间、管理员及关联记录，逐表剩余为 0。原 auth smoke 保留完整业务断言，仅将认证输入定位改为 label；本脚本不替代治理写入或供应商验收。

UI-10 全站检查在 `frontend` 运行 `npm.cmd run test:e2e:ui-final`，默认连接 test mock 18083／18082。只有编辑前才用 E2E_UI_PHASE=before 保存旧正式页面和旧预览；默认 after 核对十四个正式页、四宽度、共享主题、主要内容语义、标题／图标、未知地址返回、换空间／账号、归档、研究刷新及后台懒加载样式。沿第 4 节检查实际 catalog 与两条探针；随机三账号、两空间、管理员及研究关联数据 finally 精确清理。生产构建可本地 preview 到独立端口，再用 FRONTEND_URL 指向它，E2E_UI_OUTPUT 单独存 production，避免覆盖开发证据；本地预览不属于部署。

九块专项与原 auth／admin 串行回归使用 `npm.cmd run test:e2e:ui-regression`，每个原脚本继续独立检查与清理。输出默认工作区 ui-preview/ui-10/regression-*，总结果在 regression-verification.json，失败即停，不把未运行项算通过。目录模式仍另启 test directory 后端并运行原 test:e2e:directory，不能并入 mock 套件假称已验证真实目录；切换与最终清理按第 5 节。实际 UI-10 结果只见 SPEC-003 tasks。

UI 交互补验使用 `npm.cmd run test:e2e:ui-interactions`：正常动态下分别采默认／悬浮／实际按住的状态，并检查十四页四宽度悬浮边界、选中对比度、真实请求锁定、键盘、实际触摸与取消、reduce 即时反馈及边缘点击。`E2E_UI_PHASE=before` 只能在编辑前留基线；默认 after，输出工作区 ui-preview/ui-interactions，支持 E2E_UI_OUTPUT／FRONTEND_URL／BACKEND_URL。生产构建可指向独立 preview，另存 production。沿第 4 节核对两条探针与实际 catalog，拒绝覆盖已有管理员；随机三账号、两空间和研究／成员／邀请／管理员关系 finally 精确清理。原业务专项的断言和清理仍保留，实际证据只见 SPEC-003 tasks 的交互补验。

## 4. 测试数据清理

数据库职责固定如下：

| 场景                             | 数据库              | 数据处理                                |
| -------------------------------- | ------------------- | --------------------------------------- |
| 用户正常启动项目并手工操作       | `stock_system`      | 作为开发数据保留，自动化测试不得清理    |
| 无数据库单元测试                 | 不连接数据库        | 不产生持久化数据                        |
| 后端集成测试、事务测试、并发测试 | `stock_system_test` | 优先事务回滚；不能回滚时按本轮标识清理  |
| Codex 驱动的真实浏览器测试       | `stock_system_test` | 使用本轮唯一标识，在 `finally` 精确清理 |

所有自动化测试一律禁止写入开发数据库 `stock_system`，不存在“为了方便临时写入后再清理”的例外。用户明确要求在开发库创建演示数据属于单独的数据操作任务，不得包装成自动化测试，按用户已明确授权的范围执行，范围不清时才补问。

写数据库的自动化测试必须执行以下保护：

1. 启动前确认目标数据库精确为 `stock_system_test`；
2. 后端集成测试从 JDBC 实际连接再次核对 catalog，不能只相信 `test` profile；`TestDatabaseSafetyGuard` 在 test 后端启动时执行同样的检查，不符合则拒绝启动；
3. 浏览器测试在产生业务写入前，必须分别通过测试后端地址和测试前端代理访问仅存在于 test profile 的 `/api/test/environment`，两条路径都确认实际连接 `stock_system_test`；这同时防止后端地址正确、但 Vite 代理误连开发服务。启动日志只作为人工可见证据，不再是唯一保护；
4. 只记录和删除本次运行唯一标识创建的数据；
5. 在 `finally` 中执行清理；
6. 清理后查询相关表并确认本轮标识剩余数量为 `0`；
7. 最终回复报告创建范围、删除范围和核对结果。

并发与浏览器测试不能共享一个自动回滚事务时，所有账号和空间都必须带本次运行唯一标识，并从邀请、成员、空间到账号按引用顺序精确清理；每张相关表都要核对该标识剩余数量为 `0`。

不得删除无法确认归属的用户、空间、股票、观点或其他业务数据。

2026-09-04 曾发现一条浏览器测试账号和空间残留在开发库。根因、修复与验证见 [BUG-003：浏览器测试写入开发库](bugs/BUG-003-browser-test-database-boundary.md)。

## 5. 临时进程清理

1. 启动前记录命令、PID和端口；
2. 只关闭 Codex 自己启动且身份已确认的进程；
3. 不批量结束 Java、Node 或 Chrome；
4. 关闭后验证端口释放；
5. 复用用户已有服务时不得停止，并在结果中说明。
6. Codex 为自动化测试临时启动 test profile 或使用 `scripts/start-test.cmd` 后，任务结束必须关闭自己启动的测试前后端，确认测试端口释放，并核对项目默认仍为 dev profile、开发库仍为 `stock_system`；不得把切回日常开发状态的操作留给用户。用户自己启动的开发进程保持原状，不因测试而停止或替换。

## 6. 完成报告

执行了测试或进程操作后，按实际触发项说明；未发生的数据或进程操作可合并一句：

- 运行了哪些检查；
- 每项检查结果；
- 哪些检查未运行及原因；
- 是否创建测试数据以及清理核对；
- 是否启动临时进程以及关闭和端口释放；
- 涉及浏览器验收时，报告视口、流程和已知边界。
