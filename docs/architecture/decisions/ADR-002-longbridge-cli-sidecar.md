# ADR-002：Longbridge 行情传输的 SDK 与 CLI 选择

状态：已确认；阶段验证及当前缺口见 [SPEC-002 tasks](../../specs/002-stock-research/tasks.md)，本记录只维护传输选型。

日期：2026-09-10；最后核对：2026-10-12

## 背景

项目需要在不把供应商凭据交给浏览器的前提下取得真实报价。历史背景：2026-09 的 Longbridge 官方 Java SDK 浏览器 OAuth 在本机无法完成；升级到 `4.5.0` 后，Java API 仍没有从 CLI 已取得的 Token 创建 `OAuth` 或 `Config` 的公开入口。与此同时，官方 CLI `v0.28.5` 已通过 Device Authorization Flow 完成授权，`longbridge quote` 和 `longbridge serve` 都实际返回了 `600519.SH` 报价。

CLI 的 `serve` 命令是稳定的换行分隔 JSON-RPC 2.0 接口：进程启动后复用一次认证和行情连接，多个请求通过 ID 对应，响应允许乱序。它作为早期 SDK OAuth 未跑通时的供应商传输适配层，现继续供生产使用。

## 决定

用户确认本地使用新的长桥账号并切换官方 Java SDK OAuth，生产继续保留 CLI。`dev` 选择 sdk，`prod` 明确选择 cli，test 保持 mock。授权、探测、网页与真实到期刷新分别验收，当前证据和缺口只见 [CODE-206I](../../specs/002-stock-research/tasks.md) 与 [状态](../../project/status.md)，不在选型记录另存进度。当前链路如下：

```text
Vue → Spring Boot API → 三类 Market Provider
    → CachingLongbridgeMarketGateway → LongbridgeUpstreamGateway
        ├─ dev：LongbridgeSdkClient → 一个 QuoteContext → Longbridge OpenAPI
        └─ prod：LongbridgeJsonRpcClient → 一个 longbridge serve → Longbridge OpenAPI
```

SDK 采用官方 `OAuthBuilder`／`Config.fromOAuth`；独立本地准备入口负责浏览器同意，后端不弹出登录、不读取或手写 Token 缓存。SDK 单 JVM 短缓存容量／TTL 沿用原客户端；初版串行发送上游请求并间隔至少 100ms，锁内统一持有和释放会话，排队和单次等待分别受 timeout 约束。配额错误保留代码，分钟范围只保留允许的日期；超时或连接／授权失败关闭会话，下一请求再建连。多用户吞吐、真实自动刷新与供应商行为尚待验收，不能把一条 Java SDK 连接解释成绕过单账号限制。

两种传输共用 `LongbridgeUpstreamException`，业务 Service 和前端契约保持原职责；旧的本地粘贴授权码、手工兑换和临时写 SDK 缓存代码已移除。云端需要的 CLI 实现及 Dockerfile 保留，不卸载用户已有 CLI、不删除旧授权、不上传或重建云主机。准备步骤和完整代码定位只见[开发说明](../../engineering/development.md#92-新账号与官方-oauth-准备)。

下述是仍适用于生产 CLI 的链路与约束：

```text
Vue
  → 本项目 Spring Boot API
  → MarketDataProvider / MarketCandlestickProvider / MarketIntradayProvider
  → CachingLongbridgeMarketGateway（Redis 命中直接返回；未命中／故障再向下）
  → LongbridgeJsonRpcClient
  → 一个由 Spring Boot 拥有的 `longbridge serve` 子进程
  → Longbridge OpenAPI
```

- 行情模式 `longbridge` 表示供应商来源，传输再由环境选择 sdk 或 cli；测试环境继续固定 `mock`，需要无外部报价时可显式切回 `directory`。
- sidecar 按需启动，一个 Spring Boot 实例只拥有一个 CLI 子进程；应用关闭时先关闭 stdin，等待退出，必要时只终止自己创建的那个进程。
- Java 端只公开固定行情网关，并把方法硬编码为 `quote.quote`、`quote.candlesticks`、`quote.history_candlesticks_by_date`、`quote.intraday` 与 `quote.trading_days`。CLI 虽具备交易和任意 API 方法，本项目当前代码不提供这些调用入口，前端也不能透传 JSON-RPC 方法名。
- 搜索仍以 MySQL 标准股票目录为身份来源；一次搜索得到的候选合并为一次批量报价，再由 provider 合成统一 `StockQuoteSnapshot`。添加研究时仍在数据库事务外重新取得可信快照。
- 请求使用递增 ID 和并发 Map 对应乱序响应；单次超时默认 5 秒。sidecar 异常退出或请求超时时，未完成请求明确失败并终止该 sidecar，下一次报价请求才尝试干净重启，不把旧数据伪装成新行情，也不让超时请求在后台继续占用并发。
- 项目上限固定不超过 5 个并发请求、相邻上游请求至少间隔 100ms，以守住账号当前的每秒 10 次、并发 5 次通用限制。历史 K 线另受每 30 秒 60 次限制，由官方 CLI/SDK 的节流与项目调用方式共同保护；项目仍需监控限流错误，不能把 100ms 间隔误写成覆盖所有方法限制。一次批量请求不是逐股票调用。
- 保留原有进程内短缓存作为本进程复用和 Redis 故障时的退路，外层通过网关装饰器共享可重建行情。选择 Redis 不要求同时引入动态推送，也不把缓存升级成长期行情库；具体机制与当前差距见 [SPEC-002 缓存设计](../../specs/002-stock-research/design.md#46-redis-共享行情缓存与访问保护)。
- OAuth Token 继续由官方 CLI 管理。项目不读取、复制、解析或记录 `~/.longbridge/openapi/tokens/<client_id>`，浏览器响应、Git 和构建产物都不能包含供应商凭据。

## 备选方案

### Java SDK 直接 OAuth

2026-09 因浏览器授权失败而暂缓，2026-10-02 接入本地开发传输，后续已完成官方授权与探测；当前开发使用此方案，验证证据沿 CODE-206I。SDK 自行取得和管理 OAuth Token，不需要导入 CLI Token。生产迁移须单独核验真实行情、授权刷新、故障和部署产物，不能只因接入代码存在就删去生产 CLI。

### 每次报价执行一次 `longbridge quote`

不采用。每次都创建进程、加载认证并建立连接，会增加启动和连接开销，也不利于批量、并发控制与统一关闭。`serve` 的目的正是把这些固定成本放到一个长生命周期进程中。

### 项目自行兑换并保存 Token

不采用。它会复制 CLI 已完成的认证职责，增加刷新、权限、泄漏和兼容性风险；读取 CLI 私有缓存格式也不是受支持集成接口。

### 前端直接调用 Longbridge 或 MCP

不采用。浏览器会接触供应商凭据并绕过项目的登录、空间权限、标准股票身份、事务和数据来源规则。MCP 适合 AI 工具会话，不替代本项目面向页面的业务 API。

## 代价与失败边界

- 使用 CLI 传输的生产机器必须额外安装兼容版本的 Longbridge CLI；该路径的 Java JAR 不是唯一运行依赖，dev SDK 不要求安装 CLI。
- CLI 授权属于运行账号，而不是项目目录。换电脑、换系统用户、容器重建或授权被撤销后，须核对该服务账号的授权；普通 Token 到期由官方 CLI 刷新，不能笼统要求每次到期重新手工授权。
- CLI 日志目录和 Token 目录必须对服务账号可读写；只给项目目录权限不足。历史受限测试曾因不能写用户日志目录导致 sidecar 启动后退出。
- 一个账号只能保持一个行情长连接。当前不能拿同一账号直接水平启动多个 Spring Boot 副本；扩容前必须设计唯一行情网关或其他符合供应商条款的账号与连接方案。
- sidecar 不监听 HTTP 端口，但仍是独立 OS 进程，需要纳入启动、关闭、健康检查和故障监控。当前具备进程退出感知和按需重启，Actuator readiness 与生产告警尚未实现。
- 进程内短缓存只在单个 Java 进程内有效。Redis 已实现跨网关实例共享，但当前同 Key 锁等待超时仍可能重复回源，并不提供全局限流；实现与验证边界见上述缓存设计和 [CODE-206E 证据](../../specs/002-stock-research/tasks.md)。这不代表当前已经达到多实例容量，也不解除单账号单连接约束。WebSocket/SSE 等动态推送仍在出现相应需求后另行评估。

具体本地操作见 [开发说明](../../engineering/development.md#9-longbridge-真实报价)，上线前检查见 [部署说明](../../engineering/deployment.md#3-longbridge-sidecar-部署前置条件)，验证规则见 [测试说明](../../engineering/testing.md#21-longbridge-行情传输测试)。

## 重新评估条件

- 本地官方 SDK OAuth 已验证后，评估云端统一 SDK 的授权与部署验收；
- 需要多实例独立扩缩容，单账号单连接成为真实瓶颈；
- 基础轮询升级为大规模订阅，或共享缓存仍不足以支撑访问而需要独立行情服务；
- CLI JSON-RPC 契约、权限或部署支持发生不兼容变化。
