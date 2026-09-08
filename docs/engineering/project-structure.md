# 项目结构与文件职责

更新时间：2026-09-03

## 0. 项目位置

项目边界见 [AGENTS.md](../../AGENTS.md)，文档分类见 [导航](../index.md)。本文件维护代码目录、分层职责与命名，不再维护第二份接手流程。

本结构遵循 [Maven 标准目录](https://maven.apache.org/guides/introduction/introduction-to-the-standard-directory-layout.html) 和 [Spring Boot 代码组织建议](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html)。

## 1. 最终组织方式：单 Maven 工程内按业务模块分包

项目保持一个 `pom.xml`、一个 Spring Boot 应用和 Maven 标准源码目录：

```text
src/main/java          正式 Java 源码
src/main/resources     配置、Flyway 和 Mapper XML
src/test/java          测试 Java 源码
src/test/resources     测试配置与资源
```

`src/main/java` 是源码根目录，不是业务模块。只有需要独立构建产物时，才会让多个 Maven 子模块各自拥有一套 `src/main/java`。当前没有独立部署、独立版本或团队所有权需求，因此不拆 Maven 多模块。

源码根包继续使用 `com.stock.system`，`StockSystemApplication` 放在根包，使 Spring 默认扫描其下的项目组件。根包的直接子包优先表达业务能力，而不是把全项目所有 Controller、Service、Mapper 分别堆在一起：

```text
com.stock.system
├─ StockSystemApplication.java
├─ auth
│  ├─ controller
│  ├─ service
│  ├─ dto
│  └─ vo
├─ user
│  ├─ entity
│  ├─ mapper
│  ├─ service
│  ├─ dto
│  └─ vo
├─ workspace
│  ├─ controller
│  ├─ service
│  ├─ mapper
│  ├─ entity
│  ├─ dto
│  └─ vo
├─ admin
│  ├─ controller
│  ├─ service
│  ├─ mapper
│  ├─ entity
│  ├─ dto
│  └─ vo
├─ common
├─ config
└─ security
```

上图表示允许的职责位置，不要求提前建立所有空目录。模块没有某类代码时就不创建对应包。例如 `auth` 不拥有数据库表，通常不需要自己的 `entity` 和 `mapper`；`workspace` 同时拥有空间、成员和邀请实体，不需要再为每张表建立一层套娃目录。

请求链路仍然是：

```text
浏览器 -> 模块 Controller -> 模块 Service -> 模块 Mapper -> MySQL
```

改变的是文件聚合方式，不是分层职责，也不是拆微服务。

### 1.1 模块职责和依赖方向

| 模块/公共包 | 主要职责 | 允许的核心依赖 |
| --- | --- | --- |
| `auth` | 注册、登录、当前会话接口 | 调用 `user` 提供的账号能力和 `security` |
| `user` | 用户实体、持久化和账号状态 | `common` |
| `workspace` | 空间、成员、邀请及所有权 | 调用 `user` 查询有效账号，使用 `common` |
| `admin` | 唯一超级管理员及治理操作记录 | 调用 `user`、`workspace` 的业务能力 |
| `security` | JWT、认证上下文和 Security 过滤链 | 只提供跨业务认证基础设施 |
| `config` | Spring 框架配置 | 不承载业务规则 |
| `common` | 真正跨模块的响应、错误等稳定基础类型 | 不反向依赖业务模块 |

跨模块调用优先经过对方 Service 或明确公开的业务接口，不能从一个模块的 Controller 调另一个 Controller，也不能为了省事随意访问另一个模块的 Mapper。依赖方向不能形成循环。阶段 1 先靠包规则、代码审查和测试保持边界，不引入 Spring Modulith 或 ArchUnit 依赖；以后模块边界确实难以维护时再评估自动校验。

## 2. 文件职责

| 类型 | 放什么 | 不能放什么 |
| --- | --- | --- |
| `controller` | HTTP 路径、参数校验、调用 Service、返回响应 | 事务、多表写入和权限核心规则 |
| `service` | 业务规则、权限组合、事务和跨 Mapper 协调 | HTTP 对象和页面状态 |
| `mapper` | MyBatis-Plus 与自定义 SQL 的数据库访问 | 业务流程判断 |
| `entity` | 数据库表对应对象 | 直接作为外部 API 请求或响应 |
| `dto` | 前端允许提交的请求数据 | 当前用户、角色、创建时间等可信字段 |
| `vo` | 后端允许返回的响应数据 | 密码摘要和内部控制字段 |
| `common` | 多个模块都真正使用的稳定基础类型 | 暂时不知道放哪里的业务代码 |
| `config` | 框架 Bean 与基础设施配置 | 空间、邀请等业务判断 |
| `security` | 认证与安全基础设施 | 空间 OWNER/MEMBER 业务权限矩阵 |

`BO` 只在 Service 内部确实需要一个既不是接口 DTO、也不是数据库 Entity 的复杂业务对象时创建，不为每个接口机械复制一份。工具类也优先放回所属模块或基础设施，例如 JWT 代码属于 `security`；只有真正无业务状态且跨模块复用时才建立 `util`，不保留含义模糊的公共 `utils` 杂物包。

### 2.1 Mapper XML 和测试

Mapper Java 接口放在所属业务模块，例如：

```text
src/main/java/com/stock/system/workspace/mapper/WorkspaceMapper.java
```

自定义 SQL 的 XML 按模块镜像放置：

```text
src/main/resources/mapper/workspace/WorkspaceMapper.xml
```

没有自定义 SQL 时不创建空 XML。测试使用同样的包结构放在 `src/test/java`，例如 `src/test/java/com/stock/system/workspace/...`，便于从测试位置直接看出它验证哪个模块。

## 3. 后端命名规则

- 包名全部小写、使用单数业务词：`auth`、`user`、`workspace`、`admin`；不使用 `workspaceModule`、`bizWorkspace` 等重复限定。
- Controller 使用业务对象或用例加 `Controller`，例如 `WorkspaceController`、`WorkspaceInvitationController`；一个 Controller 过大时按资源职责拆分，不使用 `BaseController` 承载业务。
- Service 使用清楚的业务能力加 `Service`。保留现有接口与 `Impl` 结构时，实现类使用 `ServiceImpl`；新代码不为了形式机械创建只有一个实现且没有替换边界的接口。出现多个实现、外部适配器或明确模块契约时再抽接口，接口不加 `I` 前缀。
- Mapper 使用实体名加 `Mapper`，例如 `WorkspaceMapper`；不使用 `Dao`、`Repository` 与 `Mapper` 混称同一职责。
- Entity 使用业务实体名称，不加 `Entity` 后缀，例如 `Workspace`、`WorkspaceMember`。
- 请求 DTO 使用动作加 `RequestDTO`，例如 `CreateWorkspaceRequestDTO`；响应 VO 使用内容加 `ResponseVO`，例如 `WorkspaceDetailResponseVO`。
- 枚举、错误码和方法名使用能直接表达业务的普通英文；方法优先使用 `create`、`join`、`leave`、`archive`、`restore` 等动词，不使用脱离上下文后无意义的 `handle`、`process`、`doWork`。
- 数据库表和字段使用 `snake_case`，Java 字段和方法使用 `lowerCamelCase`，Java 类使用 `UpperCamelCase`，常量使用 `UPPER_SNAKE_CASE`；标识字段在 Java 中写 `workspaceId`、`userId`，不写 `workspaceID`、`userID`。
- 接口路径、类、方法、字段和错误码先追求简洁再保证清楚：只有短名称产生真实歧义时才增加必要限定词。
- 只使用团队已约定或行业通用缩写，如 `DTO`、`VO`、`JWT`、`API`、`ID`；不自造缩写，也不为逐字翻译中文而拼接过长类名。

目录深度以表达职责为限，通常控制在业务模块下再分一到两层。禁止建立 `workspace/workspace/service` 之类重复目录，也不创建空 `bo`、`util`、`manager`、`handler` 包来表现所谓“企业级”。

## 4. 前端目录

```text
frontend
├─ e2e
│  └─ auth-smoke.mjs
└─ src
   ├─ views
   ├─ components
   ├─ api
   ├─ stores
   ├─ router
   ├─ styles
   └─ __tests__
```

| 目录 | 放什么 | 当前例子 |
| --- | --- | --- |
| `views` | 一个完整页面 | `LoginView.vue`、`HomeView.vue` |
| `components` | 可以被页面复用的局部界面 | `AuthLayout.vue` |
| `api` | Axios客户端和后端接口函数 | `http.ts`、`auth.ts` |
| `stores` | 多个页面共享的Pinia状态 | `auth.ts` |
| `router` | URL对应哪个页面、访问权限 | `index.ts` |
| `styles` | 全局基础样式和共享样式 | `main.css` |
| `__tests__` | 前端单元测试 | `auth-store.spec.ts` |
| `e2e` | 使用真实浏览器验证完整操作流程 | `auth-smoke.mjs` |

前端继续保持一个 Vue 工程，并按 `views`、`components`、`api`、`stores` 等职责分类；文件增长时只在这些目录下增加浅层业务子目录，例如 `views/workspace`、`components/workspace`，不复制第二套 `frontend/src`。文件使用业务辨识度明确的名称，例如 `WorkspaceListView.vue`、`workspace.ts`、`workspace-store.spec.ts`。

## 5. 一次登录经过哪些文件

```text
LoginView.vue
  -> stores/auth.ts
  -> api/auth.ts
  -> api/http.ts
  -> AuthController
  -> AuthService
  -> UserService
  -> user/AppUserMapper
  -> app_user表
```

阅读代码时不要一次打开所有文件。先从 `LoginView.vue` 开始，每次只回答四个问题：

1. 这个文件为什么存在？
2. 它接收什么数据？
3. 它输出什么数据？
4. 它下一步调用谁？

## 6. 不需要阅读和提交的生成内容

- `target`：Maven编译输出。
- `frontend/node_modules`：npm安装的第三方依赖。
- `frontend/dist`：前端生产构建输出。
- `frontend/.eslintcache`：ESLint缓存。
- `.idea`：IntelliJ本机配置。

这些文件不属于业务源码，已经通过 `.gitignore` 排除。初学阶段也不需要阅读 `package-lock.json`、各类 `tsconfig` 和Lint配置；遇到构建配置问题时再针对性查看。
