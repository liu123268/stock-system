# SPEC-001：研究空间技术设计

状态：技术设计基线已确认；治理已实施，剩余任务见 tasks
更新时间：2026-09-22

## 1. 文档职责

本文档只保存研究空间阶段的实现方案和技术取舍，不重复产品规则。业务依据见 [需求规格](requirements.md)，全项目表结构以 [V1 数据模型](../../architecture/data-model.md) 和 Flyway 迁移为事实来源。

## 2. 当前技术基础

本规格复用认证上下文和空间业务包。实现完成度与证据见 [任务清单](tasks.md)，各数据库版本见 [当前状态](../../project/status.md)。下面的治理 API 已在 `CODE-007` 实施；未完成项仍以 tasks 为准。

## 3. 已确认的迁移基线策略

V1 创建 app_user、super_admin、super_admin_operation_log，V2 创建 workspace、workspace_member、workspace_invitation，按外键依赖顺序建表。最初建立基线时为空库，这是历史前提，不能当成当前开发库可以清空的依据。

唯一治理账号由默认关闭、无公开 HTTP 入口的一次性初始化器建立，不写进 Flyway。后续迁移操作统一遵守 [数据模型的修改要求](../../architecture/data-model.md#9-数据库修改要求)，不用重新申请已经完成的阶段开发授权。

## 4. 本阶段使用的数据关系

```text
app_user ← super_admin.user_id
app_user ← workspace_member → workspace
workspace ← workspace_invitation
super_admin_operation_log：按 action 指向治理目标，无管理员外键
```

- `workspace` 是空间与唯一当前 OWNER 的事实来源，`workspace_member` 保存成员关系，`workspace_invitation` 保存邀请生命周期；`super_admin` 与治理日志位于独立治理边界。
- 字段、状态、唯一约束、外键、索引以及不保留的冗余字段统一查看 [V1 数据模型](../../architecture/data-model.md)，实际已实施结构以 V1、V2 Flyway 迁移为准。
- 本设计只继续说明这些表如何参与空间事务、权限和 API，不复制字段终审过程。

### 4.1 阶段 1 主键、约束与索引

- 阶段 1 使用 MySQL 自增主键、必要的唯一与检查约束、`RESTRICT` 外键和面向现有查询的组合索引；完整清单以 [V1 数据模型](../../architecture/data-model.md#4-阶段-1-约束与索引终审) 与已执行迁移为准。
- 暴露递增 ID 不等于授权。跨表的 OWNER 有效成员关系和 SUPER_ADMIN 身份互斥继续由 Service 事务、锁和集成测试保证。

## 5. API 契约

### 5.1 命名与统一响应

- 命名规范见 [项目结构](../../engineering/project-structure.md#3-后端命名规则)；
- 原 `Result<Integer code, String msg, T data>` 只有 `1/0` 且普通业务异常通常仍返回 HTTP 200，无法稳定区分空间权限、状态和邀请失败；CODE-001 已统一升级为 `Result<String code, String message, T data>`；
- `code` 使用稳定、可读的英文枚举值，`message` 使用可展示的中文说明，`data` 只保存成功结果或必要的失败详情；前端不能通过匹配中文文案判断流程；
- HTTP 状态使用 `200` 查询或动作成功、`201` 创建成功、`400` 请求校验失败、`401` 未认证或账号已禁用、`403` 已认证但无权执行、`404` 资源不存在、`409` 唯一性或业务状态冲突、`500` 未预期错误；
- 统一响应升级属于全局 API 契约，认证接口、测试和前端的 `code === 1` 与 `msg` 读取已同步迁移，不能让新旧接口长期使用两套判断规则。

### 5.2 账号与普通空间 API

WS-036 的新凭据校验在注册 DTO、改密 DTO 和治理初始化器执行，Vue 注册／资料页提供相同输入提示。登录 DTO 及登录页保留已有密码校验范围，原密码继续由 BCrypt `matches` 核对。V11 只替换 `ck_app_user_username`，保留账号唯一约束、主键、密码摘要及凭据版本；不回写 V1，不批量改写既有账号。

账号资料接口属于空间外账号能力，普通用户与唯一治理账号都只能修改自己的资料：`PATCH /api/auth/me` 接收 `UpdateNicknameRequestDTO(nickname)` 并返回最新 `CurrentUserResponseVO`；`POST /api/auth/password` 接收 `ChangePasswordRequestDTO(currentPassword, newPassword)`，成功后返回空数据。原密码错误返回 `CURRENT_PASSWORD_INCORRECT` 400，不把有效会话误判为登录失效。

V8 为 `app_user` 增加从 0 开始的 `credential_version`。登录签发 JWT 时写入该版本；改密事务锁定账号行、核对原密码、更新 BCrypt 摘要并递增版本。JWT 过滤器已在每次请求读取账号状态，同次读取比较版本；不一致返回统一 401。迁移前签发且没有版本声明的 JWT 按 0 处理，首次改密后同样失效。页面成功改密后立即清除当前 Token 和缓存并引导重新登录；昵称修改只更新账号资料，不改变凭据版本。

| 方法与路径                                                        | RequestDTO                                        | ResponseVO                          | 用途                                         |
| ----------------------------------------------------------------- | ------------------------------------------------- | ----------------------------------- | -------------------------------------------- |
| `GET /api/workspaces`                                             | 无                                                | `List<WorkspaceSummaryResponseVO>`  | 查询当前用户的活动与归档空间                 |
| `POST /api/workspaces`                                            | `CreateWorkspaceRequestDTO(name)`                 | `WorkspaceDetailResponseVO`         | 创建空间                                     |
| `POST /api/workspaces/join`                                       | `JoinWorkspaceRequestDTO(inviteCode)`             | `JoinWorkspaceResponseVO`           | 使用邀请码加入或返回已有空间                 |
| `GET /api/workspaces/{workspaceId}`                               | 无                                                | `WorkspaceDetailResponseVO`         | 查看空间详情                                 |
| `PATCH /api/workspaces/{workspaceId}`                             | `RenameWorkspaceRequestDTO(name)`                 | `WorkspaceDetailResponseVO`         | OWNER 修改活动空间名称                       |
| `POST /api/workspaces/{workspaceId}/archive`                      | 无                                                | `WorkspaceDetailResponseVO`         | OWNER 归档空间                               |
| `POST /api/workspaces/{workspaceId}/restore`                      | 无                                                | `WorkspaceDetailResponseVO`         | OWNER 恢复自己归档的空间                     |
| `GET /api/workspaces/{workspaceId}/members`                       | 无                                                | `List<WorkspaceMemberResponseVO>`   | 查看成员                                     |
| `POST /api/workspaces/{workspaceId}/leave`                        | 无                                                | `Void`                              | MEMBER 主动退出                              |
| `DELETE /api/workspaces/{workspaceId}/members/{userId}`           | 无                                                | `Void`                              | OWNER 移除普通成员                           |
| `POST /api/workspaces/{workspaceId}/ownership-transfer`           | `TransferOwnershipRequestDTO(targetUserId)`       | `WorkspaceDetailResponseVO`         | OWNER 转让所有权                             |
| `GET /api/workspaces/{workspaceId}/invitations/current`           | 无                                                | `CurrentInvitationResponseVO`       | OWNER 查看当前邀请及完整代码                 |
| `GET /api/workspaces/{workspaceId}/invitations`                   | 无                                                | `List<InvitationHistoryResponseVO>` | OWNER 查看不含完整代码的邀请历史             |
| `POST /api/workspaces/{workspaceId}/invitations`                  | `CreateInvitationRequestDTO(currentInvitationId)` | `CurrentInvitationResponseVO`       | OWNER 首次生成或基于页面当前版本重新生成邀请 |
| `DELETE /api/workspaces/{workspaceId}/invitations/{invitationId}` | 无                                                | `Void`                              | OWNER 停用指定且仍为当前的邀请               |

创建、改名、归档、恢复和转让返回后端计算后的最新空间 VO，前端不自行猜测状态。改名在事务内锁定空间，核对当前 OWNER 与活动状态，复用创建时的名称清理和全平台唯一约束；同名返回原详情，其他重名返回冲突。`JoinWorkspaceResponseVO` 包含空间摘要和 `newlyJoined`，已经是有效成员时返回 `false` 且不消耗邀请次数。

### 5.3 超级管理员 API

| 方法与路径                                         | RequestDTO/查询参数      | ResponseVO                                        | 用途                       |
| -------------------------------------------------- | ------------------------ | ------------------------------------------------- | -------------------------- |
| `GET /api/admin/users`                             | `AdminUserQueryDTO`      | `PageResponseVO<AdminUserSummaryResponseVO>`      | 分页查询用户               |
| `GET /api/admin/users/{userId}`                    | 无                       | `AdminUserDetailResponseVO`                       | 查看用户基础详情和成员关系 |
| `POST /api/admin/users/{userId}/disable`           | 无                       | `AdminUserDetailResponseVO`                       | 禁用用户                   |
| `POST /api/admin/users/{userId}/restore`           | 无                       | `AdminUserDetailResponseVO`                       | 恢复用户                   |
| `GET /api/admin/workspaces`                        | `AdminWorkspaceQueryDTO` | `PageResponseVO<AdminWorkspaceSummaryResponseVO>` | 分页查询空间               |
| `GET /api/admin/workspaces/{workspaceId}`          | 无                       | `AdminWorkspaceDetailResponseVO`                  | 查看空间治理详情           |
| `POST /api/admin/workspaces/{workspaceId}/archive` | 无                       | `AdminWorkspaceDetailResponseVO`                  | 治理归档活动空间           |
| `POST /api/admin/workspaces/{workspaceId}/restore` | 无                       | `AdminWorkspaceDetailResponseVO`                  | 恢复由超级管理员归档的空间 |
| `GET /api/admin/operation-logs`                    | `OperationLogQueryDTO`   | `PageResponseVO<AdminOperationLogResponseVO>`     | 分页查看治理操作记录       |

超级管理员接口没有物理删除、直接修改成员角色、查看完整邀请码、查看研究内容或重置其他用户密码的路径。

### 5.4 DTO 与 VO 职责

- 写请求使用 `CreateWorkspaceRequestDTO`、`RenameWorkspaceRequestDTO`、`JoinWorkspaceRequestDTO`、`TransferOwnershipRequestDTO`、`CreateInvitationRequestDTO`；操作者、角色、创建时间和状态不接受前端指定；
- 管理列表的页码、每页数量、状态和关键词分别由 `AdminUserQueryDTO`、`AdminWorkspaceQueryDTO`、`OperationLogQueryDTO` 接收；
- `WorkspaceSummaryResponseVO` 用于选择页，`WorkspaceDetailResponseVO` 用于空间页面，`WorkspaceMemberResponseVO` 用于成员列表；
- `CurrentInvitationResponseVO` 可以包含当前完整代码，`InvitationHistoryResponseVO` 在类型层面不定义 `inviteCode`，避免业务代码误返回历史明文；
- 管理员 VO 只包含治理所需的用户、空间、成员身份和操作记录基础字段，不复用包含研究内容的普通空间 VO，也不返回 Entity。

### 5.5 第一批错误码

| 类别 | 错误码                                                                                                                                                                                                   |
| ---- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 通用 | `SUCCESS`、`INVALID_REQUEST`、`UNAUTHENTICATED`、`FORBIDDEN`、`CONCURRENT_OPERATION_CONFLICT`、`INTERNAL_SERVER_ERROR`                                                                                   |
| 账号 | `INVALID_CREDENTIALS`、`ACCOUNT_DISABLED`、`USERNAME_ALREADY_EXISTS`、`USER_NOT_FOUND`、`SUPER_ADMIN_CANNOT_DISABLE_SELF`                                                                                |
| 空间 | `WORKSPACE_NAME_ALREADY_EXISTS`、`WORKSPACE_NOT_FOUND`、`WORKSPACE_ACCESS_DENIED`、`WORKSPACE_MEMBERSHIP_INACTIVE`、`WORKSPACE_ARCHIVED`、`WORKSPACE_ALREADY_ACTIVE`、`SUPER_ADMIN_CANNOT_USE_WORKSPACE` |
| 成员 | `MEMBER_NOT_FOUND`、`MEMBER_NOT_ACTIVE`、`OWNER_CANNOT_LEAVE`、`TRANSFER_TARGET_NOT_ACTIVE_MEMBER`、`REMOVED_MEMBER_REQUIRES_NEW_INVITATION`                                                             |
| 邀请 | `INVITATION_NOT_FOUND`、`INVITATION_CHANGED`、`INVITATION_REVOKED`、`INVITATION_EXPIRED`、`INVITATION_EXHAUSTED`、`INVITATION_ATTEMPTS_LIMITED`                         |

已是有效成员再次使用邀请码属于成功结果，不定义重复加入错误码。每个错误码在实现时绑定唯一的默认 HTTP 状态和中文提示；Controller 和前端使用枚举或类型定义，不在各处复制字符串。

## 6. 预定实现边界

分层与 DTO/VO/Entity 的职责见 [项目结构](../../engineering/project-structure.md)。本阶段 JWT 过滤器从当前数据库状态建立认证身份和 `ROLE_SUPER_ADMIN` Authority，SecurityConfig 对 `/api/admin/**` 统一授权，治理 Service 再使用同一 Authority 做方法级防御；空间 Service 仍按具体业务校验成员和所有者。

## 7. 关键事务

事务、并发和锁由后端与数据库协作完成：Service 使用 Spring `@Transactional` 划定一个业务操作的开始与结束并决定读写顺序，Mapper 执行带 `FOR UPDATE` 的查询；Spring 通过 JDBC 要求 MySQL 提交或回滚，真正保存事务状态、持有行锁、让并发请求等待以及执行回滚的是 InnoDB。前端禁用按钮只能改善体验，不能替代数据库保证。

所有涉及既有空间的写操作统一按 `workspace -> workspace_invitation -> workspace_member` 的顺序取得需要的行锁，减少不同请求反向持锁造成的死锁。数据库仍可能主动中止极端并发事务；此时整项操作回滚并返回 HTTP 409 `CONCURRENT_OPERATION_CONFLICT`，前端刷新最新数据后让用户重试，不盲目自动重放会产生新业务结果的请求。

`WorkspaceAccessService` 集中执行有效普通账号、空间存在、有效成员、OWNER 与活动空间校验，并用不同方法名明确“普通读取”“锁定空间”“锁定且要求活动空间”，避免三个业务 Service 复制校验时悄悄改变错误顺序或 `FOR UPDATE`。邀请码随机短码发生唯一键碰撞时最多重试 10 次，全部用尽后返回 HTTP 409 `CONCURRENT_OPERATION_CONFLICT`，不把数据库异常泄露成 500。空间列表由 Mapper 一次联结当前成员关系并聚合有效成员数，不再逐空间执行成员计数。

### 7.1 创建空间

同一事务内完成：

1. 创建 `workspace`，将当前用户同时写为不可变创建者和当前所有者；
2. 为当前用户创建对应的创建者成员关系；
3. 任一步失败则全部回滚。

并发创建同名空间由 `workspace.name` 唯一约束提供最终保护。V1 不增加通用幂等表；如果创建响应结果不确定，前端先刷新空间列表，再决定是否重试。

### 7.2 转让所有权

同一事务内完成：

1. 锁定或读取目标空间并确认当前用户是当前所有者；
2. 确认目标用户是该空间的有效成员；
3. 将 `workspace.owner_user_id` 更新为目标用户；
4. 原所有者的成员关系保持有效，因此其对外角色自动从 `OWNER` 变为 `MEMBER`；
5. 任一步失败则全部回滚。

成员表不保存重复角色，因此转让不需要同时更新两处 `OWNER` 数据，也不会出现空间表与成员表互相矛盾的所有者。

转让前通过 `SELECT ... FOR UPDATE` 锁定空间行，确认当前所有者和目标有效成员后只更新 `owner_user_id`；并发转让请求按空间行串行执行。选择该方案是因为当前业务始终只有一名 OWNER；若未来出现多个管理员、编辑者或观察者，再重新评估成员角色字段或角色关系表。

转让结果不确定时前端先重新读取空间详情；如果所有者已经是目标用户，说明前一次请求已经成功，不自动再次提交。

### 7.3 使用邀请码加入

同一事务内完成：

1. 先用邀请码做不加锁查询，只取得不可变的邀请 ID 和 `workspace_id`；
2. 按统一顺序锁定空间、再次读取并锁定邀请、再锁定当前用户的成员关系；
3. 在锁内重新校验空间状态、持久化邀请状态、`expires_at` 和 `used_count < 5`，不能相信第一次普通查询的旧状态；
4. 已是有效成员时直接返回已有空间，不消耗次数；
5. 主动退出者可以使用当前有效邀请码恢复；被移除者必须满足邀请码创建时间晚于最近移除时间；
6. 创建或恢复成员关系；
7. 在锁定记录上将 `used_count` 加一；
8. 任一步失败则全部回滚。

同一邀请码的并发请求通过行锁顺序执行，因此第 6 个新成员不能与第 5 个成员同时读到旧次数后越过上限。锁只作用于当前邀请码的短事务，不影响其他空间。

### 7.3.1 邀请码连续错误尝试

`POST /api/workspaces/join` 只对已经认证的普通账号计数，账号 ID 是唯一维度，不使用 IP（家庭或校园网络可能共用出口），也不按邀请码计数（猜测不存在的代码同样要受限）。第一次失败开启固定 15 分钟窗口；窗口内前 5 次错误照常返回原业务错误，第 6 次起统一返回 HTTP 429 `INVITATION_ATTEMPTS_LIMITED`，不再查询邀请码。到期后的下一次尝试开启新窗口；成功加入或用有效代码确认已有成员关系时清零。参数格式不合规则先由 DTO 返回 400，不进入猜码计数。

V9 增加每账号一行的 `invitation_join_attempt`。Service 在加入事务开头对该行执行唯一键插入或更新，再 `FOR UPDATE` 读取，保证同一账号的并发请求按顺序核对额度。尝试先占一个计数位，成功时在同一事务删除；邀请码不存在、停用、过期、用尽、空间归档或被移除成员使用旧码时，成员写入尚未开始，抛专用预期异常并提交计数。其余运行异常仍回滚整个事务，包括成员关系和邀请使用次数，避免半成品。数据库不可用时不放行加入请求；不依赖 Redis 的可用性。账号被删除时计数行由外键级联删除，过期窗口由下一次请求懒重置，因此最多每个有失败尝试的账号一行。

这是一道按账号的基本防线，不能阻止攻击者注册许多账号分散试错，也不代替注册、登录与网络层的整体防滥用。选择现有 MySQL 保存计数，是为了让多实例共享窗口并在服务重启后保留失败次数；代价是新增一张表、一次账号计数行锁和对预期失败的事务提交规则。若将来已有统一的限流基础设施，再按真实负载和部署方式评估替换，不为这一处提前引入新中间件。

### 7.4 成员退出、移除与重新加入

成员生命周期字段及不额外创建事件表的理由见 [数据模型](../../architecture/data-model.md#35-workspace_member-空间成员表)。退出和移除先锁定空间再锁定成员；重复设置相同目标状态返回当前成功结果，不重复写入。REMOVED 不能被退出请求改写为 LEFT，已 LEFT 的成员不能被移除请求改写原因；重新加入的具体业务条件见需求 WS-015。

### 7.5 生成新邀请码

同一事务内完成：

1. 锁定目标空间并验证当前用户是所有者；
2. 比较请求中的 `currentInvitationId` 与锁内读取的当前邀请；首次生成要求两者都为空，重新生成要求两者相等，否则返回 409 `INVITATION_CHANGED`；
3. 将旧的 `ACTIVE` 邀请码更新为 `REVOKED`；
4. 使用安全随机数生成 6 位大写英文字母或数字，发生唯一约束碰撞时重新生成，并通过 `invite_code` 以明文保存；
5. 创建 7 天有效的新邀请记录，`used_count` 初始为 0，固定最多成功加入 5 名成员但不重复保存 `max_uses`；由于成员移除时间与邀请创建时间都以 MySQL `DATETIME(3)` 保存，生成事务在空间锁内读取当前 `REMOVED` 成员最晚的 `last_inactive_at`，必要时把新邀请时间推进 1 毫秒，保证“移除后生成”的严格顺序不会被毫秒截断抹平；
6. 任一步失败则全部回滚。

邀请表保留每次生成的记录。数据库使用只在 `status = ACTIVE` 时产生空间 ID、其他状态产生 `NULL` 的生成列，并对该生成列建立唯一索引，利用 MySQL 唯一索引允许多个 `NULL` 的规则保证同一空间最多一个未撤销邀请。过期和用尽根据 `expires_at` 与固定上限 5 动态判断；显式停用和生成新邀请时写 `REVOKED`。生成新邀请码的事务会先把旧的未撤销记录改为 `REVOKED`，因此自然过期或用尽的旧记录不会阻止新记录插入。

`currentInvitationId` 是轻量并发前置条件：双击或网络重复请求中，只有第一个仍与数据库当前邀请一致，后续请求刷新当前邀请而不会继续生成历史。停用接口使用明确的 `invitationId`，并在锁内确认它仍是当前邀请，防止旧页面误停用新代码。V1 不为此增加通用幂等请求表。

### 7.6 归档与恢复空间

- 所有者主动归档时写 `status = ARCHIVED_BY_OWNER`，超级管理员治理归档时写 `status = ARCHIVED_BY_SUPER_ADMIN`，两者都写入当前 `archived_at`；
- 恢复前直接按状态判断操作人：`ARCHIVED_BY_OWNER` 只能由当前 OWNER 恢复，`ARCHIVED_BY_SUPER_ADMIN` 只能由唯一超级管理员从治理入口恢复；
- 恢复成功后空间改回 `ACTIVE` 并清空 `archived_at`；V1 不保存重复的 `archived_by_user_id` 或 `archive_source`，超级管理员治理历史由 `super_admin_operation_log` 保留；
- V1 不建立通用空间状态事件表。

归档时在同一事务内停用当前未撤销邀请码。归档、恢复、账号禁用与恢复属于“设置目标状态”的操作：重复请求发现已经处于相同目标状态时直接返回当前结果；如果归档来源不匹配则拒绝，不覆盖另一方的治理决定。

### 7.7 超级管理员操作记录

治理成功时，用户或空间状态变化与 `SUCCESS` 操作记录在同一事务提交。只有身份已经确认为 SUPER_ADMIN 后发生的预期业务失败才通过独立日志 Service 的 `REQUIRES_NEW` 事务记录 `FAILED`，避免主事务回滚时一起丢失；普通用户越权、无效 Token 和数据库整体不可用不记为超级管理员治理操作。独立事务通过单独 Spring Bean 调用，不依赖同类方法自调用触发事务代理。

## 8. 接口操作判断原则

业务权限只维护在 [需求规则及矩阵](requirements.md#6-权限矩阵)，本节说明如何映射接口：

- 从认证上下文取得用户，空间 Service 查询目标有效成员后比较 owner_user_id；治理身份在每次请求的 JWT 过滤阶段比较 `super_admin.user_id` 并形成 Authority，不能从请求体或前端 Pinia 信任角色。
- 完全无成员关系和不存在的空间统一 404；已有 LEFT/REMOVED 关系返回 403 WORKSPACE_MEMBERSHIP_INACTIVE，避免向外人暴露资源。
- 每个携带 Bearer Token 的受保护请求都在 JWT 验签后按 `userId` 查询当前 `app_user`；不存在或非 `ACTIVE` 时在过滤器阶段返回 401，治理身份也从当前 `super_admin` 关系读取而不是信任 Token 中的旧角色。`/api/admin/**` 和治理 Service 复用本次请求的 `ROLE_SUPER_ADMIN`，不在入口、事务层反复读取同一身份。账号禁用不级联改变成员或所有权，恢复后原成员关系继续生效。

## 9. 前端当前空间与失效处理

- `CODE-003` 已将空间选择与空间内部页面拆开：`/workspaces` 展示真实列表与创建入口，`/workspaces/:workspaceId/overview` 展示真实详情，旧 `/workspace` 仅兼容跳转；认证 Pinia 保存 Token 和当前用户，空间 Pinia 保存运行期列表与详情；
- 路由使用 `/workspaces` 作为选择页，并使用 `/workspaces/:workspaceId/overview`、`/members`、`/settings` 表达当前空间和页面职责；后续共享自选阶段再接入对应真实页面；
- 当前空间 ID 以 URL 路由参数为唯一来源，不额外写入 localStorage。刷新、前进后退和多标签页分别由各自 URL 保持上下文；Pinia 只缓存本次页面运行中的空间列表与当前空间详情；
- 普通用户登录后查询空间列表：0 个活动空间进入创建/加入引导，1 个活动空间直接进入概览，多个活动空间进入选择页；归档空间单独展示且不计入自动进入判断。SUPER_ADMIN 直接进入 `/admin/users`；
- 创建或加入成功后使用后端返回的 `workspaceId` 进入概览；切换空间先清除 Pinia 当前详情再返回 `/workspaces`，不修改成员关系；退出登录同时清除认证与空间状态；
- 带 `workspaceId` 的路由先验证认证；浏览器刷新后的首次受保护导航通过 `/api/auth/me` 确认会话，同一次页面运行后续导航复用确认结果。页面通过 `ensureCurrent(workspaceId)` 读取真实空间详情：ID 相同复用 Pinia 运行期详情，ID 不同重新请求；加载期间显示明确状态，不能先展示上一空间数据，快速切换时用请求序号丢弃过期响应；
- `401` 清除认证和空间状态并进入登录页；`WORKSPACE_MEMBERSHIP_INACTIVE` 与 `WORKSPACE_NOT_FOUND` 清除当前空间、刷新列表并回到选择页；`WORKSPACE_ARCHIVED` 刷新列表并回到选择页，用户可从归档区域重新进入只读页面；
- 网络失败不等于失去权限，不清除 Token、当前用户或当前空间，保留页面并提供重试；并发冲突先刷新最新状态，再提示用户决定是否重试；
- API 函数显式接收 `workspaceId` 并放入路径，不能依赖隐藏全局变量自动替请求选择空间；后端仍对每次请求验证成员关系。

## 10. 测试与验收设计

### 10.1 测试数据库

执行环境、真实 catalog 核对、写入及清理硬边界只见 [测试规则](../../engineering/testing.md)。以下清单是本阶段设计覆盖目标，已通过范围以 tasks 为准，不表示全部已经完成。

### 10.2 后端测试

以下是阶段验收目标；超级管理员治理和空间改名分别已在 `CODE-007`、`CODE-008` 验收，其余范围以 tasks 为准，不能把未完成设计当成已通过结果。

- MockMvc 集成测试覆盖创建、加入、改名、成员、邀请、转让、归档恢复、超级管理员治理、HTTP 状态与业务错误码；
- 权限测试覆盖 OWNER、MEMBER、非成员、失效成员、SUPER_ADMIN、禁用账号和跨空间访问；
- 事务测试通过可控的测试替身在多步写入中间抛出异常，验证空间与创建者成员、成员与邀请次数、治理状态与成功日志一起回滚；测试替身只存在于测试配置，不在生产代码保留故障开关；
- 并发测试使用多个独立事务和同步起跑点，覆盖 6 人竞争 5 次邀请、并发同名创建、并发转让和并发重新生成邀请，并核对最终数据库状态；
- 账号禁用测试必须证明已经签发的 JWT 在下一次受保护请求时返回 401。

### 10.3 前端测试

- Vitest 与 Vue Test Utils 覆盖 0/1/多个活动空间进入、管理员跳转、URL 空间加载、切换、快速请求竞态、角色按钮、邀请明文、归档只读和错误处理；
- 阶段收尾覆盖前端各层检查；命令与只读验收规则见 [测试规则](../../engineering/testing.md)；
- 网络失败保留当前页面和会话，权限失效清除当前空间，401 清除认证；分别断言，不能用一个通用跳转掩盖所有错误。

### 10.4 浏览器闭环与清理

浏览器覆盖创建、邀请、加入、退出、移除、旧码拒绝、新码重入、转让、归档恢复和治理禁用恢复；检查 0/1/多空间、归档、跨空间 URL、桌面与手机及失败反馈。清理与进程操作只遵守 [测试规则](../../engineering/testing.md)，使用实际本轮测试端口，不操作开发端口上的用户服务。

## 11. 编码顺序与失败处理

唯一任务顺序和授权证据见 [任务清单](tasks.md)，跨阶段调整见 [路线](../../project/roadmap.md)。不在 design 复制一份编码清单。空间基础边界、治理与改名的完成证据见任务清单，剩余个人资料和邀请安全仍按独立任务验收。

失败时不进入依赖步骤；修复迁移遵守数据模型要求，进程和测试数据处理遵守测试规则，Git 保护遵守项目入口。Spring Boot 升级与业务编码分开验证兼容性，不在一个小闭环混做。
