# ADR-002：用官方 Longbridge CLI sidecar 接入只读行情

状态：已确认；报价、九种周期 K 线、历史补载、分时及交易日边界均已完成真实协议与页面验收

日期：2026-09-10

## 背景

项目需要在不把供应商凭据交给浏览器的前提下取得真实报价。Longbridge 官方 Java SDK 的浏览器 OAuth 流程在本机无法完成；升级到 `4.5.0` 后，Java API 仍没有从 CLI 已取得的 Token 创建 `OAuth` 或 `Config` 的公开入口。与此同时，官方 CLI `v0.28.5` 已通过 Device Authorization Flow 完成授权，`longbridge quote` 和 `longbridge serve` 都实际返回了 `600519.SH` 报价。

CLI 的 `serve` 命令是稳定的换行分隔 JSON-RPC 2.0 接口：进程启动后复用一次认证和行情连接，多个请求通过 ID 对应，响应允许乱序。它因此可以成为 Java SDK OAuth 修复前的供应商传输适配层。

## 决定

当前基础报价、按需 K 线和分时链路采用：

```text
Vue
  → 本项目 Spring Boot API
  → MarketDataProvider / MarketCandlestickProvider / MarketIntradayProvider
  → LongbridgeJsonRpcClient
  → 一个由 Spring Boot 拥有的 `longbridge serve` 子进程
  → Longbridge OpenAPI
```

- 开发环境默认 `longbridge` 模式；测试环境继续固定 `mock`，需要无外部报价时可显式切回 `directory`。
- sidecar 按需启动，一个 Spring Boot 实例只拥有一个 CLI 子进程；应用关闭时先关闭 stdin，等待退出，必要时只终止自己创建的那个进程。
- Java 端只公开固定行情网关，并把方法硬编码为 `quote.quote`、`quote.candlesticks`、`quote.history_candlesticks_by_date` 与 `quote.intraday`。CLI 虽具备交易和任意 API 方法，本项目当前代码不提供这些调用入口，前端也不能透传 JSON-RPC 方法名。
- 搜索仍以 MySQL 标准股票目录为身份来源；一次搜索得到的候选合并为一次批量报价，再由 provider 合成统一 `StockQuoteSnapshot`。添加研究时仍在数据库事务外重新取得可信快照。
- 请求使用递增 ID 和并发 Map 对应乱序响应；单次超时默认 5 秒。sidecar 异常退出或请求超时时，未完成请求明确失败并终止该 sidecar，下一次报价请求才尝试干净重启，不把旧数据伪装成新行情，也不让超时请求在后台继续占用并发。
- 项目上限固定不超过 5 个并发请求、相邻上游请求至少间隔 100ms，以守住账号当前的每秒 10 次、并发 5 次通用限制。历史 K 线另受每 30 秒 60 次限制，由官方 CLI/SDK 的节流与项目调用方式共同保护；项目仍需监控限流错误，不能把 100ms 间隔误写成覆盖所有方法限制。一次批量请求不是逐股票调用。
- 报价与当天分时默认使用 5 秒进程内缓存，最近／历史 K 线按代码、周期、数量和日期边界默认缓存 1 分钟。它们不是长期行情库，也不替代后续 MySQL 历史事实或多实例、动态行情场景中的 Redis。
- OAuth Token 继续由官方 CLI 管理。项目不读取、复制、解析或记录 `~/.longbridge/openapi/tokens/<client_id>`，浏览器响应、Git 和构建产物都不能包含供应商凭据。

## 备选方案

### Java SDK 直接 OAuth

这是长期更纯粹的 Java 方案，依赖更少、部署单元更简单。但当前 SDK 不能通过公开 API 复用 CLI Token，浏览器授权链路又未跑通，因此暂不作为本阶段阻塞项。将来 SDK 提供受支持的 Token 导入或 OAuth 流程修复后，可在 `LongbridgeQuoteGateway` 后替换传输实现，业务 Service、Controller 和前端契约无需改写。

### 每次报价执行一次 `longbridge quote`

不采用。每次都创建进程、加载认证并建立连接，会增加启动和连接开销，也不利于批量、并发控制与统一关闭。`serve` 的目的正是把这些固定成本放到一个长生命周期进程中。

### 项目自行兑换并保存 Token

不采用。它会复制 CLI 已完成的认证职责，增加刷新、权限、泄漏和兼容性风险；读取 CLI 私有缓存格式也不是受支持集成接口。

### 前端直接调用 Longbridge 或 MCP

不采用。浏览器会接触供应商凭据并绕过项目的登录、空间权限、标准股票身份、事务和数据来源规则。MCP 适合 AI 工具会话，不替代本项目面向页面的业务 API。

## 代价与失败边界

- 运行 Spring Boot 的机器必须额外安装兼容版本的 Longbridge CLI；Java JAR 不再是唯一运行依赖。
- CLI 授权属于运行账号，而不是项目目录。换电脑、换系统用户、容器重建、Token 过期或授权被撤销后，需要在对应服务账号下重新授权。
- CLI 日志目录和 Token 目录必须对服务账号可读写；只给项目目录权限不足。本次受限测试环境正是因为不能写用户日志目录而导致 sidecar 启动后退出。
- 一个账号只能保持一个行情长连接。当前不能拿同一账号直接水平启动多个 Spring Boot 副本；扩容前必须设计唯一行情网关或其他符合供应商条款的账号与连接方案。
- sidecar 不监听 HTTP 端口，但仍是独立 OS 进程，需要纳入启动、关闭、健康检查和故障监控。当前具备进程退出感知和按需重启，Actuator readiness 与生产告警尚未实现。
- 进程内短缓存只在单个 Java 进程内有效。多副本、热点轮询、WebSocket 订阅或跨请求复用明显增长时，再按已定义的一致性与降级边界引入 Redis。

具体本地操作见 [开发说明](../../engineering/development.md#9-longbridge-真实报价)，上线前检查见 [部署说明](../../engineering/deployment.md#3-longbridge-sidecar-部署前置条件)，验证规则见 [测试说明](../../engineering/testing.md#21-longbridge-sidecar-测试)。

## 重新评估条件

- Longbridge Java SDK 提供受支持、可测试的 OAuth/Token 接入方式；
- 需要多实例独立扩缩容，单账号单连接成为真实瓶颈；
- 基础轮询升级为大规模订阅、Redis 缓存或独立行情服务；
- CLI JSON-RPC 契约、权限或部署支持发生不兼容变化。
