# Spring Security 与 JWT 认证链路

更新时间：2026-09-17

这份文档解释项目中的“登录之后，后端如何知道请求是谁发来的”。重点不是背类名，而是理解一次请求经过哪些环节，以及每个类为什么只做一件事。

## 1. 登录和认证不是同一件事

登录接口只发生一次：用户名和密码验证成功后，后端签发 JWT。之后访问 `/api/auth/me`、工作空间和自选股接口时，前端不会反复发送密码，而是在请求头中携带 JWT：

```text
Authorization: Bearer <JWT>
```

后续每个请求仍然需要认证。认证的含义是：验证 Token 的签名和过期时间，并从中恢复当前用户身份。

```text
登录：用户名 + 密码 -> 校验 BCrypt -> 签发 JWT
后续请求：JWT -> 验证签名和有效期 -> 建立当前用户身份 -> 访问业务接口
```

## 2. 一次受保护请求的数据流

以 `GET /api/auth/me` 为例：

```text
Vue 页面
  -> Axios 请求拦截器读取 localStorage 中的 JWT
  -> 添加 Authorization: Bearer <JWT>
  -> Vite 把 /api 请求代理到 Spring Boot 8082
  -> JwtAuthenticationFilter 解析并验证 JWT
  -> 把 LoginUser 放入 SecurityContext
  -> SecurityConfig 确认该请求已经认证
  -> AuthController 通过 @AuthenticationPrincipal 取得当前用户
  -> AuthService 调用 UserService 按用户 ID 查询数据库中的最新公开资料
  -> Result<CurrentUserResponseVO> 返回 Vue
```

这里有两个容易混淆的对象：

- JWT 中的身份只包含用户 ID 和用户名，用于证明请求是谁发出的。
- `/api/auth/me` 返回的昵称来自数据库，因为它可能修改，不适合一直使用 Token 中的旧值。V1 已从 Entity、VO 和前端类型中移除头像字段，以后真正实现头像时再新增迁移和接口字段。

## 3. SecurityConfig 负责什么

`SecurityConfig` 是安全规则的总入口，但它不负责解析 Token。当前配置完成了以下工作：

1. 注册和登录接口公开访问，否则新用户连 Token 都无法取得。
2. 其他接口默认必须认证，采用“默认拒绝”的安全策略。
3. 会话策略设为 `STATELESS`，后端不保存登录 Session，每次请求都验证 JWT。
4. 关闭表单登录和 HTTP Basic，避免出现项目没有使用的默认认证方式。
5. 把 JWT 过滤器放到用户名密码过滤器之前。
6. 指定统一的 401 JSON 处理器。

CSRF 也被关闭。原因不是“CSRF 不重要”，而是当前 API 不依赖浏览器自动携带的 Cookie Session，而是使用请求头中的 Bearer Token。将来如果改为 Cookie 保存认证信息，就必须重新评估 CSRF 防护。

## 4. JwtAuthenticationFilter 负责什么

过滤器继承 `OncePerRequestFilter`，保证一次请求分派只执行一次。它的流程是：

1. 读取 `Authorization` 请求头。
2. 没有 Bearer Token 时继续执行，由后面的授权规则判断公开或拒绝。
3. 有 Token 时验证签名、结构和过期时间。
4. 读取 `userId` 和 `username`，构造最小身份对象 `LoginUser`。
5. 创建 `UsernamePasswordAuthenticationToken` 并写入 `SecurityContextHolder`。
6. Token 非法时清空安全上下文，并交给统一 401 处理器。

`SecurityContext` 可以理解为当前请求线程的“身份卡槽”。过滤器验证成功后把身份放进去，Controller 才能通过 `@AuthenticationPrincipal` 安全地取得当前用户，而不是相信前端自己提交的 `userId`。

不能让页面在请求参数中传“我要查询用户 9 的资料”来代表当前用户，因为攻击者可以把 9 改成其他 ID。当前用户必须来自已经验证签名的 Token。

## 5. 为什么 401 不由 GlobalExceptionHandler 处理

`GlobalExceptionHandler` 处理的是进入 Controller 后抛出的业务异常和参数校验异常。缺少或伪造 JWT 时，请求在 Spring Security 过滤器链阶段就被拒绝，还没有进入 Controller，所以 Controller 层的异常处理器接不到。

当前代码使用 `RestAuthenticationEntryPoint` 输出统一响应：

```json
{
  "code": "UNAUTHENTICATED",
  "message": "未登录或登录已过期",
  "data": null
}
```

同时 HTTP 状态码是真正的 `401 Unauthorized`。HTTP 状态码便于浏览器、网关和监控识别错误类型；稳定字符串 `code` 供前端判断流程，中文 `message` 负责展示，`data` 保存成功结果。注册成功返回 201，参数错误返回 400，错误凭据与账号停用返回 401，重复账号返回 409。

## 6. 前端如何验证保存的登录状态

Pinia 中有 Token 不等于 Token 仍然有效。Token 可能已过期、被篡改，或者对应账号已不可用。因此浏览器刷新后，第一次进入受保护路由时，路由守卫会调用 `/api/auth/me`：

- 成功：刷新 Pinia 中的当前用户资料并进入工作台。
- 后端明确返回 HTTP 401、`UNAUTHENTICATED` 或 `ACCOUNT_DISABLED`：清除 localStorage、Pinia 会话及空间缓存，携带原目标地址返回登录页。
- 断网、超时或服务暂不可用：不能证明 JWT 已经失效，因此保留 Token、当前用户和空间缓存，继续进入原目标页，由页面已有的加载失败状态展示错误和重试入口。

登录接口刚成功时，后端已经验证账号并返回当前用户，因此本次页面运行直接标记为已确认；从 localStorage 恢复的 Token 则不能获得这个标记，必须先经过 `/api/auth/me`。确认成功后，同一次页面运行中的后续受保护路由复用这个内存状态，不为概览、共享自选、成员和设置的每次切换重复请求 `/api/auth/me`。刷新浏览器会重新创建 Pinia，确认状态随之归零；退出登录或明确的认证失败也会清除它。

这项分类和本次运行确认发生在 [router/index.ts](../../frontend/src/router/index.ts) 的认证路由守卫与 `authStore` 中。它改善的是前端体验，不会放宽后端权限：每个业务请求仍携带 JWT、经过 Spring Security，并在空间业务中继续校验有效账号与成员关系；攻击者也不能依靠前端缓存绕过后端。单元测试分别证明网络失败保留会话、401 清除会话、刷新恢复的本地状态仍需验证，以及成功验证后不在子页面切换中重复调用。

Axios 请求拦截器只负责自动添加请求头，不负责判断 Token 是否有效。有效性必须由掌握 JWT 密钥的后端判断。

## 7. 自动化测试覆盖了什么

后端集成测试使用 MockMvc 经过真实 Spring Security 过滤器链，覆盖：

- 注册、参数校验和 BCrypt 摘要。
- 正确登录、错误密码和 JWT 内容。
- 没有 Token 时返回统一 401。
- 伪造 Token 时返回统一 401。
- 合法 Token 可以访问 `/api/auth/me`，且响应不暴露密码摘要。
- 注册账号统一转成小写，大小写不同的重复账号返回 HTTP 409 与 `USERNAME_ALREADY_EXISTS`。
- 参数错误返回 HTTP 400，错误凭据、账号停用和未认证返回 HTTP 401，并分别携带稳定错误码。

浏览器冒烟脚本使用真实 Chrome，覆盖 Vue 注册表单、登录表单、Vite 代理、JWT localStorage、Axios 请求头、路由守卫、`/api/auth/me` 和工作台跳转。这和后端集成测试互补：前者验证整个用户路径，后者能更快、更精确地定位后端规则错误。

## 8. 当前方案的边界

当前实现适合本地第一阶段，但还需要在部署和权限阶段继续完善：

- JWT 有效期内没有服务端黑名单，主动吊销和多设备退出尚未实现。
- localStorage 方案需要严格防范 XSS；生产环境可进一步评估 HttpOnly Cookie 的收益与 CSRF 成本。
- JWT 签名密钥现在由 `JwtUtil` 从 `stock-system.jwt.secret` 注入，运行配置通过 `JWT_SECRET` 提供；数据库密码通过 `DB_PASSWORD` 提供，数据库用户名是非敏感的普通配置，本机沿用 `root`，生产可通过 `SPRING_DATASOURCE_USERNAME` 覆盖为最小权限账号。环境变量可以避免把密码写入 Git 和启动参数，但本身不是加密存储；拥有本机权限的程序仍可能读取它，持久化环境变量也可能以明文保存在系统配置中。旧值仍存在于历史提交的事实不会因本次修改自动消失；如果仓库曾共享，应按已暴露处理并轮换。缺少密钥、密钥少于 32 个 UTF-8 字节或缺少数据库密码时，后端应启动失败。生产环境仍应把密码和 JWT 密钥接到密钥管理服务，不能把普通环境变量文件当作长期密钥库。
- 当前 JWT 只表示用户身份，最终确认的工作空间角色必须查询成员关系，不能简单长期写死在 Token 中。

面试时不要说“用了 JWT 就绝对安全”。更准确的表达是：JWT 解决了无状态身份传递，安全性还依赖 HTTPS、密钥管理、过期策略、XSS 防护、权限校验和吊销方案。

## 9. 登录账号、昵称和密码长度

项目中的 `username` 字段在页面上称为“登录账号”：它是唯一、稳定的系统标识，目前只允许字母、数字和下划线，便于跨设备输入，也避免中文同形字、全角半角和Unicode规范化造成的唯一性歧义。

昵称是成员在工作空间里展示的名字，可以使用中文、可以与别人重复，未来也可以修改。例如登录账号是 `liu_haoyu`，昵称可以是“浩宇”。把二者分开后，修改展示名称不会影响登录和历史数据关联。

按当前本地阶段的产品决定，密码最短长度调整为4位，注册和登录接口都限制为最多64位，后端仍使用BCrypt摘要保存。这降低了家庭成员首次使用的门槛，但4位密码的组合空间很小，不适合公网系统。正式部署前应重新提高到至少8位，优先鼓励12位以上口令，并配合登录限流、HTTPS和异常登录防护。BCrypt只能保护数据库泄露后的密码摘要，不能让弱密码本身变强。

## 10. 选型状态

认证继续复用现有 JWT 链路；有效期与版本见 [技术栈](technology-stack.md)，安全限制集中在第 8 节。只有部署或会话需求变化时再通过 ADR 比较替代方案。
