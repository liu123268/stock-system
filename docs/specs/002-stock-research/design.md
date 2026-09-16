# SPEC-002：股票与初始观点技术设计

状态：股票与初始观点基础闭环、真实行情 pull 已实现；Redis 共享缓存已编码，未完成验收，差距见 tasks
更新时间：2026-09-16

## 1. 文档职责

本文档保存股票与初始观点闭环及后续已进入实施范围的目录、报价、K 线、分时和 Redis 方案。业务规则见 [需求](requirements.md)，实施顺序与验证证据见 [任务清单](tasks.md)。尚未进入的多人观点、永久行情库和重新关注不在这里提前展开。

## 2. 四个核心对象

```text
Stock
  全系统共享的标准股票身份
      ↓
WorkspaceStock
  某个空间与这只股票的长期共享关系，只建立一次
      ↓
ResearchCycle
  一段有开始、有结束的关注过程；同一时刻最多一个活动周期
      ↓
StockOpinion
  某位成员在某段研究周期中的一次观点记录
```

这样拆分是为了同时满足两个看似冲突的要求：同一空间不重复创建同一只股票，但结束关注后又能开启新的研究周期并保留旧历史。

## 3. 首批数据职责

V3 已围绕当前闭环建立四张表；精确字段、检查约束、外键和索引以 [V3 Flyway 迁移](../../../src/main/resources/db/migration/V3__create_stock_research_tables.sql) 为可执行事实：

| 表                | 当前闭环必须表达的事实                                               | 核心约束                                |
| ----------------- | -------------------------------------------------------------------- | --------------------------------------- |
| `stock`           | 标准代码、名称、交易所、币种、上市状态和基础信息来源                 | 标准股票代码唯一                        |
| `workspace_stock` | 空间与股票的长期共享关系、首次添加人和首次添加时间                   | `(workspace_id, stock_id)` 唯一         |
| `research_cycle`  | 所属共享股票、当前关注级别、开始人/时间、开始价格、行情时间和来源    | 同一 `workspace_stock` 最多一个活动周期 |
| `stock_opinion`   | 所属研究周期、作者、类型、倾向、理由、风险、周期、可选价格和创建时间 | INITIAL 观点必须明确属于一个周期        |

活动周期唯一性不能只靠“先查询再插入”。事务先锁定空间，使同一空间的添加请求按顺序完成；发现现有活动周期时直接返回已有标识。`active_workspace_stock_id` 生成列的唯一约束仍负责保护最终数据库状态。

首批只实现 `INITIAL` 观点和活动周期；字段命名要为后续 `UPDATE/REVIEW`、观点撤回与周期结束保留自然扩展位置，但不提前实现对应流程。

## 4. 行情适配边界

业务 Service 已依赖项目自己的 `MarketDataProvider`，不直接依赖某个供应商 DTO：

```text
MarketDataProvider
├─ search(keyword)       返回标准股票候选
└─ getLatestQuote(code)  返回最新可用行情快照
```

模拟模式使用本地 `MockMarketDataProvider`：

- 内置少量 A 股、港股候选和固定行情时间；
- 返回统一的标准代码、交易所、币种、价格、行情时间和 `SIMULATED` 标识；
- 只用于打通业务和页面，不声称外部行情已经接入；
- 后续替换真实提供者时，Controller、空间权限和研究事务不随供应商 DTO 改写。

目录接入阶段曾默认使用 `directory` 模式；当前开发环境已默认使用 `longbridge`，搜索 Service 先查询 `stock`，再由 Longbridge provider 补充真实报价。需要停用外部报价时可显式选择 `directory`，测试环境固定 `mock`，三种模式不能静默回退或混用。

### 4.0 外部行情调用与密钥边界

真实行情供应商只允许作为 Spring Boot 服务端的上游。Vue/React 前端只能调用本项目的 Spring Boot API，不得直接调用供应商，也不得接收、保存或暴露 `APP_SECRET`、`ACCESS_TOKEN`、App Key 等凭据。当前服务端通过官方 CLI sidecar 调用 Longbridge，校验返回的股票身份、价格、行情时间、来源和状态，再结合 MySQL 标准身份与缓存向前端提供受项目权限和数据规则约束的结果；Redis 装饰层已经编码，实际缓存行为和未完成边界见 4.6。WebSocket/SSE 仍是达到动态推送条件后才实施的传输层。

```text
Vue/React ←→ Spring Boot → MySQL（股票身份、权限与研究事实）
                    │
                    ↓
            Redis 共享行情缓存（启用时）
                    │ 未命中／故障降级
                    ↓
            进程内短缓存 / Longbridge 网关
                    │ 官方 CLI `serve` / JSON-RPC
                    ↓
                Longbridge
```

凭据只能通过服务端环境变量、受保护的本机配置或部署平台密钥管理提供，不能进入前端源码、浏览器响应、日志、Git 或构建产物。供应商凭据泄漏可能影响关联账户，因此真实接入的验收必须包含“浏览器和构建产物中不存在供应商密钥”的检查。该边界是全局行情接入规则，不因某个页面需要行情而放宽。

### 4.1 真实数据源候选调查（2026-09-08）

本节保留候选调查依据；目录已完成下节所述实测与入库，表中的行情候选不表示已接入或取得账号权限。费用约束以 [V1 需求](../../product/requirements-v1.md#33-共享自选股与行情) 为准。

推荐先验证公开股票目录，并同时抽样验证最新可用报价与日 K 线；目录同步和数据库搜索优先实施，添加所需的真实价格快照紧随其后，实时推送与完整图表后置。目录验证不一定需要账号或 Token；只有选择需要鉴权的供应商才有该前置条件。

| 候选 | 官方资料核查结果 | 当前判断 |
| --- | --- | --- |
| 交易所公开目录 | [上交所](https://www.sse.com.cn/assortment/stock/list/)、[深交所](https://www.szse.cn/market/stock/company/)、[北交所](https://www.bse.cn/nq/listedcompany.html)、[港交所证券清单](https://www.hkex.com.hk/Services/Trading/Securities/Securities-Lists?sc_lang=en) 提供目录入口 | 优先作为目录来源或核对基准；公开网页/下载文件不等于稳定的正式 API，尚需验证下载、字段、分类、完整性和使用条件 |
| AKShare | [股票文档](https://akshare.akfamily.xyz/data/stock/stock.html) 有 A 股目录 `stock_info_a_code_name`、分交易所目录、A 股 `stock_zh_a_hist` 和港股 `stock_hk_hist` 历史接口；新浪港股 `stock_hk_spot` 明示 15 分钟延迟 | 免费采集工具候选；不是统一的上游供应商。[项目说明](https://akshare.akfamily.xyz/introduction.html) 限定学术研究用途并说明网站变更会引起接口异常，不能视为稳定性或再分发授权承诺 |
| 富途 OpenAPI | [权限与额度](https://openapi.futunn.com/futu-api-doc/intro/authority.html) 有条件免费行情，权限受认证及 OpenD 登录 IP 等影响；基础档历史 K 线额度为最近 7 天 100 只股票 | 若已有满足条件的账号，可验证为统一行情候选；额度不等于权限，不要求为本项目入金或购买行情卡 |
| 长桥 OpenAPI | [定价页](https://open.longbridge.com/zh-CN/pricing) 写港股 LV1 推广免费，但[行情概览](https://open.longbridge.com/zh-CN/docs/quote/overview) 写基础港股为约 15 分钟延迟、实时 LV1 另购 | 官方页面存在不同口径，免费实时权限待账号核查，不能承诺免费港股实时 |
| Tushare / AllTick | [Tushare 权限表](https://tushare.pro/document/1?doc_id=108) 港股目录有积分门槛；[权限说明](https://tushare.pro/document/1?doc_id=290) 区分积分与独立权限。[AllTick 定价](https://alltick.co/zh-CN/pricing) 免费版仅有 10 个演示产品，最低付费档 99 USDT/月 | 不作为零费用 A 股及港股全目录与行情闭环的首选 |

目录、最新报价和历史 K 线应在适配层分别表达能力，Java 业务继续使用本地标准身份。报价与 K 线尽量同源以减少价格口径差异；目录可以独立来自交易所，不为统一供应商牺牲费用与覆盖。即使用同一供应商，也需核对复权方式、币种、成交量单位和时间戳。

下一次实测需检查：沪市主板/科创板、深市主板/创业板、北交所的覆盖；港股全目录与港股通子集的区别；证券类型和双柜台身份；代码前导零及供应商代码映射；最新价与收盘价的业务时间；日线复权与停牌边界。港交所全证券清单不能不经分类就全部导入为股票。不能以单次目录缺失判断退市，停牌股票也不能因无当日报价被目录同步删除。

现有添加流程要求可信价格快照，目录搜索成功不代表已经能对所有股票完成真实添加。目录先上线时，报价不可用仍按 STOCK-009 明确失败；不得用 Mock 价格静默填补真实行情。测试和显式模拟模式继续独立使用 Mock。

后续已完成公开目录下载、字段转换、数据库迁移、实际导入、账号授权、A 股行情权限、基础真实报价和按需 K 线，证据见 tasks 的目录与 CODE-206B/C 实施记录。北交所完整覆盖核对仍未完成。

### 4.2 目录字段接入核对（2026-09-08）

目录接入的数据流已实现：外部 JSON/Excel → Python 按市场批量转换和校验 → 标准目录 JSON → Java 同步事务写入 `stock` → 搜索 Service 查询本地表。采集和转换脚本不连接数据库；Java 导入入口单独核对实际 catalog、执行 Flyway 和事务。运行方式见 [开发环境第 8 节](../../engineering/development.md#8-股票目录导入)。

| 项目字段 | 来源或生成方式 | 本次验证边界 |
| --- | --- | --- |
| code | 外部证券代码 + 系统市场后缀，保留前导零 | `.SH/.SZ/.BJ/.HK`，导入前校验交易所与代码一致、无重复 |
| name | 沪市 SEC_NAME_CN、深市 A股简称、巨潮 zwjc、港交所中文股份名稱 | 港股中英文表按代码关联并核对 ISIN 和币种，OpenCC 批量转简体 |
| exchange | 来源市场转换 SSE/SZSE/BSE/HKEX | V4 扩展约束支持 BSE，保留前端交易所标注和标准代码后缀 |
| currency | A 股按已确认产品范围为 CNY；港股读取 Trading Currency 并按 HKD 范围筛选 | 港股有其他币种柜台，不能对所有证券直接写 HKD |
| listing_status | 本次新增记录为 UNKNOWN；重复导入保留已有可信状态 | V4 支持 UNKNOWN，不用目录存在推断正常交易，也不以缺失判断退市 |
| source | 适配层记录实际目录来源 | 与未来报价来源分开 |
| search_aliases | 港股原繁体名称和英文名称 | 保留检索入口，支持简体、繁体和英文搜索 |
| last_synced_at | 本次采集时间转换为数据库时间 | 与业务 updated_at 分开记录 |
| id、created_at、updated_at | 本地数据库生成 | 不要求外部供应商提供；updated_at 不自动等同于最后同步检查时间 |
| 关注价格、行情时间、行情来源 | 另取最新可用报价，写 research_cycle 快照 | 不是目录字段，本次未验证 |

已下载目录还包含部分行业、上市日期等信息，但不能据此承诺跨市场字段全部一致，也不为尚未确认的字段扩展表结构。

重复同步按 code 更新，保留已有 stock.id 和关联研究记录；不删除目录中暂时缺失的股票。首次目录已导入开发库，尚未实现每日定时同步、退市状态核验和供应商自动切换。北交所使用巨潮 920xxx A 股目录作为当前补充来源，完整性未确认；港股范围为主板/GEM 的 HKD 股权证券，未将所有证券类型或其他币种柜台混入。

### 4.3 Longbridge 基础报价接入（2026-09-09）

真实最短链路已经从“等待 Java SDK 浏览器 OAuth”调整为“复用官方 CLI 已完成的 Device Authorization Flow”。Java SDK 已升级至 `4.5.0`，但仍没有通过公开 API 导入 CLI Token 的能力；官方 CLI `v0.28.5` 的 `quote` 和稳定 `serve` JSON-RPC 都已实测成功。因此当前传输层使用 Spring Boot 管理 `longbridge serve` sidecar，完整取舍见 [ADR-002](../../architecture/decisions/ADR-002-longbridge-cli-sidecar.md)。

```text
搜索关键字
  → StockMapper.searchDirectory：最多 30 个可信本地身份
  → LongbridgeMarketDataProvider：收集标准代码
  → LongbridgeJsonRpcClient：一个 quote.quote 批量请求
  → 按 symbol 把价格和供应商时间合并回本地身份
  → StockSearchResponseVO

添加标准代码
  → StockMapper 再确认代码存在且不是 Mock
  → 短缓存命中或 quote.quote
  → StockQuoteSnapshot
  → 事务外行情成功后才进入原有研究事务
```

`LongbridgeJsonRpcClient` 负责传输，provider 负责业务模型映射：

- 客户端按需启动一个 CLI 子进程，用 UTF-8 换行 JSON-RPC 通信；请求 ID 递增，pending Map 允许响应乱序；stderr 只排空，不复制可能含账号信息的上游日志。
- 默认请求超时 5 秒。进程退出、写入失败、JSON-RPC error、结果结构错误或超时都转换为报价不可用；超时会终止该 sidecar，使同进程未完成请求一起明确失败，下一次请求再干净重启，避免旧请求继续占用上游并发。
- 关闭 Spring Context 时关闭 stdin 并等待 CLI 退出，超时后只销毁自己创建的进程。CLI 没有单独 HTTP 端口，也不应由用户再手工常驻一个 `serve`。
- 搜索候选批量发送，不做 N 次逐股票调用；Java 端最多允许 5 并发，相邻上游请求至少 100ms。相同代码默认缓存 5 秒，既降低快速重复读取，又保证填写观点较久后添加时可以重新拿较新的快照。
- 本地目录仍决定 code/name/exchange/currency/listingStatus；Longbridge 只提供大于 0 的 last_done 和可解析 timestamp。供应商 UTC 时间转换为 `Asia/Shanghai` 的 `LocalDateTime` 写入现有模型，来源记为 `LONGBRIDGE_OPENAPI`，`simulated=false`。
- 搜索失败降级为“目录存在但暂无报价”；添加失败继续触发 STOCK-009，研究表不产生半成品。不能自动切换 Mock，也不使用过期缓存冒充最新行情。
- Java 网关只实现五个固定只读方法：`quote.quote`、`quote.candlesticks`、`quote.history_candlesticks_by_date`、`quote.intraday` 与 `quote.trading_days`，不接受来自 Controller/前端的方法名。CLI 的 `trade.*`、`api.get/post` 等能力在本阶段不可达。

基础免费范围不能理解为无限制或所有市场实时。当前验证证明 A 股 `600519.SH` 可返回真实报价；账号仍受一个行情长连接、每秒 10 次、并发 5 次限制，实时报价一次最多传入 500 个代码，港股基础权限在特定请求规模或账号权限下可能返回延迟行情。项目保留供应商时间，页面不能仅凭来源标签宣称实时。历史 K 线另按自然月查询过的不同标的计额，当前基础开户档为 100 只、账户等级最高 3000 只，同一标的当月重复查询只计一次，并另受每 30 秒最多 60 次调用限制；因此 K 线只在详情页按需取得，不随搜索候选预取。定价、额度、权限和供应商条款变化时需重新核对。

原 `LongbridgeJsonRpcClient` 的报价与当天分时 5 秒缓存、K 线及历史日期批次 1 分钟缓存仍属于单 JVM 层；新增共享层见 4.6，不替换这些兜底路径。进程内缓存使用带过期时间的 LRU 上限：报价 4096、K 线 256、分时 512、交易日 2048 条，交易日结果最长复用 24 小时；过期读取会移除，超出容量淘汰最久未使用条目，避免精确 `before` 历史边界和日期键随进程运行无限累积。页面继续以 30 秒 pull 刷新单只可见分时。上游 WebSocket/SSE 仍按动态推送需求另行设计。多实例前仍必须解决单账号单连接和共享限流，不能因为有 Redis 就直接复制多个 sidecar。部署前置条件见 [部署说明](../../engineering/deployment.md#3-longbridge-sidecar-部署前置条件)。

### 4.4 已加入股票的按需 K 线（2026-09-09）

```text
用户展开共享自选中的一只股票
  → GET /api/workspaces/{workspaceId}/stocks/{workspaceStockId}/candlesticks
  → 重新验证空间成员并按 workspaceId + workspaceStockId 查标准股票
  → MarketCandlestickProvider
  → LongbridgeCandlestickGateway：先经共享缓存装饰层，再进入 LongbridgeJsonRpcClient
  → 首次最多 1000 根不复权 OHLCV，左边界按 before 游标继续补历史
  → Pinia 保存当前展开序列
  → 按需加载的 ECharts K 线／成交量组合图
```

- Controller 接受 `1m/5m/15m/30m/1h/day/week/month/year` 和 20～1000 根；页面首次固定请求上限 1000 根，后续携带当前最早时间 `before` 继续向前读取。
- K 线读取与搜索候选完全分离。只有 `workspace_stock` 已存在且当前用户仍是空间成员时才访问上游，避免一次模糊搜索把 30 个候选都计入月度不同标的额度。
- Java 客户端白名单包含五个固定只读方法：基础报价、最近 K 线、按日期历史 K 线、当天分时和交易日历。前端不能传 JSON-RPC 方法名，交易和任意 API 仍不可达。
- 原客户端最近 K 线按 `symbol + period + count + latest`、更早批次按完整 `before` 时间边界默认缓存 1 分钟；共享层参数见 4.6。历史接口虽然按日期请求，但必须先过滤 `< before` 再截取所需根数，不能用日期代替缓存 Key 中的精确时间。缓存不改变 Longbridge 按股票代码统计的月度不同标的口径。
- 上游 `301607` 在 K 线方法中映射为 `MARKET_KLINE_LIMIT_EXCEEDED`（HTTP 429），页面提示减少数据量或核对月度标的额度；其他上游失败映射为 `MARKET_KLINE_UNAVAILABLE`（HTTP 503）。官方 K 线文档只把该码定义为“请求数据数量超限／接口限制”，没有证明它只代表月额度耗尽；报价方法同码则明确是单次代码数量超限，不能跨接口共用一句错误含义。
- 当前序列不写 MySQL，刷新页面后重新按需读取；MySQL 只保留研究开始时的价格快照。CODE-206E 用 Redis 复用可重建的短期行情结果，但不把它当成永久行情库；后续要做跨供应商长期历史复盘时，再实现 `stock_daily_quote` 幂等落库。

### 4.5 分钟 K、长历史与分时实现

分钟 K 继续使用 K 线数据结构；同一 OHLCV 响应在 ECharts 上方网格绘制蜡烛图、下方网格绘制中性灰色成交量柱，两条类目轴由同一个 dataZoom 控制。分时使用独立的 `IntradayPoint(time, price, avgPrice, volume, turnover)` 与折线／成交量组合图，避免为了复用组件丢掉均价线。Longbridge [K 线接口](https://open.longbridge.com/zh-CN/docs/quote/pull/candlestick)支持 `1m/5m/15m/30m/1h`，所有周期首次请求接口允许的最近 1000 根，仍在现有 ECharts 图中通过 dataZoom 缩放。用户向左拖到已加载边界时，后端再用[历史 K 线接口](https://open.longbridge.com/zh-CN/docs/quote/pull/history-candlestick)的 date 或 offset 游标请求前一批最多 1000 根，按时间去重后插入同一数组；这里的“分页”只是上游分批取数，不是页面分页，也不要求更换图表或增加时间范围选择器。用户继续向左即可逐批到达供应商最早可用数据。

“全部历史”按供应商可用范围解释。当前产品只接入 A 股与港股；官方范围为 A 股日线 1999-11、分钟线 2022-08 起，港股日线 2004-06、分钟线虽然更早但还受账户资产对应的近 3 年／8 年限制；因此页面显示“可用以来”，并在达到供应商最早点时停止继续加载，不写成无条件“上市以来”。

当天分时的第一阶段数据流：

```text
成员打开已加入股票的分时面板
  → Spring Boot 校验空间成员与 workspace_stock
  → quote.intraday 拉取开盘至当前最新分钟的 price / avg_price / volume / turnover
  → 响应同时返回该市场常规交易时段；Vue 先建立完整分钟槽位
  → 仅在面板可见且市场处于相应交易时段时进行受控刷新
  → 当天 `quote.intraday` 按 symbol 使用 5 秒进程内短缓存
  → Vue 把已有点按 HH:mm 对齐，未来分钟留空，价格线、均价线和分钟量柱只画到当前位置
```

收盘后页面停止定时刷新，但用户重新打开当天分时仍走 `quote.intraday` 的 5 秒缓存，并未实现额外的盘后长缓存。过去交易日通过同一个 `longbridge serve` 的 `quote.history_candlesticks_by_date(period=1m, start=end=tradeDate)` 读取，使用分钟 close 绘制价格线，并由累计成交额／累计成交量计算当日均价线。官方 [CLI 分时说明](https://open.longbridge.com/docs/cli/market-data/intraday)与本机 CLI 0.28.5 的 `intraday --date YYYYMMDD` 都表明历史单日分时可用，但公开说明未写明该 CLI 包装命令自身如何计额；项目采用的历史 `1m` K 线方案明确遵守历史 K 线月度不同标的额度，同一股票当月回看任意多个日期仍只计一个标的。历史方法返回 `301607` 时可解释为当月历史 K 线标的数超限；最近 `quote.candlesticks` 的同码按单次数据量超限解释。

响应按当前产品支持的市场返回常规交易时段：A 股 `09:30～11:30、13:00～15:00`，港股 `09:30～12:00、13:00～16:00`，时间含义由同一响应的市场时区限定；不在 V1 范围的美股不会进入分时时段映射。ECharts 使用完整交易分钟类目轴，不再把盘中已有点均匀拉伸到全宽；未来槽位使用 `null`，刷新时只填入新到分钟。午休分钟不进入类目轴，两个时段的交界合并显示为 `11:30/13:00` 或 `12:00/13:00`，折线允许跨空值连接，因此供应商缺少边界分钟时也不会在计划内午休处断开，但未来没有后续点时不会向收盘时间延伸。历史分时从 `1m` K 线重建均价时，A 股先把以“手”返回的成交量换算为每手 100 股，再用累计成交额除以累计股数，避免均价放大 100 倍把价格线压平；港股不套用 A 股手数规则。历史返回点还会按市场时区严格过滤为所选日期。

近一年日期会先调用同一 sidecar 的 `quote.trading_days(market,start,end)`，CN/HK 的完整交易日和半日市都可通过，休市日返回 `MARKET_INTRADAY_NON_TRADING_DAY`（400）；官方接口只提供近一年交易日，因此更早日期不伪造本地日历，而由分钟 K 是否存在及供应商范围错误判断。当天 `quote.intraday` 的每个点还必须按市场时区属于请求日期，上一交易日残留点会被丢弃；只有交易日日历确认、日期为今天且时间处于对应常规时段，`updating` 才为真。历史分钟边界只从供应商固定格式提取日期，返回安全的 `MARKET_INTRADAY_HISTORY_RANGE_EXCEEDED`（422），不会把任意上游消息原样传给浏览器。

分时百分比以所选交易日前最近一根日 K 的收盘价为 `referencePrice`，不能使用当前报价的昨收去解释任意历史日期。Provider 通过已有历史 K 线网关读取所选日期前最多 10 根日 K，按市场日期过滤后取最近一根；响应把该价格连同分钟点返回。前端同时计算价格线和均价线相对昨收的最大绝对偏离，增加 8% 视觉余量后向上取 `1/2/3/5/10/20/30/50/100%` 档位；左价格轴固定为 `昨收 × (1±档位)`，右百分比轴固定为 `±档位`，两边都分成四段，因此 `0%` 与昨收严格处在同一水平线。分档只在突破当前档位时扩展，避免每次 pull 都发生细小缩放抖动。Tooltip 使用第三个不绘制的百分比序列，在价格和均价下方显示当分钟价格相对昨收的“涨跌幅”；价格、均价和涨跌幅都在 ECharts `valueFormatter` 层保留两位小数，右轴 `axisPointer.label.formatter` 共用带正负号和 `%` 的格式化函数；序列内部仍保留原始精度。

在 CODE-206D 阶段没有把 WebSocket、SSE 或 Redis 与 pull 正确性闭环捆绑：单后端、单个可见图表先用受控 pull 完成。当前 Redis 已独立接入，具体机制见 4.6；它不与上游订阅和浏览器推送捆成一次改造。后续若需要更及时的盘中变化，WebSocket 是 Longbridge 与 Spring Boot 之间保持不断开的双向连接，由供应商主动推送；SSE 是 Spring Boot 到浏览器的单向持续连接，由后端把新价格推给当前页面。进入多用户同时观看或多个股票动态更新后，可再由 `quote.subscribe` 的 quote/trades 推送聚合当前分钟，通过 SSE 发给浏览器，并定期用 `quote.intraday` 对账；届时复用 Redis 保存最新分时和订阅引用状态。MySQL 只在产品确认需要脱离供应商长期复盘时持久化不可变分钟事实。

### 4.6 Redis 共享行情缓存与访问保护

CODE-206E 实施中，尚未整体验收。原来 provider 直接依赖 `LongbridgeJsonRpcClient` 的网关接口：缓存只存在一个 Java 进程内，另一实例无法复用。现在 `CachingLongbridgeMarketGateway` 通过 `@Primary` 成为四类网关接口的首选实现，把可重建的行情缓存放在 provider 与旧客户端之间；Controller、Vue 和研究事务不直接操作 Redis。新增依赖为 Spring Data Redis，连接客户端使用 Lettuce。关闭 Redis 时使用 `NoOpMarketSharedCache`，仍走原行情链路，不切换 Mock。

```text
业务 Service 按接口校验身份与访问范围
  （搜索查本地标准身份；空间图表另外校验成员和 workspace_stock）
  → provider → CachingLongbridgeMarketGateway 生成 Key
      ├─ Redis 命中：JSON 还原为原始行情对象 → provider 映射项目响应
      └─ 未命中：合并同 JVM 请求 → 尝试 Redis 锁
          ├─ 取得锁：再次查缓存；仍未命中才调用旧客户端
          ├─ 锁被占用：短时等待共享结果；超时走旧客户端
          └─ Redis 不可用：直接走旧客户端
              → 进程内短缓存或受限 sidecar pull → 写 Redis（可用时）
```

`Key` 是结果的查询身份，`TTL` 是 Redis 允许复用该结果的有效时长，不是股票历史总长度。实现由 [MarketCacheKeys](../../../src/main/java/com/stock/system/stock/market/MarketCacheKeys.java) 和 [MarketRedisProperties](../../../src/main/java/com/stock/system/stock/market/MarketRedisProperties.java) 定义；以下为共享层当前默认值，原客户端短缓存不因此消失。

| 数据 | Key 的结果区分条件（均带可配置版本前缀） | 当前基础 TTL |
| --- | --- | --- |
| 批量报价 | 规范化、去重、排序后的整批代码集合的 SHA-256 | 5 秒 |
| 最近 K 线 | 标准代码、周期、`latest`、数量 | 1 分钟 |
| before 历史批次 | 标准代码、周期、`before` 的 epoch 毫秒、数量 | 已确定不含当前周期时 12 小时；否则 1 分钟 |
| 指定日期 K 线（供历史分时使用） | 标准代码、周期、日期 | 12 小时 |
| 当天分时 | 标准代码、股票市场时区下的当天日期 | 5 秒 |
| 交易日历 | CN/HK 市场、日期 | 24 小时 |

默认前缀为 `stock-system:market:v1`，写入 TTL 加 ±10% 随机抖动。报价按整批集合缓存，不是逐股票共享缓存：`[A,B]` 与 `[B,A]` 可命中同一 Key，但 `[A]` 与 `[A,B]` 不能共享这层结果，仍可能命中旧客户端的逐股票缓存。缓存值直接序列化原始 `Map/List/Boolean`，报价、K 线和分时点保留供应商时间与首次取得数据的 `retrievedAt`，缓存命中不刷新该时间；目前没有统一含 `source` 的缓存信封，`LONGBRIDGE_OPENAPI` 由 provider 补回，日历只存布尔值。与需求中“缓存值保留来源”的差距不能写成已经完成。

同 JVM 的 `CompletableFuture` 让正在加载同一 Key 的请求等待同一个结果；跨实例锁用 Redis `SET NX` 加随机 owner，默认租期 8 秒，释放用 Lua 原子比较 owner 后删除，防止误删别人续接的锁。锁竞争者每 40ms 查共享结果，默认最多等待 800ms；超时后不持锁调用旧客户端，因此它是限时等待加可用性降级，不保证所有并发场景只回源一次，也不是全局限流器。跨实例等待尚须补可靠的竞争与超时验收。

`RedisMarketSharedCache` 在连接／命令异常后默认冷却 30 秒，期间跳过 Redis，回到原有进程内缓存与受限 pull；日志只记录异常类型，不记录可能含凭据的原始异常信息。无法反序列化的 JSON 会删除后重取，但尚未校验反序列化成功后的字段语义。空 List/Map 和抛出的上游异常不写入共享缓存，日历 `false` 是合法休市结果，可以缓存。这些保护不替代已有参数、目录、成员和空间股票校验，也不能解除单账号单行情连接与供应商额度。

这里的“缓存”是对一次完整查询结果的临时快照：例如日 K 请求命中时返回整批 K 线，而不是只缓存“最后一根”；同一 Key 在 TTL 内可以避免重复回源，不同数量、周期、`before` 边界或日期仍是不同结果。日 K 的最近一批当前 TTL 为 1 分钟，适合短时间重复打开或刷新。`before` 是排他上界：只有它不晚于股票市场时区下当前分钟／小时／日／周／月／年的周期起点时，结果才确定不含仍会变化的当前 K 线并使用 12 小时；直接 API 若给出更晚边界，统一降为 1 分钟。这是偏保守的正确性判断，例如本周已收盘但下周尚未开始时仍使用短 TTL。Redis 仍不是行情事实来源或永久存档。

本机开发拓扑是 Windows 若依 Redis `127.0.0.1:6379` 与 Ubuntu WSL2 Redis `127.0.0.1:6380` 并存，stock-system 只连接后者；这是为避免本机两个项目互相抢端口的开发隔离，不是生产架构要求。WSL 实例使用 `/tmp`，关闭 RDB 与 AOF，重启后缓存为空但可从 Longbridge 重建；生产 Redis 的持久化、容量上限、备份和淘汰策略必须按部署可靠性单独决定。

历史批次的未收盘长缓存问题已按上述排他边界收敛；缓存来源字段仍未按需求显式保留。命中、未命中、合并和加载目前主要靠 DEBUG 日志，且“loaded from upstream”可能只是调用到旧客户端缓存，不能据此冒充真实外部请求次数或性能结论。未完成验收和测试证据集中见 [CODE-206E](tasks.md#code-206e-redis-共享行情缓存实施中证据2026-09-11)，运行与配置方式见 [开发环境](../../engineering/development.md#10-redis-本地学习环境)。

## 5. 首批 API

| 方法   | 路径                                   | 作用                                     |
| ------ | -------------------------------------- | ---------------------------------------- |
| `GET`  | `/api/stocks/search?keyword=`          | 按模式搜索本地标准目录或模拟股票；无报价时价格与时间为空 |
| `POST` | `/api/workspaces/{workspaceId}/stocks` | 添加共享股票、创建活动周期并发表初始观点 |
| `GET`  | `/api/workspaces/{workspaceId}/stocks` | 查询当前空间共享自选列表                 |
| `GET`  | `/api/workspaces/{workspaceId}/stocks/{workspaceStockId}/candlesticks?period=1m&count=1000&before={instant}` | 按需读取空间股票 K 线；`before` 为空取最新，存在时取更早；仅成员可读 |
| `GET`  | `/api/workspaces/{workspaceId}/stocks/{workspaceStockId}/intraday?date={YYYY-MM-DD}` | 按需读取当天或历史交易日分时；仅成员可读 |

搜索接口需要登录但不依赖某个空间；添加和列表接口必须验证目标空间成员关系。搜索结果只作为候选展示，添加时后端再次通过 provider 获取和校验股票身份与行情，不能信任前端回传的名称、价格、作者、时间或来源。

列表查询以当前活动研究周期为入口，联接标准股票、首次添加人和 INITIAL 观点，一次返回页面首屏所需摘要。活动空间和归档空间的有效成员都可读取；非成员统一不可见。归档只读是写入限制，不是抹掉历史读取权限。

## 6. 添加事务的数据流

```text
前端提交 workspaceId、标准股票代码和观点输入
  → Controller 做请求格式校验
  → Service 取得当前登录用户
  → provider 在事务外取得可信股票与行情快照
  → 进入数据库事务
      1. 锁定并检查空间仍为 ACTIVE
      2. 检查当前用户仍是有效成员
      3. 查找或写入标准 stock
      4. 查找或写入 workspace_stock
      5. 检查不存在活动 research_cycle
      6. 写入新 research_cycle
      7. 写入当前用户的 INITIAL stock_opinion
  → 提交事务
  → 返回共享股票、活动周期和初始观点摘要
```

外部行情调用放在数据库事务外，避免慢网络长时间占用行锁；进入事务后再次校验空间和成员，防止行情请求期间权限或空间状态变化。行情失败发生在事务前，不写任何记录；数据库阶段任一步失败由 Spring 事务整体回滚。

## 7. 前端最小页面

- 空间导航中的“共享自选”进入真实列表页；
- 页面提供一个内联搜索入口，候选项显示标准代码、名称、交易所；模拟报价明确标注，真实目录缺少报价时显示“暂无报价”，UNKNOWN 显示“交易状态待核实”；
- 有报价的候选才展开初始观点表单；暂无报价时说明暂不能添加，后端仍独立拒绝无报价添加；提交成功后刷新列表；
- 重复活动周期返回现有共享股票 ID 时，前端提示“该股票已在关注中”，并为后续详情页保留跳转入口；
- OWNER 与 MEMBER 使用相同添加能力；归档空间只展示列表和只读说明，不显示提交入口。
- 每条已加入股票提供一个按需行情面板；只有展开后才默认请求日 K，可切换九种 K 线周期或分时。K 线支持同步成交量、缩放、左边界补载和历史错误重试，分时支持日期选择、价格／均价／成交量组合图及盘中受控刷新；两个 ECharts 组件都按需加载，不增加共享自选首屏负担。

前端按 `View → stock Store → stock API → 后端` 分层。View 只负责候选选择、表单交互和状态呈现；Store 统一管理搜索、列表与提交状态，并在提交成功后重新读取共享列表；API 层只描述 HTTP 契约。目标价和止损价在页面上是可选数字，运行时按空值或正数转换，不能假定 `type="number"` 的 `v-model` 永远仍是字符串。

## 8. 针对性验证

- provider 单元测试：A 股/港股搜索、标准身份、模拟标识、K 线／历史分时转换、配额映射和不可用错误；
- MySQL 集成测试：OWNER 添加、MEMBER 添加、非成员拒绝、归档拒绝、跨空间隔离、重复活动周期、事务回滚和唯一约束；
- 前端单元测试：搜索状态、表单可信字段边界、添加成功刷新、按需 K 线、历史批次合并、分时请求、业务失败与网络失败；
- 纵向闭环完成后做一次 OWNER/MEMBER 浏览器流程；环境和清理要求见 [测试规则](../../engineering/testing.md)。

模拟业务闭环、目录扩展、真实报价、九种周期 K 线、同图历史补载和 pull 分时均已有自动化、真实 CLI／sidecar 与浏览器页面证据。隔离浏览器流程验证了真实日 K、1 分钟 K、当天／历史分时、分钟历史范围提示及休市日错误；测试进程、端口和 `stock_system_test` 数据均已清理。
