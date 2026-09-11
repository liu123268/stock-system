# 测试与验收规则

更新时间：2026-09-11

## 1. 测试目标与选择

任何测试或临时进程任务都读取本文件。根据风险选择最小但充分的检查：纯文档检查链接与规则；小代码改动检查相关模块；纵向闭环检查后端、前端和关键浏览器流程；大阶段收尾再全量回归，不因每次修改机械执行全套测试。

测试不是只证明正常流程能运行，还要证明参数、权限、事务、数据隔离、失败响应和清理行为符合规则。

PowerShell 用 `npm.cmd`，后端使用系统 `mvn`。

## 2. 后端测试

- 使用 JUnit 5 和 Spring Boot Test。
- Controller/API 集成测试使用 MockMvc。
- 认证链路覆盖注册、登录、错误密码、缺失 Token、非法 Token、当前用户和统一 401。
- 空间创建、列表、详情、非成员访问、邀请状态、成员退出/移除/重入、所有权转让、归档恢复、归档只读和无效操作已经有真实 MySQL 集成测试；后续继续覆盖事务故障回滚和其余并发竞争。
- 使用真实 MySQL 验证 Flyway、生成列、检查约束、外键和行锁；连接保护统一执行第 4 节。
- 当前本机没有 Docker 命令，阶段 1 不依赖 Testcontainers 或 H2；后续具备 Docker 环境且需要 CI 隔离时再评估 Testcontainers。
- 邀请并发测试使用 6 个独立线程与 `CyclicBarrier` 同步起跑，验证 5 人成功、1 人收到 `INVITATION_EXHAUSTED`；所有权并发测试验证旧 OWNER 同时向两个目标转让时只有一个成功。后续继续覆盖并发同名创建和并发重新生成邀请。
- 多步事务回滚通过测试配置中的可控替身制造中间失败，不在生产代码加入测试故障开关。
- 股票研究测试已覆盖 V3 表与生成列唯一约束、模拟搜索、可信行情字段、OWNER/MEMBER 添加、非成员和归档拒绝、重复添加、跨空间隔离，以及观点写入受控失败时前三类写入整体回滚。
- Maven 常用命令：`mvn "-Dmaven.repo.local=D:\Java\stockweb\.m2repo" test`。

### 2.1 Longbridge sidecar 测试

- 普通回归不依赖真实账号：`LongbridgeJsonRpcClientTest` 用内存假进程覆盖报价、最近／历史 K 线、当天分时和交易日历协议、乱序 ID 对应、短缓存、`301607`／分钟最早日期保留、超时和关闭；provider 测试覆盖目录身份合并、K 线转换、历史分时均价重建、配额映射、休市日拦截、当天点日期过滤与不可用降级。分时交易时段响应另由 provider 和前端坐标测试覆盖，不能用固定工作日逻辑替代官方交易日历。
- 真实 CLI 测试默认跳过，只有明确验证本机/部署账号授权时才用系统属性 `stock.longbridge.cli.integration=true` 单独启用。测试不得输出 Token、CLI 私有缓存或完整账号信息。
- `LongbridgeCliIntegrationTest` 只证明 Java 管理器能启动/关闭 sidecar 并取得真实报价；`LongbridgeMarketDataIntegrationTest` 还必须通过 `MySqlIntegrationTest` 核对实际 catalog 为 `stock_system_test`，临时目录记录使用专用来源并在事务后核对剩余为 0。
- `LongbridgeCandlestickRealProbeTest` 由 `STOCK_LONGBRIDGE_KLINE_PROBE=true` 显式启用，只读取已批准的 `600519.SH` 两根日 K；`LongbridgeIntradayRealProbeTest` 由 `STOCK_LONGBRIDGE_INTRADAY_PROBE=true` 启用，读取同一股票当天分时和指定交易日 1 分钟 K。两者都不启动 Spring、不写数据库；历史 K 线会占月度不同标的额度，新增探测代码前必须先说明并优先选择已经计入过的标的。
- 启用真实测试前后都要精确核对 `longbridge` 进程。只关闭本轮 Java 客户端创建的进程，不能批量结束用户已有 CLI；失败、超时和测试结束都必须确认无残留。
- 受限执行环境还必须允许 CLI 读取服务账号授权缓存并写其正常日志目录。若只开放项目目录，sidecar 可能启动后立即退出；这不等于 OAuth 或行情权限失效，应与同账号直接执行 `longbridge quote` 做对照。

### 2.2 单元测试怎样运行与阅读

单元测试把一个类或一小段协作逻辑与外部系统隔离，用固定输入检查输出、异常或交互；它通常快、定位清楚，但不能代替真实 MySQL、Redis、Longbridge 或浏览器验收。这个项目的 `*Test.java` 中，使用假进程、替身或内存对象的测试主要属于单元测试；带 `@SpringBootTest`、真实 JDBC 或显式 Redis 的测试属于集成测试，分类以测试实际连接的资源为准。

在项目根目录运行 `mvn "-Dmaven.repo.local=D:\Java\stockweb\.m2repo" test` 会执行普通后端回归。测试报告在 `target/surefire-reports/`：先看 `tests`、`failures`、`errors`、`skipped`，再按失败测试名回到源码中的 `@Test` 方法。Redis 真实连接、Longbridge 账号和浏览器流程都不是普通单元测试会自动启用的能力，必须按本文件相应开关和环境前置单独运行；“测试通过”要同时说明测试层次和未执行的外部探测。

## 3. 前端测试

- 类型检查：`npm.cmd run type-check`。
- 静态检查：自动修复使用 `npm.cmd run lint`，只读验收使用 `npm.cmd run lint:check`。
- 单元测试：`npm.cmd run test:unit -- --run`。
- 生产构建：`npm.cmd run build-only` 或完整 `npm.cmd run build`。
- `lint:check` 依次运行不带 `--fix` 的 Oxlint 和 ESLint，自动验收优先使用该命令，避免静态检查顺带改写任务外文件。
- 关键全链路使用 `frontend/e2e/auth-smoke.mjs` 和本机真实 Chrome；脚本使用两个隔离浏览器上下文模拟 OWNER 与 MEMBER。
- 当前空间 Store 单元测试覆盖列表、创建、加入、邀请读取、重新生成、成员加载、移除、转让、归档恢复和退出状态清理；访问错误测试分别验证 401 清除账号会话、成员关系失效清除当前空间。后续继续补齐快速切换竞态和更多页面组件测试。
- 浏览器闭环连接真实后端和 `stock_system_test`，已覆盖完整成员生命周期、所有权转让、归档、只读重入、恢复和退出登录本地状态清理。
- `application-dev.properties` 与 `application-test.properties` 都位于主运行时资源目录，不再依赖 `src/test/resources` 是否进入 `spring-boot:run` 类路径。日常开发和浏览器测试分别使用 `scripts/start-dev.cmd`、`scripts/start-test.cmd`，不用来回修改配置文件。
- 写入前的后端启动检查和浏览器环境检查统一执行第 4 节。

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
3. 浏览器测试在产生业务写入前，通过仅存在于 test profile 的 `/api/test/environment` 确认后端实际连接 `stock_system_test`；启动日志只作为人工可见证据，不再是唯一保护；
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
