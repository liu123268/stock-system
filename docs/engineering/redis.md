# Redis 工程教学

本文用于从零建立 Redis 心智模型，并把基础知识逐步对应到本项目现有行情缓存代码。它记录教学顺序和稳定的代码关系，不把“代码已经存在”写成“学习者已经掌握”，也不替代 [实际技术栈](../architecture/technology-stack.md)、[缓存设计](../specs/002-stock-research/design.md#46-redis-共享行情缓存与访问保护) 或 [当前状态](../project/status.md)。

## 1. 学习目标与顺序

完成本专题后，应能够：

1. 解释 Redis 在整个系统中的位置，以及它与 Java 内存、MySQL、Longbridge 的区别；
2. 说明 Key、Value、数据结构、TTL、命中、未命中和回源；
3. 沿一次行情请求找到 Controller、Service、Provider、缓存网关、共享缓存和上游客户端；
4. 解释缓存穿透、击穿、雪崩，以及项目已经处理和尚未处理的边界；
5. 从“多个请求同时做同一件事”的问题出发，解释锁、锁所有者、过期时间和 Lua 安全解锁；
6. 说明 Redis 故障时项目如何降级，并读懂相关单元测试和真实 Redis 集成测试；
7. 区分项目真实使用、面试基础知识和暂时没有真实场景的高级能力，能够形成准确的简历描述。

教学按以下顺序推进：整体系统地图；Redis 基础模型；项目文件关系；一次命中与未命中；Key、JSON 与 TTL；并发和锁；穿透、击穿与雪崩；故障降级；持久化、淘汰和部署边界；测试、简历与面试复述。没有建立前一层心智模型时，不跳到后续代码细节。

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
       -> LongbridgeJsonRpcClient（未命中或降级时回源）
  -> Longbridge CLI / OpenAPI
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
| `LongbridgeJsonRpcClient` | 真正与 Longbridge sidecar 通信，并保留进程内短缓存和访问限制 |

## 4. 学习进度的判断方式

每一层都按四个结果判断，不以“看过代码”代替掌握：

- 能用自己的话解释概念；
- 能在项目中指出对应文件和关键语句；
- 能手工推演正常、未命中、并发和故障流程；
- 能回答当前方案为什么这样做、有什么边界、还有哪些替代方案。

当前只确认学习路线开始，不在本文标记任何知识点已经掌握。实际实现和验收状态继续以技术栈、SPEC tasks 与状态文档为准。
