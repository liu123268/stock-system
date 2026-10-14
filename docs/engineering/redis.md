# Redis 工程教学

本文是 Redis 专题的复习提纲：按主题保留心智模型、关键代码路径和易错边界，不逐轮记录课堂讲解；代码已经存在不等于学习者已经掌握。实际采用情况、方案和进度分别见 [技术栈](../architecture/technology-stack.md)、[缓存设计](../specs/002-stock-research/design.md#46-redis-共享行情缓存与访问保护) 和 [当前状态](../project/status.md)。

## 1. 学习目标与顺序

完成本专题后，应能够：

1. 解释 Redis 在整个系统中的位置，以及它与 Java 内存、MySQL、Longbridge 的区别；
2. 说明 Key、Value、数据结构、TTL、命中、未命中和回源；
3. 沿一次行情请求找到 Controller、Service、Provider、缓存网关、共享缓存和上游客户端；
4. 解释缓存穿透、击穿、雪崩，以及项目已经处理和尚未处理的边界；
5. 从“多个请求同时做同一件事”的问题出发，解释锁、锁所有者、过期时间和 Lua 安全解锁；
6. 说明 Redis 故障时项目如何降级，并读懂相关单元测试和真实 Redis 集成测试；
7. 区分项目真实使用、面试基础知识和暂时没有真实场景的高级能力，能够形成准确的简历描述。

本专题的理解依赖是：整体系统地图；Redis 基础模型；项目文件关系；一次命中与未命中；Key、JSON 与 TTL；并发和锁；穿透、击穿与雪崩；故障降级；持久化、淘汰和部署边界；测试与项目表达。接续时按 [实际交接](../project/status.md#求职专题学习交接) 和缺口选章，只有阻碍当前目标的前置知识才补，不将此导航当作每轮必须从头讲的顺序；共用方法见 [教学方法](../project/teaching.md)。
本专题按 [统一专题完成标准](../project/roadmap.md#专题完成标准) 验收；上面的目标用于组织复习与练习，不另设一套较弱的掌握标准。

## 2. 第一张整体地图

Redis 是一个独立运行的服务，不在 Java 代码内部，也不是 MySQL 的一张表。本项目同时存在四种不同位置的数据：

```text
浏览器
  |
  v
Spring Boot Java 进程
  |-- Java 对象和进程内短缓存：只属于当前进程，重启即消失
  |-- MySQL：保存用户、空间、股票身份、研究周期和观点等业务事实
  |-- Redis：保存可丢失、可重新获取的共享行情副本
  `-- Longbridge：Redis 未命中时访问的真实行情上游
```

Redis 在本项目中的职责不是保存用户、密码、JWT 或研究事实，而是减少相同报价、K 线、分时和交易日请求对 Longbridge 的重复访问。Redis 数据丢失后，项目仍能重新访问上游，因此它是性能优化层，不是业务事实源。

### 2.1 本机、进程与网络

“运行在同一台电脑”不等于“处于同一个程序内部”。浏览器、Spring Boot、MySQL 和 Redis 都是独立进程，各自拥有受操作系统隔离的内存，不能直接读取另一个进程的 Java 变量。Redis 默认通过 TCP 端口接收命令，因此 Spring Boot 即使连接本机 `localhost:6380`，仍要通过操作系统的本地网络栈和 Socket 发送 Redis 协议；数据不会离开本机，但通信方式仍属于网络通信。

一个 Spring Boot 进程可以同时处理许多用户的许多请求。用户数量、请求数量和后端进程数量不是同一概念：本地开发通常只有一个后端进程；生产环境可能为了容量或可用性启动多个 Java 进程或容器，它们拥有各自的 Java 内存，但可以连接同一个 Redis 共享缓存。

### 2.2 Key 是 Redis 中一条数据的名字

Redis 不是先建立行情表再按多列查询。本项目使用 Redis String 时，每条数据至少包含 `Key -> Value`：Key 是应用为这份数据构造的唯一名字，Value 是保存的行情 JSON。应用向 Redis 发送 `GET key` 精确取值，或发送带过期时间的 `SET key value` 保存值。

锁 Key 也只是普通 Redis 数据。Redis 不会因为名字以 `:lock` 结尾就自动理解业务锁；应用约定“这个 Key 存在，表示某个请求正在加载对应行情”，并把随机 owner 作为 Value、短 TTL 作为自动失效时间。锁来自应用对普通 Key 的使用规则，而不是 Redis 中另有一种名为 Lock 的数据类型。

### 2.3 Redis TTL 与页面刷新是两套机制

TTL 只决定 Redis 中的 Key 何时失效，不会主动通知浏览器，也不会自动触发 Vue、Spring Boot 或 Longbridge 请求。页面先由前端交互或定时器发起新 HTTP 请求，后端收到请求后才会读取 Redis；此时缓存仍有效就命中，已经过期就回源。

当前页面只有满足“当前分时、面板展开、页面可见且后端标记仍在更新”时每 30 秒自动请求。日 K、年 K 等 K 线没有定时刷新，只在展开、切换周期、向左加载历史或重新进入页面等动作发生时请求。这里的前端请求节奏与 Redis 的报价、K 线 TTL 各自独立，不能把缓存过期理解成页面自动变化。

## 3. 项目代码总图

以 K 线请求为例，稳定的调用方向是：

```text
WorkspaceStockController
  -> StockCandlestickService
  -> MarketCandlestickProvider
  -> LongbridgeMarketCandlestickProvider
  -> LongbridgeCandlestickGateway
  -> CachingLongbridgeMarketGateway
       -> MarketSharedCache
            |-> RedisMarketSharedCache（Redis 开启）
            `-> NoOpMarketSharedCache（Redis 关闭）
       -> LongbridgeUpstreamGateway（未命中或降级时回源）
            |-> dev：LongbridgeSdkClient -> Java SDK / OpenAPI
            `-> prod：LongbridgeJsonRpcClient -> CLI / OpenAPI
```

各文件只承担一个主要问题：

| 文件 | 主要职责 |
| --- | --- |
| `application-dev.properties` | Redis 地址、开关、TTL、锁等待和故障冷却参数 |
| `MarketRedisProperties` | 把配置绑定成 Java 对象 |
| `MarketSharedCache` | 定义项目需要的缓存和同 Key 锁能力，不暴露具体 Redis 客户端 |
| `RedisMarketSharedCache` | 使用 `StringRedisTemplate` 实现读、写、删除、抢锁、解锁和失败冷却 |
| `NoOpMarketSharedCache` | Redis 关闭时提供空实现，使上层仍可回源 |
| `MarketCacheKeys` | 统一生成报价、K 线、分时和交易日缓存 Key |
| `MarketCacheEnvelope` | 规定缓存 JSON 同时保存来源和值 |
| `MarketCacheTtl` | 给基础 TTL 增加随机抖动 |
| `MarketCacheLock` | 表示获得锁、发生竞争或 Redis 不可用三种结果 |
| `CachingLongbridgeMarketGateway` | 编排读取缓存、请求合并、抢锁、回源、写缓存和降级的完整流程 |
| `LongbridgeUpstreamGateway` | 隔离 SDK／CLI 传输，缓存网关依赖这个统一接口 |
| `LongbridgeSdkClient`／`LongbridgeJsonRpcClient` | 分别访问官方 SDK／CLI sidecar，保留进程内短缓存与各自的访问限制；实现与环境分工见 [ADR-002](../architecture/decisions/ADR-002-longbridge-cli-sidecar.md) |

### 3.1 用当天分时串起一次真实请求

以**当天分时点列表**为例；历史分时走一分钟 K 线分支：

1. HTTP 请求 → [`WorkspaceStockController.intraday`](../../src/main/java/com/stock/system/stock/controller/WorkspaceStockController.java) → [`StockIntradayService.get`](../../src/main/java/com/stock/system/stock/service/StockIntradayService.java)（校验空间、查股票）→ [`StockMarketSeriesService.getIntraday`](../../src/main/java/com/stock/system/stock/service/StockMarketSeriesService.java)（确定日期）→ [`LongbridgeMarketIntradayProvider`](../../src/main/java/com/stock/system/stock/market/LongbridgeMarketIntradayProvider.java)（选择当天分支）→ [`CachingLongbridgeMarketGateway`](../../src/main/java/com/stock/system/stock/market/CachingLongbridgeMarketGateway.java)。
2. 网关用 `MarketCacheKeys.intraday` 生成 Key，`read` 经 `MarketSharedCache`、`RedisMarketSharedCache` 发起 `GET`。命中则解析 `MarketCacheEnvelope` 的来源与分时点列表；Key 不存在时得到 `Optional.empty()`，不是异常。`json` 只是 `Optional<String>` 变量名；连接故障也返回空，但会记录故障并冷却，单看返回值分不出两者。
3. 未命中：`inFlight` 合并进程内请求 → Redis 锁协调跨进程加载 → 二次检查 → `LongbridgeUpstreamGateway` 对应的 SDK／CLI 客户端（可能命中其短缓存）→ 非空结果带 TTL 写入 Redis 并直接返回 → 安全解锁。锁竞争与超时见 [5.2](#52-本项目如何减轻热点-key-击穿)，解锁见 [4.2](#42-为什么解锁必须核对-owner)。

边界：整个分时响应还可能读取交易日和前收盘价，并非只访问这一个 Key；Redis 未命中也不等于一定新发 Longbridge 网络请求。

## 4. Redis String 的第一组命令

本项目用 Redis String 保存行情 JSON 和锁 owner。冒号只是 Key 命名分隔符，不会建立目录；`market:kline:600519:day` 与 `market:kline:600519:day:lock` 是两个独立 Key。

第一组命令及项目对应关系如下：

| Redis 命令 | 直白含义 | 项目 Java 调用 |
| --- | --- | --- |
| `SET key value` | 按名字保存一个值 | `opsForValue().set(...)` |
| `GET key` | 按名字读取一个值 | `opsForValue().get(...)` |
| `DEL key` | 删除这个名字和值 | `delete(...)` |
| `TTL key` | 查看还剩多少秒过期 | 用于观察和测试，主流程不靠轮询 TTL |
| `SET key value NX EX/PX ...` | 仅在 Key 不存在时写入，并设置过期时间 | `setIfAbsent(..., ttl)` |

行情数据 Key 保存行情 JSON；锁 Key 保存随机 owner。锁 Key 存在只表示“已有请求正在加载对应行情”，不表示行情数据 Key 已经存在。等待方需要继续检查数据 Key，只有数据 Key 出现后才能复用结果。

### 4.1 锁 Key 的两种消失方式

正常路径中，持有加载资格的请求完成或抛出可处理异常后，Java 在 `finally` 中主动调用解锁；Redis 端用 Lua 先核对 owner，再 `DEL` 整条锁记录。锁通常在任务完成时就消失，不需要等 TTL 到期。

TTL 是异常兜底：如果 Java 进程崩溃、机器断电或执行没有机会进入解锁逻辑，Redis 会在设定时长后让锁 Key 自动失效。没有 TTL 时，正常主动解锁仍能删除锁；但一旦主动解锁没有发生，锁可能永久残留。当前项目等待超时后允许受控回源，因此不会永久卡住一次请求，但残留锁会让后续请求持续走等待和降级，失去减少重复回源的作用。

上游请求超时与锁 TTL 由不同主体处理：Java 等待 Longbridge 超时后抛出异常，并在调用栈退出时通过 `finally` 主动解锁；若整个 Java 进程崩溃或解锁命令失败，才由仍在运行的 Redis 按 TTL 清理锁。TTL 只删除锁 Key，不会停止仍在执行的旧 Java 请求。

`SET lock-key owner NX PX 8000` 成功时，过期时间附着在新建的锁 Key 上，从创建成功的时刻开始计算 8 秒；不是这条命令等待 8 秒，也不是命令失败后安排 8 秒后自动重试。若因 Key 已存在而失败，本次命令不创建任何内容；旧锁过期后，必须有新的请求再次执行命令，才可能创建新锁。

### 4.2 为什么解锁必须核对 owner

创建锁 Key 通常瞬间完成，之后调用上游才可能耗时超过锁 TTL。示例：A 在第 0 秒创建 `lock-key -> owner-A`，随后调用 Longbridge；第 8 秒锁自动过期，但 A 的 Java 请求没有因此停止，仍在等待上游；第 9 秒 B 发现同名 Key 已不存在，于是创建 `lock-key -> owner-B`；第 10 秒 A 才执行完毕。此时如果 A 只执行 `DEL lock-key`，Redis 会删除这个名字下当前属于 B 的新记录。

因此解锁不能只凭 Key 名，还要确认当前 Value 仍等于自己的 owner。若当前是 `owner-A`，A 才能删除；若已经是 `owner-B`，说明 A 的旧资格已过期并被 B 接手，A 不得删除。项目用 Lua 把“比较当前 owner”和“删除”作为一个不可插入其他命令的原子操作执行。

原子性是一种“操作不可被其他请求从中间插入”的保证，不等同于事务。`putIfAbsent` 与 `SET ... NX PX ...` 都把检查和写入合成一个原子操作，所以并发请求即使同时到达，也只有一个能成功；Lua 可把多步判断与删除组成一个原子脚本。事务则是组织多条操作的机制；Redis `MULTI/EXEC` 侧重连续执行且不被插入，不提供 MySQL 事务那样的完整回滚语义。

## 5. 穿透、击穿与雪崩：先看请求形状

这三个词描述的不是三种 Redis 命令，而是“缓存未命中以后，大量请求怎样压到下游”的三种形状：

| 问题 | 大量请求在查什么 | 为什么持续未命中 | 主要后果 |
| --- | --- | --- | --- |
| 缓存穿透 | 不存在的数据，或大量从未缓存过的不同参数 | 下游也查不到，空结果又没有缓存 | 请求不断越过 Redis，消耗数据库或上游额度 |
| 缓存击穿 | 同一个真实、非常热门的 Key | 这个热点 Key 恰好过期 | 瞬间出现大量相同回源请求 |
| 缓存雪崩 | 许多不同 Key | 大批 Key 同时过期，或 Redis 整体不可用 | 大面积回源、延迟升高并可能形成级联故障 |

### 5.1 本项目对缓存穿透的边界

假设接口允许客户端随意提交股票代码，攻击者不断构造不存在的代码；若每次都是 Redis 未命中、Longbridge 也查不到、空结果又不缓存，那么相同攻击可以持续消耗上游配额、连接、线程和响应时间。这个后果才是“穿透”，不是 Redis 数据被攻破。

当前行情预览入口先规范代码格式，再从 MySQL 股票目录确认它是可信股票；不存在的任意代码会在进入 Longbridge 前以 `STOCK_NOT_FOUND` 结束。K 线数量、周期和日期也有范围校验。因此经典的“任意不存在股票代码直接穿到上游”已被明显缩小，但不能称为绝对解决：已登录用户仍可组合许多合法股票和合法参数制造大量不同的冷 Key；另外网关目前不缓存空 List/Map，某只真实股票若持续返回合法空数据，后续请求仍会回源。

现阶段不应为了名词完整而立即加入布隆过滤器。更匹配真实风险的后续措施是先做请求指标和命中率观测，并在确有滥用时增加按用户/IP 的接口限流；只有“稳定不存在”的结果才适合短 TTL 负缓存，不能把上游超时等临时故障误缓存成“不存在”。

### 5.2 本项目如何减轻热点 Key 击穿

例如某只热门股票的当前分时 Key 到期，同一时刻有 100 个请求到达。若没有保护，100 个请求都会访问 Longbridge，容易耗尽项目设置的并发数和调用频率，真实用户会等待或失败。

项目在一个 Java 进程内用 `inFlight.putIfAbsent` 合并同 Key 请求：第一个请求执行加载，其余请求复用同一个 `CompletableFuture`。它只协调本进程，但速度快且不访问网络，可先把每个进程的大量请求缩成一个候选者；Redis 锁再从连接同一 Redis 实例的这些候选者中选出一个加载者。等待者轮询行情数据 Key，加载者取得锁后还会再次检查缓存，避免等待期间数据已经由别人写入却仍重复回源。超时后的受控回源意味着该方案目标是“大幅减少重复行情调用”，不是严格保证所有实例只调用一次。

### 5.3 本项目如何减轻缓存雪崩

雪崩既可能是许多 Key 在同一秒过期，也可能是 Redis 故障后所有读取都相当于未命中。后果不是某一个 Key 多调用几次，而是报价、K 线、分时等大量不同请求一起压到 Longbridge，继而占满 Java 请求、延迟升高、触发上游限制。

项目使用不同类型的基础 TTL，并给实际 TTL 加随机抖动，降低大批 Key 同时到期的概率；Redis 故障后使用冷却时间，避免每个业务请求都重复等待一次 Redis 连接失败；上游两条传输均控制请求间隔，生产 CLI 用 Semaphore 限制并发，本地 SDK 用 ReentrantLock 串行；具体约束见 [ADR-002](../architecture/decisions/ADR-002-longbridge-cli-sidecar.md)。这些属于单机/小规模项目的合理保护，但不是生产级高可用方案：当前仍缺少按接口或用户的全局限流、完整熔断与 Redis Sentinel/Cluster 部署验证。是否继续增加这些能力，应由真实压力、监控指标和部署规模决定。

## 6. 缓存一致性：先区分数据源

“数据库与缓存一致性”通常讨论同一份业务数据同时存在于 MySQL 和 Redis 的场景。Cache Aside（旁路缓存）表示缓存位于主数据源旁边，由应用分别访问两者：读请求先查 Redis，未命中再查 MySQL 并写入 Redis；写请求先提交 MySQL，再删除对应缓存，使下一次读取从 MySQL 重建。删除是幂等操作；直接更新缓存则会增加数据库与缓存双写失败、并发写入乱序和无读取需求却创建缓存等问题。

| 模式 | 数据源访问由谁负责 | 主要取舍 |
| --- | --- | --- |
| Cache Aside | 应用分别访问缓存和数据源 | 最常见，应用负责回填与失效 |
| Read Through | 缓存层在未命中时读取数据源 | 对应用隐藏加载过程 |
| Write Through | 缓存层同步写入数据源 | 完成后两边已写，写延迟较高 |
| Write Behind | 缓存层异步写入数据源 | 写入快，但可靠性与恢复更复杂 |

“提交 MySQL”和“删除 Redis”是发往两个独立系统的两次操作，不是一条原子命令，也通常不在同一个本地事务中。Java 方法只是按顺序发起操作；`@Transactional` 通常只管理绑定到 MySQL 连接的数据库事务，不能自动把 Redis 一并纳入原子提交。即使每一条 MySQL 语句和 Redis `DEL` 在各自系统内部都是原子的，也不代表两者组合后原子。这个问题通常称为数据库与缓存的跨系统一致性问题；从应用同时修改两个系统的角度，也常被归入双写一致性问题。

先提交 MySQL 再删除缓存仍不能提供强一致性，要区分两种并发窗口。第一种是短暂旧读：B 已提交 MySQL 新值但还没执行 Redis 删除，A 在两步之间命中 Redis 旧值并返回；删除成功后该窗口结束。第二种是旧值回填：A 更早因缓存未命中而从 MySQL 读到旧值，B 随后提交新值并删除缓存，A 最后才把手中的旧值写回 Redis；旧值会留到 TTL 到期或再次失效。另一个失败边界是数据库提交成功、缓存删除因 Redis 故障失败，旧缓存同样会继续存在。

不同方案处理的是不同风险，不能作为必须同时接入的技术清单：

| 方案 | 主要作用 | 代价与边界 |
| --- | --- | --- |
| 短 TTL / 接受最终一致性 | 给旧数据限定最长存活时间 | TTL 内仍可能读旧值，适合允许短暂陈旧的数据 |
| 延迟双删 | 更新并首次删除后，延迟一段时间再删一次，清理由并发旧读回填的缓存 | 延迟难精确选择，第二次删除也可能失败，不提供绝对保证 |
| 消息队列 | 把“需要失效哪个缓存”可靠交给消费者，并允许删除失败后重试 | 数据库提交后、消息发出前仍可能崩溃；严谨实现还要解决消息可靠产生、重复消费和延迟窗口 |
| 订阅 Binlog | 从 MySQL 自己的已提交变更日志产生事件，减少业务代码漏发失效通知的风险 | 后续通常仍经 CDC、消息队列和消费者异步处理，增加中间件、运维和事件延迟 |
| 版本号 | 当旧读结果与新结果乱序到达时，让系统识别并拒绝低版本覆盖高版本 | 不能只给 Value 加字段；必须保留可比较的最新版本，并原子完成比较与写入 |
| 逻辑过期 | Key 不物理过期，Value 保存业务过期时间；过期后由少量请求刷新，其他请求可暂时使用旧值 | 重点保护热点可用性，主动接受旧数据，并不能单独解决所有数据库写入一致性问题 |

### 6.1 本项目为何没有采用数据库删除缓存方案

当前 Redis 保存的是 Longbridge 行情副本，不是 MySQL 用户、空间、研究等业务记录。项目不负责修改 Longbridge 的源数据，也无法在其内部行情变化时执行“更新数据库后删除 Redis”。因此当前行情采用按数据变化速度划分 TTL 的最终一致性：报价和分时使用秒级 TTL，当前 K 线使用分钟级 TTL，较稳定的历史 K 线和交易日使用更长 TTL；在 TTL 到期前允许短暂陈旧，到期后的下一次请求再从 Longbridge 重建。

当前代码没有为 MySQL 业务记录建立 Redis 读写和失效链路，因此不存在“更新 MySQL 后删除业务缓存”的实现。项目已经把 Cache Aside 的读取、未命中回源和回填思想用于 Longbridge 行情；不为展示技术而额外缓存一致性敏感且尚无性能瓶颈证据的 MySQL 数据。

消息队列和 Binlog 也不匹配当前数据源：项目自己的 MySQL Binlog 不包含 Longbridge 行情变化。逻辑过期可用于极高流量热点，但本项目已经使用短 TTL、同 Key 请求合并和 Redis 锁减少回源冲击。现阶段继续引入这些方案会增加复杂度，却没有对应的真实写入链路和规模证据。

## 7. Lua 在本项目中的唯一职责：安全解锁

Lua 是一门脚本语言。Redis 可以在服务端执行 Lua 脚本，并保证脚本中的多条 Redis 操作执行期间不会插入其他客户端命令。本项目不使用 Lua 编写业务功能，只用它把“读取锁 owner、比较 owner、匹配时删除锁”合成一次原子执行。

接续 4.2 节的 A、B 时间线：Java 通过 `StringRedisTemplate.execute` 发送 `UNLOCK_SCRIPT`，`KEYS[1]` 是锁 Key，`ARGV[1]` 是 A 保存的 owner。脚本调用 `GET` 比较两者，相等才调用 `DEL`；若 B 已接手，返回 0，不删除 B 的锁。

这里的“原子”范围只在 Redis 内：脚本不能把 MySQL 更新、Longbridge 请求或整段 Java 方法一起变成原子操作，也不会延长锁 TTL。它解决的是安全解锁中 GET 与 DEL 之间不能被其他命令插入这一件事。

## 8. 当前 Redis 锁与 Redisson 的关系

本项目使用的是**基于单 Redis 实例的跨 JVM 加载协调锁**：连接同一 Redis 的多个 Java 进程用同名锁协调谁加载一份行情。项目的 `inFlight` 只合并一个进程内的请求；跨进程时由 `RedisMarketSharedCache` 用 `setIfAbsent` 创建带 TTL 的锁 Key，并用 Lua 核对 owner 后解锁。它没有多 Redis 节点仲裁或故障切换保证；当前只读行情允许锁超时、Redis 故障等情况下偶发重复回源，因此这一级保证与项目风险相匹配，不能泛化为保护不可重复业务写入的高可用分布式锁。

Redisson 是另一套 Java Redis 客户端库。其 `RLock` 是供 Java 调用的锁对象；调用者使用 `tryLock` 和 `unlock`，库在内部与 Redis 通信，也会用 Lua 脚本。它替调用者管理获取与释放锁的细节，可用发布订阅通知等待者；不指定固定 `leaseTime` 时可用 watchdog 在持有者仍活跃时续期，指定固定租期时按该租期释放。当前项目自己用 `StringRedisTemplate` 写了加锁和 Lua 解锁，并由业务代码每 40 毫秒轮询行情结果、最多等待 800 毫秒。Redisson 不替业务决定等锁超时或 Redis 故障后是否继续访问 Longbridge，也不保证外部接口严格只调用一次。

可直接复述的项目回答见 [项目工程面试问答](../career/interview/01-project-engineering.md)。

## 9. Redis 重启后，行情缓存怎样恢复

Redis 主要在内存中提供数据。RDB 记成“快照”：定时把某一时刻的数据整体保存到磁盘；重启可加载最后一份快照，快照之后的变更可能丢失。AOF 记成“追加操作记录”：把修改数据的命令继续写到文件末尾，重启时重放；能恢复到什么程度取决于同步到磁盘的策略，也不是无条件零丢失。两者可组合使用，或都关闭。

当前本机学习实例的启动参数为 `--save "" --appendonly no`，即关闭 RDB 和 AOF。Redis 重启后行情 Key 和锁 Key 都不从磁盘恢复；后续 HTTP 请求读取不到缓存，会沿原有 Longbridge 网关重新获取并写入。Redis 暂时不可用时，Java 的故障冷却跳过 Redis，走按环境选择的 SDK／CLI 客户端的进程内缓存或受限回源。MySQL 中的用户、空间和研究事实不依赖 Redis 恢复。开发环境允许这样做，是因为 Redis 当前只保存可重建的行情副本；生产环境的持久化、容量和高可用策略须另外按恢复时间和回源能力验证。

## 10. TTL 与内存淘汰：两种不同的删除原因

TTL 是应用写入某个 Key 时指定的有效期限；到期后它不再是可用缓存。内存淘汰发生在 Redis 配置了 `maxmemory`、内存达到上限并要容纳新数据时；尚未过期的 Key 也可能被提前删掉，具体看 `maxmemory-policy`。例如本项目的历史 K 线基础 TTL 为 12 小时，但不同股票、周期、数量和 `before` 边界会生成不同 Key；即使所有 Key 都有 TTL，也不能仅凭 TTL 证明内存足够。

`noeviction` 表示达到上限后拒绝增加数据的写命令，已有读取仍可用；`allkeys-lru` 表示从所有 Key 中近似挑选近期较少使用的淘汰，新请求如果再次查到被淘汰的行情，就重新回源。当前缓存写入失败会进入 Redis 故障冷却并走原有行情链路，但回源量可能增加。淘汰策略也可能影响锁 Key：若锁被提前淘汰，另一个请求可能获得同名锁，因此不能把“设置内存淘汰”当作与并发保护完全无关的配置。

本机学习实例此前记录 `maxmemory=0`，表示未配置 Redis 自身的数据集内存上限；这不表示机器内存无限，也不能预测当前占用。`maxmemory` 没有通用数字：本机练习暂可保持现状，128 MiB 只能作为将来观察 `INFO memory` 时的实验起点，不能作为生产标准。部署前应根据典型 JSON 大小、同时存活的 Key 数量、命中率、`used_memory`、`evicted_keys` 和上游回源额度决定容量和淘汰策略，并为系统与 Redis 进程留内存余量。项目 Java 进程内的 LRU 上限与 Redis 的 `maxmemory-policy` 是两套机制。

## 11. 热点 Key 与大 Key：访问次数和单条体积

热点 Key 是同一个 Key 被大量请求反复访问，关注单位时间的访问次数；大 Key 是一条 Key 对应的 Value 很大，关注单次读取、写入和内存占用。两者可以分别出现，也可以同时出现：一只热门股票的当天分时可能因频繁访问成为热点；包含很多根 K 线的完整批次会序列化成同一个 Redis String，可能较大，但没有实测字节数前不能称它已是“大 Key”。

项目的 `inFlight` 与 Redis 锁主要减轻热点 Key **失效时**的重复 Longbridge 回源；如果 Key 一直命中却被极高频 `GET`，请求仍到 Redis，这不是同一个问题。大 Key 则会增加一次读取或写入的数据量，占用更多内存和网络；当前历史 K 线按不同股票、周期、数量与 `before` 生成 Key，再按完整结果缓存。具体风险要用 Key 的 `MEMORY USAGE`、Redis 命中与请求指标以及页面响应时间判断；出现实际瓶颈再考虑缩小批次、调整缓存粒度或对只读热点增加短时进程内复用。

## 12. 常用数据结构与非缓存用途

Redis 的 Value 不只是一整段字符串。选择数据结构的依据是“需要对 Value 做什么操作”，不是看到多个字段就一定换类型：

| 类型 | Redis 能直接完成的核心操作 | 常见场景 | 本项目 |
| --- | --- | --- | --- |
| String | 整体读写一个值；数字字符串还能原子增减 | JSON 缓存、计数器、锁 owner、会话值 | 行情 JSON 和锁 owner |
| Hash | 按 field 单独读写一个对象的部分字段 | 用户资料、对象属性 | 未采用 |
| List | 按顺序从两端加入、读取或弹出独立元素 | 简单待处理队列 | 未采用 |
| Set | 保存不重复成员并判断成员关系 | 标签、去重、共同成员 | 未采用 |
| ZSet | 每个不重复成员带 score，并按 score 排序 | 排行榜、优先级 | 未采用 |

String 中即使保存了 JSON 数组，对 Redis 来说仍是一个整体；`GET` 读取后不会自动删除。List 的每项则是 Redis 能单独操作的元素，`POP` 会取出并删除其中一项，其他项仍保留。多个待领取任务才构成 List 队列；只有一个独立任务时 String 也能表达。简单 List 在元素弹出后若处理者崩溃，任务可能丢失，因此不能未经可靠性设计就把它包装成生产级消息队列。

缓存只是 Redis 的一种使用方式。共享会话、原子计数、排行榜、简单队列和分布式协调，分别利用了 Redis 的共享访问、数据结构和原子命令；当前项目有真实场景的只有行情缓存与跨进程加载协调，不能把其余用途写进项目成果。

## 13. 持久化、复制与集群解决不同问题

RDB/AOF 解决“Redis 重启后怎样从磁盘恢复”；它们不等于服务不会中断。主从复制是主节点把数据复制给副本，可分担读请求或准备备用数据，但只有复制还不能自动决定主节点故障后由谁接管。Sentinel 在非分片部署中监控节点并在主节点故障时选举副本接管，重点是自动故障转移；Cluster 则把不同 Key 分片到多个节点，同时提供容量扩展和节点故障转移，客户端还要处理 Key 属于哪个分片。

当前项目没有部署 Redis 主从、Sentinel 或 Cluster，也没有多节点故障切换证据。Redis 只保存可重建行情副本，当前规模和部署环境不足以证明需要这些复杂度；生产化时应根据可接受中断时间、数据量、吞吐和运维能力选择，不能为了简历直接接入。

## 14. 怎样用测试证明缓存行为

测试不是证明“Redis 这个软件正确”，而是证明项目围绕 Redis 写出的规则正确：

| 测试 | 不需要什么外部环境 | 主要证明什么 |
| --- | --- | --- |
| `CachingLongbridgeMarketGatewayTest` | 不连接真实 Redis 和 Longbridge | 命中复用、Key 完整性、进程内合并、模拟跨实例竞争、过期重载、故障与等待超时降级、TTL 选择 |
| `RedisMarketSharedCacheTest` | Mockito 模拟 `StringRedisTemplate` | 连接失败进入冷却、后续快速跳过；只有 owner 匹配才安全解锁 |
| `MarketCacheWiringTest` | Spring 测试上下文 | Redis 开关关闭时注入 `NoOpMarketSharedCache`，开启时替换成 `RedisMarketSharedCache`，上层网关不变 |
| `RedisMarketSharedCacheIntegrationTest` | 需要显式启用的真实 Redis | 两条独立连接共享锁和结果；真实写入、读取、过期与测试 Key 清理 |

单元测试用替身快速覆盖分支，但不能证明 Redis 命令、连接和 TTL 在真实服务中一定正常；集成测试补这一层证据。反过来，一条真实 Redis 成功用例也不能替代各种异常分支的单元测试。现有双连接竞争能证明两个缓存客户端经同一 Redis 协调，不等于多个真实 Longbridge sidecar 已获准水平部署。项目简历证据见 [项目亮点](../career/highlights.md#5-股票研究首个纵向闭环)，项目实战回答见 [项目工程面试问答](../career/interview/01-project-engineering.md#project-05为什么行情缓存使用自写-redis-锁而没有接入-redisson)，通用高频题见 [Redis 与缓存面试问答](../career/interview/05-redis-cache.md)。
