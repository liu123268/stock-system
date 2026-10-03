# 项目与工程实践面试问答

这里保存项目定位、架构取舍和工程质量相关的问答。项目经验题依据真实代码和验证证据；本专题未采用的基础技术若用于知识准备，须明确标注，不能写成项目成果。

## PROJECT-01：为什么采用前后端分离？

### 项目场景

项目需要共享自选股、成员观点、状态管理和后续走势图等交互，旧 Spring 静态 HTML 难以继续维护。

### 完整回答

> 项目前端采用 Vue 3 独立工程，后端使用 Spring Boot 提供 REST API。开发阶段 Vite 运行在 8083，Spring Boot 运行在 8082，由 Vite 代理转发 `/api`。这样前端交互和后端业务可以独立开发、测试和构建。生产环境计划由 Nginx 统一域名和入口，所以开发时的两个端口不会暴露给最终用户。代价是需要维护接口契约、前端状态和独立构建流程，但项目复杂度已经超过简单服务端页面，收益大于成本。

### 项目证据

- `frontend` 独立 Vue/Vite 工程；
- Spring Boot 只提供 `/api`；
- `vite.config.ts` 开发代理；
- 前后端分别具备测试和构建命令。

### 可能追问

- 为什么不让 Spring Boot 直接托管 Vue 构建文件？
- 开发代理和生产反向代理有什么区别？
- 前后端分离如何处理跨域？

## PROJECT-02：AI生成代码后如何保证质量？

### 完整回答

> AI用于提高实现和检索速度，但每项改动按风险选择需求检查、代码审查与相应层次的验证，纵向闭环和阶段收尾再扩大测试。项目曾遇到 Vue 脚手架生成的 Oxlint 版本冲突，没有使用 `--force` 掩盖问题，而是核对依赖关系、调整配套版本并重新验证。AI按变化更新唯一文档落点，里程碑再整理面试材料，使下一次对话能继续。候选人的价值不是“会让AI生成代码”，而是能判断正确性、定位问题、验证结果并解释技术权衡。

`CODE-008` 的改名闭环也说明测试本身需要维护：业务请求和页面已成功，但首次浏览器运行的清理脚本仍按旧空间名称定位数据，删除账号时触发外键限制。确认本轮数据归属后精确清理，改为兼容改名前后名称，再复测并核对剩余为零。证据见 [任务清单](../../specs/001-workspace/tasks.md#8-编码任务)。

### 可能追问

- AI生成的代码怎样做安全检查？
- 如何防止AI覆盖未提交改动？
- 为什么测试通过仍然可能有问题？

## PROJECT-03：为什么正式业务页面不能把 Mock 数据当成真实数据？

### 项目场景

项目从认证空状态逐步演进到真实空间、共享自选和股票目录。不同阶段都有尚未接通的下游数据，如果用固定股票或模拟价格填补，页面虽然看起来完整，却会让用户和测试误判系统已经具备真实能力。

### 完整回答

> 项目早期只有认证能力时，工作台只展示真实用户和空状态，没有用固定股票、价格或成员数伪装后端结果。后来空间和共享自选逐步接通，页面只展示真实 API 返回的数据。在首次接入真实股票目录的阶段，开发环境改为查询 MySQL；当时真实报价源还没有完成验证，所以接口主动把价格和行情时间返回为空，页面明确显示“暂无报价”，后端也拒绝用 Mock 价格创建研究周期。Mock 只保留给自动化测试和显式模拟模式。这个做法牺牲了一点页面的“完整感”，但保证了数据来源可信，也让每个阶段的测试能够准确证明系统实际拥有的能力。

### 项目证据

- 旧 `/workspace` 只保留兼容跳转，真实空间入口为 `/workspaces`；
- 当前用户来自 `/api/auth/me`；
- 空间列表、详情和共享自选来自真实 API，股票搜索在开发模式读取 MySQL 目录；
- `DirectoryMarketDataProvider` 在真实报价未接入时返回空结果，页面显示“暂无报价”，添加接口返回受控失败；
- `application-test.properties` 固定使用 Mock，开发环境只有显式选择模拟模式才使用 Mock；
- 真实 Chrome 已从注册、登录、创建空间和进入概览继续验证到双账号邀请加入、退出、重新加入、移除和所有权转让；历史污染和后续修复见 [BUG-003](../../engineering/bugs/BUG-003-browser-test-database-boundary.md)，不能笼统声称早期每轮都没有残留。

### 可能追问

- 哪些字段必须由后端产生？
- 为什么不能先用 Mock 数据再忘记替换？
- 空状态的按钮在接口未接入时应该怎样表现？

## PROJECT-04：为什么用 Spring Boot 管理官方 CLI sidecar，而不是每次行情都执行一条命令？

### 项目场景

历史背景：2026-09 CLI 已能读取真实行情，但 Java SDK 浏览器 OAuth 在本机没有跑通，因此选择 sidecar。本地现已改用独立账号的官方 SDK OAuth，云端保持 CLI；授权和四类行情 SDK 探测已通过，证据见 SPEC-002 的 CODE-206I。项目既不能把凭据交给前端，也不能每查一次价格就新建一个 CLI 进程和行情连接。

### 完整回答

> 我把供应商传输细节隔离在 `LongbridgeJsonRpcClient` 后，由 Spring Boot 按需启动并独占一个 `longbridge serve` 子进程。页面仍只调用项目 API；Java 只允许报价、最近 K 线、历史 K 线、当天分时和交易日历五个固定只读方法，通过递增 ID 对应乱序响应，并统一处理限流、短缓存、超时、进程退出和关闭。这样复用了官方 CLI 已经完成的认证和长连接，又没有读取它的私有 Token 文件。代价是部署机必须额外安装 CLI、授权必须属于运行 Spring Boot 的同一系统账号，而且同一 Longbridge 账号不能简单水平启动多个行情副本。这个方案是供应商适配层的实现选择。现在缓存层依赖 `LongbridgeUpstreamGateway`，dev 可选择 `LongbridgeSdkClient`，prod 保留 `LongbridgeJsonRpcClient`；替换传输不需要改业务 API。本地已通过官方 SDK 授权和真实行情探测；登录后的网页与自动刷新仍待验收。

### 项目证据

- `LongbridgeJsonRpcClient` 只开放报价、最近 K 线、历史 K 线、当天分时和交易日历五个方法；
- 客户端仍保留进程内短缓存与限流；其外已增加可配置的 `CachingLongbridgeMarketGateway` 共享缓存装饰层，机制和边界见 [行情缓存设计](../../specs/002-stock-research/design.md#46-redis-共享行情缓存与访问保护)。这不改变 CLI 子进程与账号连接的部署约束；
- 普通回归使用假 sidecar，不依赖真实账号；真实探测显式启用并精确核对进程退出；
- 完整取舍、部署代价和重新评估条件见 [ADR-002](../../architecture/decisions/ADR-002-longbridge-cli-sidecar.md)。

### 可能追问

- sidecar 和微服务有什么区别？
- 为什么不读取 CLI 的 Token 文件直接构造 Java SDK 客户端？
- 单账号单长连接下怎样演进到多实例？

## PROJECT-05：为什么行情缓存使用自写 Redis 锁而没有接入 Redisson？

### 项目场景

同一行情 Key 未命中时，并发请求若全部回源，会消耗 Longbridge 的调用额度。

### 完整回答

> 项目先在同一 Java 进程内合并相同 Key 的请求，再用带 TTL 的 Redis 锁协调连接同一 Redis 实例的多个进程。取得锁的请求二次检查缓存，仍未命中才回源；未取得锁的请求最多等待 800 毫秒，超时允许受控回源；解锁用 Lua 比对 owner 后删除，避免旧请求误删新锁。目前只有这一种只读行情的限时协调用途，Spring Data Redis 已覆盖所需操作，因此没有引入 Redisson。代价是自己维护锁协议；锁到期、Redis 故障或等待超时仍可能重复请求，单 Redis 实例也没有多节点仲裁或故障切换保证，不能说成全局严格执行一次，更没有证据说自写锁更快。如果以后锁要保护不可重复的业务写入或任务耗时难预测，我会重新评估 Redisson，并同时设计业务幂等和数据库约束。

### 项目证据

- [缓存网关](../../../src/main/java/com/stock/system/stock/market/CachingLongbridgeMarketGateway.java) 与 [Redis 锁实现](../../../src/main/java/com/stock/system/stock/market/RedisMarketSharedCache.java)；原理和取舍见 [Redis 工程教学](../../engineering/redis.md#8-当前-redis-锁与-redisson-的关系)。
- [CODE-206E 验收](../../specs/002-stock-research/tasks.md#code-206e-最终验收证据2026-09-18)用两条独立 Lettuce 连接模拟竞争；它不等于多个真实 Longbridge sidecar 已获准并行部署。

### 可能追问

- Redisson 能替代哪些锁细节？哪些回源与降级决策仍要业务代码处理？
- 固定锁 TTL 到期后，旧请求为什么仍可能继续执行？

## PROJECT-06：请沿一次当天分时请求说明 Redis 缓存的命中与未命中

### 完整回答

> 工作台接口先校验用户对空间和股票的访问，再由分时 Provider 选择当天数据分支，调用缓存网关。网关按前缀、股票代码和市场日期生成 Key，先从 Redis 读 JSON：命中就解析出分时点列表返回；未命中则先合并本进程同 Key 请求，再用带 TTL 的 Redis 锁协调加载。取得锁后二次检查缓存，仍为空才调用 Longbridge 客户端（它也可能命中进程内短缓存），非空结果带 TTL 写回；未取得锁的请求等待别的实例写入，超时可受控回源。Redis 故障会被缓存层当作未命中并短暂冷却，不能把“缓存没取到”和“上游没数据”混为一谈。一次完整分时响应还可能读取交易日与前收盘价，不能声称只查一个 Key。

### 项目证据与复习

- [分时接口](../../../src/main/java/com/stock/system/stock/controller/WorkspaceStockController.java)、[缓存网关](../../../src/main/java/com/stock/system/stock/market/CachingLongbridgeMarketGateway.java)；逐层调用与命中／未命中细节见 [Redis 工程教学](../../engineering/redis.md#31-用当天分时串起一次真实请求)。

### 可能追问

- `cache.get(key)` 返回 `Optional.empty()` 时，怎样区分正常未命中与 Redis 连接故障？
- 为什么拿到 Redis 锁后还要再读一次数据 Key？
