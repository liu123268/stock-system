# 共享自选统计的 SQL 性能对照

验证日期：2026-10-23。本报告只描述本机 MySQL 上共享自选列表的一次查询优化；实验数据全部为 `stock_system_test` 中的自造 fixture，未读取生产业务或行情数据。

## 1. 问题与改动

`GET /api/workspaces/{workspaceId}/stocks` 经 Service 校验空间访问权限后，调用 [WorkspaceStockMapper.selectActiveList](../../src/main/java/com/stock/system/stock/mapper/WorkspaceStockMapper.java)。查询一次返回股票、活动周期、最近未撤回观点，以及本期观点数和参与人数。

原统计子查询先处理所有研究周期，再由外层联表取当前空间。即使页面只展示当前空间十只股票，其他空间增加观点也会增加这个统计分支的扫描量。

```sql
select research_cycle_id, count(*), count(distinct author_user_id)
from stock_opinion
group by research_cycle_id;
```

改为在统计分支内部先联接周期和空间股票，只聚合当前空间的活动周期：

```sql
select opinion.research_cycle_id,
       count(*) as opinion_count,
       count(distinct opinion.author_user_id) as participant_count
from stock_opinion opinion
join research_cycle cycle on cycle.id = opinion.research_cycle_id
join workspace_stock stock on stock.id = cycle.workspace_stock_id
where stock.workspace_id = ?
  and cycle.status = 'ACTIVE'
group by opinion.research_cycle_id;
```

上面是聚合分支的简化示例。完整旧 SQL 固定在 [对照基线](../../src/test/resources/performance/workspace-stock-list-before.sql)，当前 SQL 从 MyBatis 实际注册的 `MappedStatement` 取得，不另写一份候选 SQL 来代替生产映射。

基线资源直接使用 JDBC 的 `?` 占位符，读取后由 `PreparedStatement` 绑定一个 `workspaceId`。Mapper 注解中的 `#{workspaceId}` 则先由 MyBatis 转为 JDBC 占位符，机制见 [官方参数说明](https://mybatis.org/mybatis-3/sqlmap-xml.html#Parameters)。普通 `.sql` 资源不经过 MyBatis，不能依靠 Java 字符串替换让文件中的模板语法在运行时才变成有效 SQL；两层的参数形式应与实际执行入口对应。

业务语义保持：最近观点仍排除撤回并按时间／ID 稳定选择；观点数和参与人数继续包含本周期保留的撤回审计记录；历史周期和其他空间不混入当前统计。排序、返回字段和权限入口保持原职责。统计仍在一条 Mapper SQL 内完成，使用已有索引，本轮没有数据库结构迁移。

## 2. 实验条件与方法

本机实际环境为 MySQL `8.0.34`、Java `17.0.15`、Windows amd64；查询得到会话隔离级别 `REPEATABLE-READ`，InnoDB Buffer Pool 为 `128 MiB`。这些是该次连接的现场值，不能由 `Isolation.DEFAULT` 或机器型号推断生产配置。

固定当前空间十只股票、十个活动周期、每周期 200 条观点，共 2000 条；再放入 400 条已结束／已取消历史观点。作者有重复、最新观点包含撤回、创建时间包含并列，用于核对去重人数、最近有效观点和排序。其他空间的观点分三档增加，当前空间始终相同：

| 其他空间观点 | fixture 总观点 | 当前活动周期观点 |
| ---: | ---: | ---: |
| 0 | 2400 | 2000 |
| 10000 | 12400 | 2000 |
| 100000 | 102400 | 2000 |

每档提交 fixture 后执行 `analyze table` 更新测试表统计；同一连接先预热五对查询，再测三十对，按 AB／BA 交替执行。每一次结果都与旧查询的十行全字段、有序结果对比，断言在计时结束后执行。耗时包含 JDBC prepare、参数绑定、发送、完整读取、结果映射和关闭语句，不包含连接池取连接、JWT、Service、HTTP 或页面渲染。p50／p95 使用最近秩定义，保留原始样本。

测试没有清空缓存，也没有并发用户。以下是本机串行、预热后的小样本 JDBC 数据，不能推算生产吞吐量、整页耗时或所有 SQL 的表现。

## 3. 最终独立运行结果

最终样本与执行计划在 `target/sql-list-performance-20261023-final/`，日志 `target/sql-list-performance-20261023-final.log`。这是最终清理与统计恢复逻辑下重新执行的完整三档对照，不把首轮样本拼接成六十对。

| 其他空间观点 | 原查询 p50/ms | 当前查询 p50/ms | 原查询 p95/ms | 当前查询 p95/ms |
| ---: | ---: | ---: | ---: | ---: |
| 0 | 7.092 | 7.391 | 9.686 | 10.066 |
| 10000 | 14.682 | 6.919 | 21.345 | 10.407 |
| 100000 | 80.430 | 7.679 | 96.673 | 10.312 |

十万条其他空间观点的样本中，p50 降低约 **90.5%**。零背景数据时，新查询 p50 高约 0.299 ms，未观察到小数据收益。此次改动控制的是其他空间数据增长造成的聚合开销，当前空间自身观点增多仍会增加工作量。

结果始终为同样的十行，字段与顺序全部一致。原、新 SQL SHA-256 分别为 `d637285cb5373d64d616eb2b4bf1cdd614418d2bf61395f0083c8f595e2bb774`、`c49ec33b5a951c2c1276746b9f3e21a7d3e08bc7e2d9a04c9a7e9b804dd79154`。首轮 `target/sql-list-performance-20261023/` 原样保留，用于追溯，最终表只采用上述最终独立运行。

### 执行计划怎样解释收益

`explain analyze` 会实际执行查询，并给出各迭代器的时间、行数和循环次数，含义沿 [MySQL 官方说明](https://dev.mysql.com/doc/refman/8.0/en/explain.html#explain-analyze)。最终十万背景档的聚合输入分支为：

```text
原查询：Index scan on stock_opinion using idx_stock_opinion_cycle_created
        actual rows=102400 loops=1
新查询：Index lookup on scoped_opinion using idx_stock_opinion_cycle_created
        actual rows=200 loops=10
```

统计分支由读取 102400 条缩小为十个周期各 200 条，即 2000 条。两者都使用同一个已有索引，区别是原查询扫描整段索引，新查询沿周期定位较小范围；“key 不为空”不足以证明扫描量合理。这里是统计分支的访问量，不是整条联表 SQL 的总扫描量。

当前计划仍对范围内的 2000 条做分组排序，最终列表也保留业务排序；最近观点分支继续按原语义读取。没有把减少一处分支扫描写成消除了所有排序或完成了整站优化。完整估计计划和实际计划分别保存为 `*-plan.json`、`*-analyze.txt`。

## 4. 验证与复现

[WorkspaceStockListQueryIntegrationTest](../../src/test/java/com/stock/system/stock/WorkspaceStockListQueryIntegrationTest.java) 的普通回归检查全字段等价、去重人数、撤回、历史排除、排序、空空间及真实 Mapper 到 VO 的映射；还构造“提交 fixture、执行 analyze、随后抛异常”，验证 finally 清理和统计恢复。规模实验默认跳过，不让普通回归自动生成十万条背景数据。

本轮最终普通后端全量及打包 **528 项：514 通过、14 条件跳过，失败／错误 0**，日志 `target/sql-list-backend-full-package.log`。十四条包括原十三条条件跳过项和本实验的显式开关；随后显式启用规模实验，独立 **1 项通过**。这两次命令分别计数。没有前端源码变更，未重跑前端／浏览器流程或收费题集。

需要复现实验时，在项目根目录、已提供 `DB_PASSWORD`／`JWT_SECRET` 且依赖已缓存的 PowerShell 中运行：

```powershell
$previousSqlProbe = $env:STOCK_SQL_LIST_PERFORMANCE_PROBE
try {
    $env:STOCK_SQL_LIST_PERFORMANCE_PROBE = 'true'
    mvn -o -B -s scripts/agent-compatibility-maven-settings.xml `
        '-Dmaven.repo.local=D:\Java\stockweb\.m2repo' `
        '-Dtest=WorkspaceStockListQueryIntegrationTest' test
} finally {
    if ($null -eq $previousSqlProbe) {
        Remove-Item Env:STOCK_SQL_LIST_PERFORMANCE_PROBE -ErrorAction SilentlyContinue
    } else {
        $env:STOCK_SQL_LIST_PERFORMANCE_PROBE = $previousSqlProbe
    }
}
```

开关只启用测试类中的规模实验，最终恢复当前终端原值。默认输出到新的 `target/sql-list-performance-<时间戳>/`；需要固定路径时可加 `-Dstock.sql-list-performance.output=target/<本轮新目录>`，已有目录会被拒绝。成功应看到该类三项通过、`results.json` 的 `passed=true`、七表 cleanup 为 0 及 `statisticsRestored=true`，也要检查 Maven 退出结果。

大规模入口检查实际 catalog 为 `stock_system_test`、业务空间为空，并用连接级命名锁排除另一轮同类实验；执行时须独占测试库，不能和其他数据库测试并行。所有数据带本轮随机标识并保存生成 ID，finally 按外键顺序精确删除、核对七表为 0，随后重新统计测试表。`analyze table` 具有 [隐式提交行为](https://dev.mysql.com/doc/refman/8.0/en/implicit-commit.html)，所以这里采用已提交 fixture 的显式清理，不能假称整体靠事务回滚。默认开发库与生产数据不参与实验，测试库 Flyway 保持 V16。

本轮没有外部模型／行情请求，原累计费用账本未变。数据与进程的最终审计入口见 [CODE-212](../specs/002-stock-research/tasks.md#code-212共享自选统计-sql-优化2026-10-23)。个人独立分析与面试表达仍待核对，本报告只记录工程证据。
