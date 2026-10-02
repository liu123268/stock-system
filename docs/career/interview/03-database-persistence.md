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

> 邀请码最多允许 5 名新成员加入。如果两个请求都先读到 `used_count = 4`，再各自写成 5，就可能出现成员已经增加两人但计数只增加一次的丢失更新。项目在 Spring Service 中声明事务，并按“空间、邀请、成员”顺序执行 `select ... for update`；真正阻止其他数据库事务同时修改同一行的是 MySQL/InnoDB 行锁。InnoDB 的行锁落在索引记录上，合适的索引能精确定位待锁数据；缺少索引时，锁定查询可能因扫描更多记录而扩大锁影响范围。Java 的 `synchronized` 只约束当前 JVM，应用部署多个实例后无法保护另一台机器上的请求。统一锁顺序还能降低不同业务互相反向等待形成死锁的概率。

### 项目证据

- `WorkspaceInvitationService.join` 在同一事务内重新校验邀请、写成员并增加次数；
- Mapper 的 `selectByIdForUpdate`、`selectForUpdate` 使用 `for update`；
- `workspace_member` 的唯一索引 `(workspace_id, user_id)` 支持精确定位某个空间中的成员关系；
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

> ORM 不一定把 Java 对象里的每个字段都写进 `update`。项目恢复空间时执行了 `setArchivedAt(null)`，但 MyBatis-Plus 默认更新策略忽略 null 属性，原 SQL 只把状态改成 `ACTIVE`，没有清空归档时间。数据库检查约束发现“活动空间仍有归档时间”并拒绝写入。修复不是删除约束，而是增加语义明确的 Mapper SQL，在一条语句中同步更新状态和归档时间。这个案例说明排查持久化问题要观察实际 SQL，并让数据库约束保护跨字段不变量。

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

## DB-08：为什么研究时间线要先筛选再分页？

### 完整回答

> 如果接口只返回前 20 条，浏览器再按作者筛选，得到的只是“这 20 条中符合条件的记录”，可能漏掉第 21 条以后的匹配观点。项目先验证空间成员和股票归属，再让 MySQL 对该股票全部周期应用作者、倾向和类型条件，按周期 ID 与观点 ID 的稳定顺序取 20 条，并多取 1 条判断是否还有下一页。下一页携带末条的两个 ID 作为游标，避免页码偏移时前面新增记录造成重复或漏读。详情另取当前活动周期摘要，历史按需加载；一次查询替代原先逐周期查作者和观点的 N+1 读取。当前游标保证追加记录时已有页面的顺序稳定，筛选条件变化或发表／撤销后则从首批重新读取。

### 项目证据

- `StockResearchLifecycleService.detail/timeline` 分别返回当前摘要与服务端筛选的观点批次；
- `WorkspaceStockResearchView` 合并跨页的同一研究周期，并在筛选变化时忽略旧请求；
- `StockResearchIntegrationTest` 检查跨页无重复、筛选先于分页、非成员拒绝和非法游标；浏览器流程验证成员筛选与重新关注后的新旧周期。

### 可能追问

- `limit 20 offset 20` 在前面有新数据写入时可能发生什么？
- 为什么排序字段需要唯一的辅助键？
- 游标分页是否意味着数据库完全不用扫描或排序候选记录？

## DB-09：MySQL 有哪些事务隔离级别？

### 完整回答

> 四种事务隔离级别从低到高是：`read uncommitted`（读未提交），可能发生脏读、不可重复读和幻读；`read committed`（读已提交），避免脏读；`repeatable read`（可重复读），避免脏读和普通快照读中的不可重复读，MySQL/InnoDB 通常默认使用该级别，并结合 MVCC 与锁处理并发读取；`serializable`（串行化）隔离最强，但等待和并发成本也最高。隔离级别属于数据库连接或事务的运行设置，不写在建表语句中。项目的 `@Transactional` 没有显式指定 `isolation`，因此使用 `Isolation.DEFAULT`，即继承当前 MySQL 连接的隔离级别；实际值应使用 `select @@session.transaction_isolation` 核对。

### 可能追问

- 脏读、不可重复读和幻读分别是什么？
- `read committed` 与 `repeatable read` 的 Read View 有什么区别？
- 已经使用 `repeatable read`，为什么检查后修改仍可能需要 `select ... for update`？

## DB-10：redo log、undo log 和 binlog 分别解决什么问题？

### 完整回答

> `undo log` 保存撤销修改和构造旧版本所需的信息，支撑事务回滚与 MVCC；`redo log` 是 InnoDB 的重做日志，先记录数据页修改，崩溃恢复时重放已提交变化，是持久性的核心机制；`binlog` 属于 MySQL Server 层，记录已提交的数据变更，主要用于复制和基于备份的时间点恢复，不能代替 undo 回滚或 redo 崩溃恢复。项目邀请码加入在同一事务中修改成员和使用次数：失败时依靠事务与 undo 撤销，提交后依靠 redo 保证崩溃恢复；若数据库启用 binlog，提交变更还会进入 binlog。redo 与 binlog 由 MySQL 的提交协议协调，应用代码不直接写这三种日志。

### 可能追问

- 为什么提交事务时不直接等待所有数据页写回磁盘？
- redo log 与 binlog 为什么不能互相替代？
- ACID 的原子性、隔离性和持久性分别由哪些机制共同保证？

## DB-11：数据库用 UTF-8，为什么中文搜索仍报字符集错误？

### 完整回答

> 数据库默认字符集不覆盖每个列的字符集。项目账号列使用 ASCII 保存标准登录身份，搜索同时匹配中文昵称；`locate` 在账号分支按表达式规则尝试把中文参数转成 ASCII，出现 MySQL 3854。修复只把比较表达式转换为 utf8mb4，存储约束保留；还需明确 utf8mb4_bin，否则默认不区分大小写的排序规则会改变原账号匹配。列表和 `count` 查询必须一致。参数绑定避免 SQL 注入，不解决字符集比较问题；`or` 也不能保证不计算另一分支。

### 项目证据与追问

真实 MySQL／MockMvc 覆盖中文、emoji、英文账号、字面百分号、无匹配和大写账号边界；根因和 SQL 只见 [BUG-004 的治理搜索补充](../../engineering/bugs/BUG-004-stock-search-character-set.md#ui-09-补充账号与昵称联合搜索2026-10-01)。可追问字符集与 collation 分别负责什么、为什么不直接改账号列、`locate` 是否保证使用索引；本修复没有性能收益结论。

## DB-12：索引为什么能加速查询，怎样判断一条 SQL 是否真正用了索引？

### 完整回答

> InnoDB 的索引通常使用 B+ 树。声明为主键的列同时构成主键索引，它的叶子节点保存整行数据，所以主键索引也是聚簇索引；二级索引的叶子节点主要保存索引列和主键值。先查二级索引再到主键索引取其他列叫回表；查询需要的列都能从同一索引得到叫覆盖索引。联合索引按定义顺序排序，能否有效使用要结合最左前缀、查询条件和排序，而不是因为 SQL 同时写了两个字段就一定高效。最终是否走索引由优化器根据统计信息和成本选择，应使用 `explain` 看 `type`、`key`、`rows` 和 `extra`，再结合真实数据量、耗时和调用频率决定是否修改。项目股票精确代码查询可利用 `code` 唯一索引；综合搜索含函数、多个 `or` 和子串匹配，实测约 8318 行时为全表扫描，但本机延迟仍可接受，所以没有为了“看起来用了索引”给拼音列盲目加索引。

### 项目证据

- `workspace_member` 的唯一联合索引 `(workspace_id, user_id)` 同时保证关系唯一，并支持按空间和用户精确定位；
- `stock` 的主键索引保存行数据，`code` 唯一二级索引用于精确代码定位；
- 股票综合搜索的 `explain` 结果和暂不新增拼音索引的依据见 [数据模型](../../architecture/data-model.md#37-stock-股票基础信息表)。

### 可能追问

- 为什么联合索引 `(a, b)` 通常能支持只查 `a`，却不一定适合只查 `b`？
- 回表与覆盖索引有什么区别？
- `limit 30` 为什么不代表数据库只检查 30 行？
- `explain` 显示全表扫描时，为什么不一定需要立刻加索引？

## DB-13：股票目录为什么分批写入，批处理和事务有什么区别？

### 完整回答

> 逐行向数据库发送 8318 次请求会产生大量往返开销。项目先校验完整目录，再用 `PreparedStatement` 绑定参数，通过 `addBatch()` 累积，每 500 行执行一次 `executeBatch()`，最后处理不足 500 行的余数。500 行只是一次发送多少 SQL，不是每 500 行提交一次事务；调用方关闭自动提交，全部批次成功后统一 `commit`，任一批失败则统一 `rollback`。`on duplicate key update` 利用股票代码唯一约束实现幂等导入，重复执行会更新允许变化的目录字段并保留同一股票身份。测试模式会重复导入并回滚，正式应用模式才提交。

### 项目证据

- `StockDirectoryImporter.importRows` 负责全量校验、参数绑定和每 500 行执行批处理；
- `scripts/ImportStockDirectory.java` 负责 catalog 核对、事务提交／回滚和重复导入验证；
- 测试库验证 8318 行导入、重复导入与回滚后仍为 0，实施证据见 [SPEC-002 tasks](../../specs/002-stock-research/tasks.md#code-206a-目录实施证据2026-09-08)。

### 可能追问

- `PreparedStatement` 除了批处理，还怎样降低 SQL 注入风险？
- 批次大小为什么不能无限增大？
- 为什么幂等导入还需要事务和输入校验？
