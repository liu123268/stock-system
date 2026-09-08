# 认证与安全面试问答

## SEC-01：为什么使用 BCrypt 而不是 MD5？

### 完整回答

> MD5 计算速度快且不是面向密码存储设计，攻击者可以高效进行字典和暴力尝试。BCrypt 会为每个密码使用随机盐，并提供可调整的计算成本。项目数据库只保存摘要，登录时使用 `matches` 比较原始密码和摘要，不进行解密。它解决的是密码泄露后的离线破解成本问题，不代表系统因此不再需要 HTTPS、登录限制和密钥管理。

### 项目证据

- 注册时使用 `PasswordEncoder.encode`；
- 登录时使用 `matches`；
- Entity 和响应 VO 不向前端返回密码摘要。

## SEC-02：JWT目前在项目中如何使用？

### 完整回答

> 登录成功后，后端签发包含用户 ID、用户名、签发时间和过期时间的 JWT。前端 Pinia 保存登录状态，Axios 拦截器自动添加 `Authorization: Bearer <token>`。后端 `JwtAuthenticationFilter` 验证签名、结构和过期时间，再把最小用户身份写入 Spring Security 上下文。进入受保护路由时，前端调用 `/api/auth/me` 验证会话并查询数据库中的最新用户资料。JWT负载只是编码而不是加密，因此不保存密码和敏感信息，也不保存容易变化的空间角色。

### 可能追问

- 为什么 JWT 中不保存昵称和空间角色？
- JWT 如何主动失效？
- localStorage 和 HttpOnly Cookie 各有什么风险？
- 为什么有 Token 仍然调用 `/api/auth/me`？

### 当前边界

项目尚未实现 Refresh Token、主动吊销和多设备会话管理。

## SEC-03：为什么使用无状态会话？

### 完整回答

> Spring Security 会话策略设置为 `STATELESS`，后端不创建或读取登录 Session。每个受保护请求携带 JWT 并独立验证，服务实例不需要共享 Session。代价是 Token 签发后不能像 Session 一样直接从服务端删除，主动吊销需要短期 Access Token、Refresh Token、黑名单或版本号等额外机制。当前项目规模选择简单的短期访问 Token，并明确记录未实现边界。

## SEC-04：为什么统一401不能只用全局异常处理器？

### 完整回答

> 缺失或非法 JWT 会在 Spring Security 过滤器链中被拒绝，请求尚未进入 Controller，因此 `@RestControllerAdvice` 接不到这类异常。项目通过 `AuthenticationEntryPoint` 返回 HTTP 401 和统一 `Result` JSON。HTTP 状态码服务于浏览器、网关和监控，JSON 结构服务于前端业务提示。

### 可能追问

- 认证失败和权限不足有什么区别？
- 401 与 403 应怎样区分？
- 过滤器异常为什么不进入 ControllerAdvice？
