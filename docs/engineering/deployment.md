# 部署与上线检查

状态：生产部署尚未实施；本文记录上线前必须完成的工作，不把计划写成已部署事实。

更新时间：2026-09-10

## 1. 文档职责

本文维护生产机器、服务账号、外部依赖、密钥、启动、回滚和上线验收。开发电脑怎样运行见 [本地开发](development.md)，测试环境和清理见 [测试规则](testing.md)，Longbridge 方案取舍见 [ADR-002](../architecture/decisions/ADR-002-longbridge-cli-sidecar.md)。

## 2. 计划部署结构

```text
Browser
  → Nginx（静态前端与 /api 反向代理）
  → Spring Boot
      → MySQL
      → 自己管理的 longbridge serve 子进程
          → Longbridge OpenAPI
```

Nginx、容器、Actuator、生产告警和自动发布当前均未实施。上线时应先选择宿主机服务或容器方案，再补充可执行配置和演练证据。

## 3. Longbridge sidecar 部署前置条件

### 3.1 安装和运行账号

1. 在实际运行 Spring Boot 的机器安装已验证兼容的 Longbridge CLI，并用 `longbridge --version` 核对版本。当前本地验证版本为 `v0.28.5`，升级必须重新跑真实集成测试。
2. 为应用使用固定的非管理员服务账号。CLI 授权和缓存属于这个 OS 账号；不能在管理员或开发者账号登录后，假设服务账号会自动继承。
3. 在同一服务账号下完成 `longbridge auth login`，再用 `longbridge quote 600519.SH` 做最小只读验证。授权通常复用，不是每次报价都重新登录；Token 过期、撤销、账号或机器变化时才需要重新授权。
4. 保证该账号对自身 Longbridge Token 与日志目录拥有最小必要的读写权限。不能授予其他用户广泛读取权限，也不能因为日志目录不可写而只检查项目目录。

### 3.2 凭据和持久化

- Token 由 CLI 保存在服务账号的 `~/.longbridge/openapi/tokens/<client_id>`；项目代码不读取其内容。
- Token 不能写入 Git、镜像层、前端环境变量、启动参数、普通日志或浏览器响应。备份和迁移必须按生产密钥处理，并优先采用供应商支持的重新授权流程。
- 如果使用容器，镜像可以安装 CLI，但不能烘焙 Token。服务账号 home/Token 必须采用受保护的持久卷或部署平台密钥机制；具体挂载方式需在选定平台后验证权限、刷新和容器重建行为。
- 生产机器需配置正确时钟、DNS、TLS 和出站网络；若依赖代理，必须在服务账号及 Java 启动环境中实际验证，不能只以交互式浏览器可访问为依据。

### 3.3 进程和容量

- 当前简写变量 `MARKET_MODE`、`LONGBRIDGE_CLI_PATH` 等只由 `application-dev.properties` 映射，不能直接当成尚未建立的生产 profile 配置。生产实施时必须新增并验收生产配置，或使用 Spring Boot 标准环境变量 `STOCK_SYSTEM_MARKET_MODE=longbridge`、`STOCK_SYSTEM_MARKET_LONGBRIDGE_EXECUTABLE=<绝对路径>` 等直接绑定完整属性；不得以 `dev` profile 冒充生产配置。CLI 路径使用绝对值，避免 systemd、Windows Service 或容器的 PATH 与交互式终端不同。
- Windows 官方脚本当前默认安装到 `%LOCALAPPDATA%\Programs\longbridge\longbridge.exe`；开发配置会以该位置作为 PATH 缺失时的默认值，生产仍必须显式设置绝对路径。部署验收先执行 `longbridge auth status`；网页显示授权成功但这里仍为 `Token not found` 时，说明 Token 没有写入运行 Spring Boot 的同一 OS 账号，不能把网页状态当作 sidecar 已授权。
- Spring Boot 第一次报价时按需启动一个 `longbridge serve`，应用停止时负责关闭它。进程管理器只监督 Spring Boot，不要再独立常驻第二个 `serve`，否则会与应用争用账号长连接。
- Longbridge 当前规则为一个账号一个行情长连接。没有完成集中式行情网关设计前，同一账号只部署一个启用 `longbridge` 模式的应用副本；不能通过简单增加副本实现扩容。
- 行情通用限制按不超过每秒 10 次、并发 5 次执行，历史 K 线另有每 30 秒最多 60 次的接口限制；当前 Java 端另有批量请求、报价／当天分时 5 秒缓存、K 线及历史日期批次 1 分钟缓存。官方 CLI/SDK 仍负责服务端规则下的主动节流，项目也要监控 301606/429，不能只依赖 100ms 通用间隔宣称覆盖所有方法限制。多副本或热点行情需要共享缓存时再引入 Redis，不能把每个副本各自缓存误当作全局限流。
- 当前只开放 `quote.quote`、`quote.candlesticks`、`quote.history_candlesticks_by_date` 与 `quote.intraday` 四个固定只读方法，不配置交易权限。未来任何交易接入都必须另立需求、权限、确认和风控设计，不能因为 CLI 本身具备交易命令就直接暴露。
- 实时报价 pull 的单次代码上限为 500；需要全市场批量读取时分批并纳入调度，不能把 5000 个代码塞进一次请求。订阅仍受账号单连接和同时最多 500 个符号限制，不能把 pull 无月度总量硬上限误解为可同时订阅 5000 只。
- K 线按自然月不同股票代码计额，上线配置前在 [Longbridge Account Center](https://open.longbridge.com/account) 的“我的行情权限”核对实际档位。官方当前没有公开 CLI 用量查询命令，也未承诺账号页提供实时已用计数；若项目以后记录自身不同标的数，必须标为估算且不能代替供应商全局余量。告警必须结合方法解释 `301607`：实时报价表示单次代码数量超限，最近 K 线表示单次请求数据量超限，历史 K 线可表示当月不同股票代码额度超限；不能跨接口套用同一个原因。应用返回 `MARKET_KLINE_LIMIT_EXCEEDED`，应纳入 429 指标和告警。
- 当天分时走 `quote.intraday`；历史交易日分时由 `1m` 历史 K 线还原，因此历史分时也占用同一自然月“不同股票代码”额度。当前实现只在成员打开单只股票分时页、页面可见且后端返回 `updating=true` 时每 30 秒拉取，切换 K 线、收起面板、页面隐藏或返回非更新状态后不继续访问上游；搜索候选和共享列表不预取分时或 K 线。当前 `updating` 仅按时区、工作日与固定时段计算，尚未接入交易所节假日日历，也未核对当天返回点的日期，响应还没有明确交易时段范围；生产前必须补齐。

## 4. 上线验收

按以下顺序留下环境化证据：

1. 服务账号运行 `longbridge --version`、`longbridge check` 和一只允许市场的只读报价成功；
2. Spring Boot 以生产配置启动，第一次行情请求只创建一个子进程，重复请求不会重复登录或重复创建进程；
3. 项目登录用户通过本项目 `/api` 取得代码、价格、供应商时间、来源和真实/模拟标识，前端不直接访问 Longbridge；
4. CLI 不存在、未授权、网络超时、上游错误和 sidecar 异常退出时，搜索保留可信目录但价格为空，添加按 `MARKET_QUOTE_UNAVAILABLE` 失败且不写半成品；
5. 停止 Spring Boot 后核对其子进程已退出，没有遗留 `longbridge serve`；
6. 检查浏览器网络响应、前端生产构建、后端 JAR、日志和 Git 历史均不含 Token 或授权缓存；
7. 重新核对上线时的实时报价限流、单次标的数、历史 K 线月度不同标的配额和市场权限；记录实际 CLI 版本、服务账号、Token 持久化方式、网络/代理、实例数、回滚命令和告警负责人。
8. 用一个已经加入测试空间且本月已查询过的股票读取分钟／日／年 K、左拖补历史、当天分时和一个历史交易日，确认同一个 sidecar、来源时间、不复权标记、停止刷新条件和页面错误状态；再用非交易日验证不会把上一交易日数据错标为所选日期。不得用批量搜索候选做 K 线或分时验收。

Actuator readiness 与生产告警尚未实现，不能在缺少这些证据时把“进程能启动”写成生产可运维完成。

## 5. 回滚和授权失效

- 外部行情故障时将生产环境的完整属性 `stock-system.market.mode`（标准环境变量为 `STOCK_SYSTEM_MARKET_MODE`）改为 `directory` 后重启 Spring Boot：搜索继续使用 MySQL 标准目录，但价格和行情时间为空，添加研究按现有规则失败。`MARKET_MODE` 只是当前 dev profile 的简写映射；不得切到 `mock` 冒充生产行情。
- 若版本升级导致不兼容，回退到已验证 CLI 版本和应用版本，再重新运行第 4 节；不要删除数据库研究历史。
- Token 过期或撤销时，在服务账号下重新执行官方授权；不修改源码、不把 Token 复制到配置文件。若怀疑泄漏，先从 Longbridge 安全设置撤销授权，再清理受影响运行环境并重新授权。
- 当 Java SDK 提供受支持的 OAuth/Token 入口时，可替换 `LongbridgeQuoteGateway` 实现；切换前保留 sidecar 回滚路径并做同一套接口、故障和凭据验收。
