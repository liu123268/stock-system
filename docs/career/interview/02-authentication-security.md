# 认证与安全面试问答

## SEC-01：为什么使用 BCrypt 而不是 MD5？

### 完整回答

> MD5 计算速度快且不是面向密码存储设计，攻击者可以高效进行字典和暴力尝试。BCrypt 会为每个密码使用随机盐，并提供可调整的计算成本。项目数据库只保存摘要，登录时使用 `matches` 比较原始密码和摘要，不进行解密。它解决的是密码泄露后的离线破解成本问题，不代表系统因此不再需要 HTTPS、登录限制和密钥管理。

### 项目证据

- 注册时使用 `PasswordEncoder.encode`；
- 登录时使用 `matches`；
- Entity 和响应 VO 不向前端返回密码摘要。

### 可能追问：盐能弥补短密码吗？提高门槛后旧账号怎么办？

盐让相同密码得到不同摘要，阻止预计算结果的大规模复用；BCrypt 的成本参数让每次猜测更慢，但二者都不能增加用户密码本身的候选空间。项目把新凭据校验和旧凭据验证分开：注册／改密约束新密码，登录／原密码核对继续兼容历史密码；不批量重置摘要，成功改密才递增凭据版本并作废旧 JWT。当前新密码门槛不代表符合公网认证标准，详见 [认证教学](../../architecture/authentication.md#9-登录账号昵称和密码长度)。

## SEC-02：JWT目前在项目中如何使用？

### 完整回答

> 登录成功后，后端签发包含用户 ID、用户名、凭据版本、签发时间和过期时间的 JWT。前端 Pinia 保存登录状态，Axios 拦截器自动添加 `Authorization: Bearer <token>`。后端 `JwtAuthenticationFilter` 验证签名和有效期，并查询数据库确认账号状态、凭据版本与治理身份，再把当前身份写入 Spring Security 上下文。改密事务核对原密码并递增凭据版本，因此当前和其他设备旧 JWT 在下一次请求时返回 401；昵称修改不改变版本。JWT 负载只是编码，不保存密码或空间角色。

### 可能追问

- 为什么 JWT 中不保存昵称和空间角色？
- JWT 如何主动失效？
- localStorage 和 HttpOnly Cookie 各有什么风险？
- 为什么有 Token 仍然调用 `/api/auth/me`？

### 当前边界

项目尚未实现 Refresh Token、指定设备的独立吊销和多设备会话管理；改密后整体吊销已由凭据版本实现。

UI-08 新增的前端 sessionVersion 只约束迟到响应，不能用它宣称 JWT 安全或全局吊销。浏览器在 A 改密完成后验证旧 JWT 返回 401，换 B 后释放 A 的旧成功响应仍保留 B；这分别验证服务端失效和前端回调归属，具体算法见 [认证设计](../../architecture/authentication.md#ui-08旧回调与当前会话)。

## SEC-03：为什么使用无状态会话？

### 完整回答

> Spring Security 会话策略设置为 `STATELESS`，后端不创建或读取登录 Session。每个受保护请求携带 JWT 并独立验证，服务实例不需要共享 Session。项目在账号表增加凭据版本：改密后版本递增，旧 JWT 虽然签名仍有效，但与数据库版本不符而被拒绝。代价是每次请求要查当前账号，且该机制只能整体失效旧 Token，不能单独管理某台设备。具体边界见 [认证设计](../../architecture/authentication.md)。

## SEC-04：为什么统一401不能只用全局异常处理器？

### 完整回答

> 缺失或非法 JWT 会在 Spring Security 过滤器链中被拒绝，请求尚未进入 Controller，因此 `@RestControllerAdvice` 接不到这类异常。项目通过 `AuthenticationEntryPoint` 返回 HTTP 401 和统一 `Result` JSON。HTTP 状态码服务于浏览器、网关和监控，JSON 结构服务于前端业务提示。

### 可能追问

- 认证失败和权限不足有什么区别？
- 401 与 403 应怎样区分？
- 过滤器异常为什么不进入 ControllerAdvice？

## SEC-05：短邀请码为什么还要限制猜测次数？

这是项目安全收尾的补充案例，实习准备优先讲清认证、权限、事务和测试；不需要为它逐行背诵 SQL。复习时先看 [加入入口](../../../src/main/java/com/stock/system/workspace/service/WorkspaceInvitationService.java) 如何决定成功或错误，再看 [计数服务](../../../src/main/java/com/stock/system/workspace/service/InvitationAttemptService.java) 和 [V9 表](../../../src/main/resources/db/migration/V9__add_invitation_join_attempt.sql) 为什么能跨请求保留次数，最后用 [集成测试](../../../src/test/java/com/stock/system/workspace/InvitationRateLimitIntegrationTest.java) 解释失败提交与并发。

### 完整回答

> 邀请码有随机性，但 6 位短码仍是可提交的加入凭证，不能让已登录账号无限试错。项目按账号记录固定 15 分钟窗口内的失败次数；第 6 次起返回 429，窗口到期或成功使用有效邀请码后恢复。MySQL 的唯一键和行锁让同账号并发尝试依次计数；预期邀请码错误提交计数，成功加入清零，其他异常仍回滚成员与邀请次数。数据库不可用时加入接口不放行。具体事务顺序和测试见 [SPEC-001 设计](../../specs/001-workspace/design.md#731-邀请码连续错误尝试)与 [任务证据](../../specs/001-workspace/tasks.md)。

回答边界：这是按账号限制连续错误的补充保护；批量注册不同账号仍可能分散尝试，不能称为完整的反暴力破解体系，也不应把它当作项目最主要的实习亮点。

### 可能追问

- 为什么按账号而不是按 IP 或邀请码限额？
- 为什么普通 `@Transactional` 回滚会让失败计数失效？
- 怎样证明并发的第 6 次尝试不能越过额度？
