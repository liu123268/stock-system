# 数据库与持久化面试问答

## DB-01：为什么 DTO、VO、Entity 要分开？

### 完整回答

> RequestDTO 描述前端允许提交的数据，ResponseVO 描述允许返回的数据，Entity 对应数据库记录。注册时用户只能提交用户名、密码和昵称，不能控制 ID、状态、创建时间和密码摘要；响应时也不会暴露 `passwordHash`。分离后可以缩小接口攻击面，让参数校验和接口文档更清楚，并避免数据库字段变化直接破坏前端契约。代价是对象和转换代码增加，但对认证、权限和长期维护是值得的。

### 项目证据

- 注册与登录 RequestDTO；
- 当前用户和认证 ResponseVO；
- `AppUser` Entity 不直接作为 Controller 响应。

## DB-02：为什么使用数据库行锁，不能只在 Java 里加锁？

### 完整回答

> 邀请码最多允许 5 名新成员加入。如果两个请求都先读到 `used_count = 4`，再各自写成 5，就可能出现成员已经增加两人但计数只增加一次的丢失更新。项目在 Spring Service 中声明事务，并按“空间、邀请、成员”顺序执行 `SELECT ... FOR UPDATE`；真正阻止其他数据库事务同时修改同一行的是 MySQL/InnoDB 行锁。Java 的 `synchronized` 只约束当前 JVM，应用部署多个实例后无法保护另一台机器上的请求。统一锁顺序还能降低不同业务互相反向等待形成死锁的概率。

### 项目证据

- `WorkspaceInvitationService.join` 在同一事务内重新校验邀请、写成员并增加次数；
- Mapper 的 `selectByIdForUpdate`、`selectForUpdate` 使用 `FOR UPDATE`；
- 6 个线程通过 `CyclicBarrier` 同时竞争 5 次额度，结果为 5 次成功、1 次 `INVITATION_EXHAUSTED`，数据库最终计数为 5。

### 可能追问

- Spring 事务和数据库事务分别负责什么？
- 为什么加锁后还要在锁内重新读取和校验？
- 行锁是不是一定不会发生死锁？

## DB-03：为什么成员退出后不删除记录，重新加入也不新增记录？

### 完整回答

> 同一用户和空间只有一条 `workspace_member`，唯一约束是 `(workspace_id, user_id)`。退出把状态改为 `LEFT`，OWNER 移除改为 `REMOVED`，合法重新加入恢复为 `ACTIVE`，同时保留首次加入、最近加入和最近失效时间。这样既不会出现重复成员，也能区分主动退出和被移除，并判断被移除者使用的邀请码是否在移除后生成。物理删除会丢失这些判断依据，将来也可能让历史观点无法清楚追溯成员关系。

### 项目证据

- `WorkspaceMemberService` 的退出和移除事务；
- `WorkspaceInvitationService.join` 对 `LEFT/REMOVED` 的恢复；
- 集成测试验证重新加入前后成员记录 ID 与首次加入时间不变。

## DB-04：为什么对象字段设为 null，数据库列却可能没有被清空？

### 完整回答

> ORM 不一定把 Java 对象里的每个字段都写进 UPDATE。项目恢复空间时执行了 `setArchivedAt(null)`，但 MyBatis-Plus 默认更新策略忽略 null 属性，原 SQL 只把状态改成 ACTIVE，没有清空归档时间。数据库检查约束发现“活动空间仍有归档时间”并拒绝写入。修复不是删除约束，而是增加语义明确的 Mapper SQL，在一条语句中同步更新状态和归档时间。这个案例说明排查持久化问题要观察实际 SQL，并让数据库约束保护跨字段不变量。

### 项目证据

- `WorkspaceMapper.updateArchiveState` 显式更新 `status` 与 `archived_at`；
- `WorkspaceService.archive/restore` 在事务内锁定空间并调用该更新；
- 集成测试验证归档时间写入、恢复清空，以及按归档来源拒绝错误恢复。

### 可能追问

- MyBatis-Plus 的字段更新策略是什么？
- 为什么不用把所有字段都配置成总是更新？
- Service 事务和数据库 CHECK 约束分别解决什么问题？

## DB-05：有 test profile，为什么浏览器测试仍可能污染开发库？

### 完整回答

> test profile 只是配置选择，不是运行后端实际连接目标的证据。项目曾出现浏览器请求发给 8082 上的开发后端，而清理脚本根据测试配置去清理 `stock_system_test` 的情况，于是测试数据留在 `stock_system`。修复后开发和测试使用不同 profile 与端口；test 后端启动时从 JDBC 连接读取 catalog，不是 `stock_system_test` 就拒绝启动；浏览器在第一次注册前还访问只存在于 test profile 的环境接口，二次确认运行中后端的真实库名。最后才是唯一标识和 finally 清理。核心原则是写入前验证实际连接，不能把事后清理当成写入隔离。

### 项目证据

- `application-dev.properties`、`application-test.properties` 与两套启动脚本；
- `TestDatabaseSafetyGuard` 的启动期 JDBC catalog 检查；
- `TestEnvironmentController` 与浏览器脚本写入前检查；
- [BUG-003](../../engineering/bugs/BUG-003-browser-test-database-boundary.md#5-数据处理与验证) 的残留识别、精确清理和当次测试证据。

### 可能追问

- 为什么测试库执行后仍然是空的？
- profile、端口隔离和真实 JDBC catalog 检查分别解决什么问题？
- 为什么不能让浏览器测试写开发库后再删？

## DB-06：为什么严格的时间先后判断仍会偶发出错？

### 完整回答

> 被移除成员只能使用移除后生成的新邀请码，代码比较的是邀请 `created_at` 是否严格晚于成员 `last_inactive_at`。Java 时间有更高精度，但两个 MySQL 字段都是 `DATETIME(3)`；移除和立即生成如果发生在同一毫秒，入库后会变成相等，于是真正的新码被误判为旧码。不能把比较放宽为“大于等于”，因为移除前旧码也可能与移除时间同毫秒。项目保留严格比较，并利用两项操作已有的空间行锁顺序：生成新码时读取当前被移除成员的最晚失效时间，把候选时间截断到毫秒，必要时推进 1 毫秒。这样数据库中的时间重新具备可判定的严格顺序。

### 项目证据

- `WorkspaceMemberMapper.selectLatestRemovedAt` 查询当前空间的移除边界；
- `WorkspaceInvitationService.nextInvitationCreatedAt` 对齐数据库精度并保证严格递增；
- 集成测试固定“旧邀请时间等于移除时间”，验证旧码仍拒绝、新码严格更晚且可以重入；
- [BUG-005](../../engineering/bugs/BUG-005-invitation-millisecond-boundary.md) 保存复现、根因和完整修复链路。

### 可能追问

- 为什么 `LocalDateTime.now()` 的调用顺序不能直接当数据库顺序？
- 为什么使用已有空间行锁后，查询最晚移除时间才不会和并发移除竞争？
- 如果未来要保存完整成员事件历史，怎样用事件 ID 或版本号替代时间边界？

## DB-07：做近期动态时，哪些事实需要另存事件？

### 完整回答

> 先看现有表能否还原“谁在什么时候做了什么”。研究周期的开始／结束时间、观点的发表／撤销时间已经存在；概览可按当前空间批量查询并合并这些事实。反复把关注级别从普通切到重点、再切回来后，`research_cycle.attention_level` 只剩最终值，旧切换无法倒推，所以 V10 仅为级别切换新增 `research_attention_change`，在更新周期的同一事务里写入。这样既能展示真实历史，也不复制观点正文。动态中的已撤销观点只保留占位，不返回旧倾向或理由。完整来源、权限和排序见 [SPEC-002 设计](../../specs/002-stock-research/design.md#64-已确认-v1-能力的设计入口与落地)。

### 可能追问

- 为什么不能把每种操作都写成一份带正文的活动 JSON？
- 如果当前周期更新成功、级别变更插入失败，事务应怎样处理？
- V10 前已经发生的级别切换能否准确补回？
