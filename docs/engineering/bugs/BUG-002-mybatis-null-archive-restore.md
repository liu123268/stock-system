# BUG-002：恢复空间时归档时间未被清空

状态：已解决

发现与修复时间：2026-09-04

## 1. 问题现象

OWNER 归档空间成功后调用恢复接口，Service 已把状态设置为 `ACTIVE` 并执行 `setArchivedAt(null)`，但数据库返回 `ck_workspace_archive_time` 检查约束冲突，接口最终为 HTTP 500。

## 2. 影响范围

只影响普通 OWNER 恢复自己归档空间的写入语句，不影响归档、空间读取、成员退出或其他业务规则。

## 3. 根本原因

MyBatis-Plus 的默认字段更新策略会忽略值为 `null` 的属性。`updateById(workspace)` 生成的 SQL 更新了 `status = ACTIVE`，却没有生成 `archived_at = NULL`。数据库因此看见“活动状态仍带归档时间”的不合法组合，并由检查约束拒绝写入。

## 4. 解决方案

在 `WorkspaceMapper` 增加语义明确的 `updateArchiveState`，用一条 SQL 同时更新 `status` 和 `archived_at`。归档传入当前时间，恢复明确传入 `NULL`。没有修改已执行迁移，也没有放宽数据库约束。

## 5. 验证

- 定向 `WorkspaceIntegrationTest` 9 项通过；
- 完整后端 23 项测试通过；
- 集成测试证明归档返回 `ARCHIVED_BY_OWNER` 和时间，恢复返回 `ACTIVE` 且归档时间为空；
- 真实浏览器完成归档、只读重入和恢复。

## 6. 预防措施

- 更新为 `null` 具有业务含义时，不要只看 Java 对象，要检查 ORM 实际生成的 SQL；
- 对需要同步变化的状态字段使用一个明确的持久化操作；
- 保留数据库检查约束，让遗漏字段在测试阶段失败，而不是沉默保存矛盾状态。

## 7. 可转化的面试问题

- 为什么 Java 中 `setXxx(null)` 不一定会生成 `SET xxx = NULL`？
- ORM 字段更新策略和数据库检查约束分别承担什么职责？
- 为什么本次修复选择显式 Mapper SQL，而不是删除检查约束？
