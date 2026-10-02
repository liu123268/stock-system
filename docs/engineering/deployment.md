# 部署与上线检查

状态：生产部署尚未实施；本文记录上线前必须完成的工作，不把计划写成已部署事实。

更新时间：2026-10-02

## 1. 文档职责

本文维护生产机器、服务账号、外部依赖、密钥、启动、回滚和上线验收。开发电脑怎样运行见 [本地开发](development.md)，测试环境和清理见 [测试规则](testing.md)，Longbridge 方案取舍见 [ADR-002](../architecture/decisions/ADR-002-longbridge-cli-sidecar.md)。

## 2. 计划部署结构

```text
Browser
  → Nginx（静态前端与 /api 反向代理）
  → Spring Boot
      → MySQL
      → Redis（可重建行情共享缓存）
      → 自己管理的 longbridge serve 子进程
          → Longbridge OpenAPI
```

Nginx、容器、Actuator、生产告警和自动发布当前均未实施。上线时应先选择宿主机服务或容器方案，再补充可执行配置和演练证据。

### 2.1 Windows Server 2019 的首次部署准备

以下是针对现有 Windows 主机的教学建议，尚未实施或完成云端验收。用户已说明该主机有其他应用，部署前必须先确定隔离方式，不能仅凭 CPU／内存充足就直接安装全局依赖。Docker 是部署方式，不是业务运行的必要依赖；Docker Desktop 官方不支持 Windows Server 2019，这不等于 Server 2019 无法运行 Windows 容器。Windows 容器与常见 Linux 镜像不能直接混用，需另行验证运行时、镜像与系统版本。直接运行宿主机服务的隔离边界见第 2.4 节。

| 组件 | 用途与安装边界 |
| --- | --- |
| Java 17 | 运行 Spring Boot JAR；安装 Windows x64 运行时或 JDK，后者包含运行时。项目自带嵌入式 Tomcat，无需另外安装 Tomcat。 |
| MySQL Server | 后端必须能访问 MySQL，但不要求数据库和后端同机。用户已确认 MySQL 留在开发电脑、云端只运行后端，因此本次云主机不安装 MySQL；跨机器连接和权限要求见第 2.5 节。若以后新建数据库，版本须验证 JDBC、Flyway 和迁移兼容性。 |
| Nginx Windows 版 | 提供前端静态文件，把 `/api` 转发到同机 Spring Boot；官方将 Windows 版标为 beta，且只有一个 worker 实际处理请求，首次学习／小规模展示可评估采用，不能据此宣称高并发生产能力。 |
| Longbridge CLI | 真实行情的运行依赖；安装 Windows 版，在运行 Java 的同一服务账号下授权，具体要求见第 3 节。 |
| Windows 服务包装工具（例如 WinSW） | 将 Java 和 Nginx 纳入 Windows 服务管理，实现启动、重启及日志管理；MySQL 可使用安装程序提供的服务。必须验证停止 Java 时它拥有的行情子进程也退出。 |
| Redis | 行情共享缓存，代码允许关闭。首次部署可显式设置完整属性 `stock-system.market.redis.enabled=false`，之后再选择受支持的 Redis 服务并验收；不把本机 WSL2 环境当作 Server 2019 已有能力，也不推荐无人维护的旧 Windows 移植包。 |

建议在开发电脑完成构建和发布前验证，只上传后端 JAR、前端 `dist` 和必要部署材料。这样云主机无需安装 IDE、Maven、Node.js、Python 或上传 `node_modules`；目录采集仍可在本机完成。前端构建所需 Node.js 版本以当前 `frontend/package.json` 为准。生产前端由 Nginx 提供，不常驻 Vite 开发／预览服务器。

### 2.2 首次部署容量预算

以下为 Windows 已安装、单应用实例、小规模数据、构建在本机完成时的规划估算，**不是云端实测、安装包固定大小或性能承诺**。此表包含同机 MySQL 的候选方案；用户已选择数据库留在本机，云端预算不包含 MySQL 行。既有服务和 Windows 自身占用需另外核对。

| 新增占用 | 初期磁盘预算 |
| --- | --- |
| Java 17 | 约 0.3–0.8 GB，取决于 JRE／JDK 和发行版 |
| MySQL 程序及初期数据、数据库内部日志 | 约 2–5 GB，取决于版本、配置和数据量 |
| Nginx、Longbridge CLI、服务包装工具和应用发布文件 | 合计预留约 0.5–1 GB |
| 应用／访问日志、备份、旧发布包和更新临时空间 | 预留约 5–10 GB，须配置保留策略并随使用量调整 |

首次直接部署可先按新增约 10–20 GB 规划，给项目保留约 30–40 GB 空闲磁盘更从容；没有日志轮转或备份保留策略时不存在固定上限。应用及数据库可先按合计约 2–4 GB 内存预算（不含 Windows 与其他服务），上线后再依据进程实际占用调整。CPU／内存充足不能代替网络连通、磁盘剩余、接口负载和供应商权限验证。

数据库留在本机时，云端程序及发布文件可先预留约 1–2 GB，日志、旧版本及更新临时空间另外预留约 5–10 GB，先为云端项目保留 10–20 GB 空闲空间；数据库数据与备份占用留在数据库所在电脑。若改用隔离虚拟机，还需单独预算客户机操作系统、虚拟磁盘及快照，不能沿用只上传发布文件的占用估算。

### 2.3 从本机开发到云端访问的操作顺序

1. **盘点主机**：查看目标磁盘剩余空间、已有 Java／MySQL／Web 服务及端口占用，确定应用目录和非管理员服务账号；共享主机不得覆盖已有服务。
2. **准备运行依赖**：安装／复用 Java 17、MySQL、Nginx 和 Longbridge CLI，分别核对版本及启动状态。安装软件、启动进程和通过项目 API 验收是三个不同步骤。
3. **准备业务数据库**：先确定数据库位置。用户已确认数据库留在本机，本次不向云端安装 MySQL 或搬迁数据，按第 2.5 节准备连接与专用账号。若以后创建独立生产库，新库由 Flyway 创建表结构；股票目录是业务数据，需要另行导入可信目录，建表不等于目录已就绪。若迁移已有账号和研究记录，应先备份并制定整库恢复及 Flyway 版本核对方案，不能只复制部分关联表；当前目录脚本的 dev/test 入口也不能未经改造直接冒充生产导入工具。
4. **补齐生产配置**：建立并验证 `prod` 配置，显式激活它；配置实际 JDBC 地址、数据库账号、Secret 注入、CLI 绝对路径、行情模式、Redis 开关和时区。同机 Web 入口方案中，Java 使用核对无冲突的回环端口（例如 `127.0.0.1:8082`），MySQL 及可选 Redis 只在本机或受控私网访问。不能沿用默认 dev profile，尤其不能继承开发电脑的 Windows 用户目录或 Redis 6380 端口假设。
5. **构建和发布**：在本机完成发布前验证，生成新的后端 JAR 和前端 `dist`；保留上一版产物用于应用回退。JAR 带迁移及运行依赖，不意味着它包含开发库数据。已存在的旧构建文件不能自动代表最新源码。
6. **先验证内部链路**：以服务账号完成 Longbridge 授权、启动 Java，核对迁移、登录、目录搜索和真实行情；排查完成后配置 Windows 服务及停止行为。
7. **接通浏览器入口**：Nginx 静态目录指向上传的 `dist`，Vue history 路由回退到 `index.html`，`/api` 转发保留完整路径。当前前端 API 基地址就是 `/api`，同一个站点可同时承载页面和接口，避免新增跨域配置。
8. **完成公网和运维验收**：核对云安全组和 Windows 防火墙，网站开放 80／443，管理端口只对需要的管理来源开放；配置域名／HTTPS、日志保留、数据库备份及恢复演练。验证浏览器登录、研究操作、行情和刷新页面路由，再验证主机重启后服务恢复；完整环境验收按第 4 节执行。

例如，浏览器请求 `/api/auth/login` 应被转发到 `http://127.0.0.1:8082/api/auth/login`；`proxy_pass http://127.0.0.1:8082;` 不附加 URI，因此保留 `/api` 前缀。前端文件则由 Nginx 直接返回，静态页面本身不会连接 MySQL。

参考：[Docker Desktop Windows 支持范围](https://docs.docker.com/desktop/setup/install/windows-install/)、[Nginx Windows 限制](https://nginx.org/en/docs/windows.html)、[Nginx proxy_pass 路径语义](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_pass)、[Vite 静态部署](https://vite.dev/guide/static-deploy)、[Longbridge CLI 安装与授权](https://open.longbridge.com/docs/cli/install)、[WinSW](https://github.com/winsw/winsw)。

### 2.4 共享云主机的隔离边界

用户希望部署不影响主机上已有软件和服务，隔离是本次方案选择的前提。三种方式能力不同，以下只是选型说明，未授权或实施主机功能安装、虚拟网络修改或重启：

| 方式 | 能力与边界 |
| --- | --- |
| 直接运行，配独立账号／目录／端口 | 可避免常见的文件覆盖、全局环境变量和端口冲突；仍共享操作系统、CPU、内存、磁盘和网络，不能等同于容器或虚拟机隔离。 |
| 容器 | 隔离应用依赖、文件系统视图、进程及网络，可配置资源限制；主机挂载、开放端口和特权设置会改变隔离边界。Windows 进程隔离容器仍共享宿主机内核，Hyper-V 隔离容器另有客户机内核；不能把所有容器都视为独立虚拟机。 |
| 独立虚拟机／独立云实例 | 具有独立操作系统，适合优先隔离整个运行环境；同宿主机 VM 仍共享物理资源，需分配资源额度。现有云主机内新建 VM 取决于供应商是否开放嵌套虚拟化；仅有 64 GB 内存不能证明支持。安装 Hyper-V 等主机功能可能需要重启，应先评估已有业务影响。 |

若选择直接运行：使用专用非管理员 Windows 账号；JDK、Nginx 和 CLI 使用独立目录及绝对路径，不覆盖系统 Java、全局 PATH／JAVA_HOME 或其他用户的授权；配置和 Secret 仅注入本服务；先核对现有监听端口，使用独立服务名、日志目录和回环端口；网站优先与已有入口协调路由，不抢占 80／443、不覆盖既有 Web 配置。设置合理的 JVM 堆上限及日志保留策略，但 JVM 堆上限不是整个 Java 进程或主机 CPU／磁盘的硬隔离。验收和回退只操作已确认属于本项目的服务、进程、文件和防火墙规则。

如果采用 Docker，Longbridge CLI 必须安装在运行 Java 的容器环境中，Java 与 `serve` 保持当前父子进程关系；安装在宿主机上的 CLI 和授权不会自动出现在容器中。Token 由容器运行账号管理，并通过受保护的持久化方式跨重建保留，不能烘焙进镜像。Docker 不会替项目处理供应商授权、API 权限或业务数据库连接。

参考：[Docker 容器原理](https://docs.docker.com/get-started/docker-concepts/the-basics/what-is-a-container/)、[Windows 容器隔离模式](https://learn.microsoft.com/en-us/virtualization/windowscontainers/manage-containers/hyperv-container)、[Hyper-V 嵌套虚拟化](https://learn.microsoft.com/en-us/windows-server/virtualization/hyper-v/nested-virtualization)。

### 2.5 云端后端连接本机 MySQL

用户已确认：云主机运行后端，MySQL 和数据留在自己的开发电脑，云端不运行数据库。当前没有建立或验证这条网络链路。

- 后端通过受控 VPN 私网或加密隧道访问本机 MySQL，不直接把 MySQL 3306 开放给公网。家庭／校园网的局域网 IP 不是云端天然可达的地址；JDBC 应使用实际 VPN 地址或云端隧道入口，不能照搬开发电脑的 localhost 配置。若 Java 在容器中，还要以容器视角核对隧道端点可达性。
- 电脑必须保持开机、不休眠、MySQL 运行、网络及 VPN／隧道可用；任一环节失效都影响云端依赖数据库的功能。这是学习／演示方案的可用性边界，不宣称云端服务已经独立于开发电脑。
- 在本机 MySQL 中给云端应用建立专用账号，只授权目标库及受控连接来源，不沿用开发 root。公网应用直接连接开发库意味着它能按权限写入真实开发数据；建议在同一 MySQL 实例中使用独立发布库，该建议尚未实施，不要求把数据库搬上云。
- 当前 `spring.flyway.enabled=true`，云端启动会对 JDBC 实际目标库验证并执行缺失迁移。上线前核对目标库、Flyway 版本并备份；不能以“数据库没有搬到云上”推断云端不会修改本机数据库。仅允许预期的应用实例执行迁移，避免本机旧版本与云端新版本同时操作不兼容结构。
- 验收从云端真实连接到目标库，再通过项目登录、目录搜索及研究读写验证；另验证电脑断网／隧道中断后的错误边界和恢复。数据库备份在本机维护，连接凭据仍按第 3.2 节管理。

## 3. Longbridge sidecar 部署前置条件

### 3.1 安装和运行账号

1. 在实际运行 Spring Boot 的机器安装已验证兼容的 Longbridge CLI，并用 `longbridge --version` 核对版本。当前本地验证版本为 `v0.28.5`，升级必须重新跑真实集成测试。
2. 为应用使用固定的非管理员服务账号。CLI 授权和缓存属于这个 OS 账号；不能在管理员或开发者账号登录后，假设服务账号会自动继承。
3. 在同一服务账号下完成 `longbridge auth login`，再用 `longbridge quote 600519.SH` 做最小只读验证。授权通常复用，不是每次报价都重新登录；Token 过期、撤销、账号或机器变化时才需要重新授权。
4. 保证该账号对自身 Longbridge Token 与日志目录拥有最小必要的读写权限。不能授予其他用户广泛读取权限，也不能因为日志目录不可写而只检查项目目录。

### 3.2 凭据和持久化

- Token 由 CLI 保存在服务账号的 `~/.longbridge/openapi/tokens/<client_id>`；项目代码不读取其内容。
- Token 不能写入 Git、镜像层、前端环境变量、启动参数、普通日志或浏览器响应。备份和迁移必须按生产密钥处理，并优先采用供应商支持的重新授权流程。
- Spring Boot 的 `JWT_SECRET` 和 `DB_PASSWORD` 必须由部署平台的 Secret 注入；数据库用户名不是秘密，可由部署配置通过 `SPRING_DATASOURCE_USERNAME` 提供，但数据库账号必须是该应用的最小权限账号，不应继续使用 root。生产镜像、当前 Git 文件、前端构建产物、启动参数和普通日志中都不能出现密码或 JWT 密钥。旧提交若曾包含这些值，不能只靠删除当前文件解决，必须轮换密钥或密码。
- 如果使用容器，镜像可以安装 CLI，但不能烘焙 Token。服务账号 home/Token 必须采用受保护的持久卷或部署平台密钥机制；具体挂载方式需在选定平台后验证权限、刷新和容器重建行为。
- 生产机器需通过 NTP 保持真实时钟准确，并显式对齐三处时间解释：JVM 默认时区设为 `Asia/Shanghai`，MySQL 全局及应用连接会话的 `time_zone` 设为 `+08:00` 或可用的 `Asia/Shanghai`，项目行情转换继续使用代码中的市场时区。上线验收同时查询 JVM `ZoneId.systemDefault()`、MySQL `@@global.time_zone`／`@@session.time_zone` 与 `NOW()`，再和应用日志墙钟对照；JDBC URL 的 `serverTimezone=Asia/Shanghai` 只说明驱动怎样解释时间，不能单独证明数据库会话已经对齐。
- 生产机器还需验证 DNS、TLS 和出站网络；若依赖代理，必须在服务账号及 Java 启动环境中实际验证，不能只以交互式浏览器可访问为依据。

### 3.3 进程和容量

- 当前简写变量 `MARKET_MODE`、`LONGBRIDGE_CLI_PATH`、`REDIS_HOST` 等只由 `application-dev.properties` 映射，不能直接当成尚未建立的 production profile 配置。生产实施时必须新增并验收生产配置，或使用 Spring Boot 标准环境变量绑定完整属性（如 Redis 主机 `SPRING_DATA_REDIS_HOST`、端口 `SPRING_DATA_REDIS_PORT`）；不得以 `dev` profile 冒充生产配置。CLI 路径使用绝对值，避免 systemd、Windows Service 或容器的 PATH 与交互式终端不同。
- Windows 官方脚本当前默认安装到 `%LOCALAPPDATA%\Programs\longbridge\longbridge.exe`；开发配置会以该位置作为 PATH 缺失时的默认值，生产仍必须显式设置绝对路径。部署验收先执行 `longbridge auth status`；网页显示授权成功但这里仍为 `Token not found` 时，说明 Token 没有写入运行 Spring Boot 的同一 OS 账号，不能把网页状态当作 sidecar 已授权。
- Spring Boot 第一次报价时按需启动一个 `longbridge serve`，应用停止时负责关闭它。进程管理器只监督 Spring Boot，不要再独立常驻第二个 `serve`，否则会与应用争用账号长连接。
- Longbridge 当前规则为一个账号一个行情长连接。Redis 只共享可重建行情和同 Key 锁，不能解除账号连接与供应商限流；没有完成集中式行情网关设计前，同一账号只部署一个启用 `longbridge` 模式的应用副本，不能通过简单增加副本实现扩容。
- 行情通用限制按不超过每秒 10 次、并发 5 次执行，历史 K 线另有每 30 秒最多 60 次的接口限制；这些是此前核对的供应商规则，上线时须按第 4 节重新核对。当前 Java 端已有批量请求、进程内缓存及 Redis 共享缓存，但没有跨副本全局限流。官方 CLI/SDK 的节流也不能代替项目对 301606/429 的监控，不能只依赖 100ms 通用间隔宣称覆盖所有方法限制。共享缓存的具体能力及未完成项见 [SPEC-002 设计](../specs/002-stock-research/design.md#46-redis-共享行情缓存与访问保护)。
- 当前只开放 `quote.quote`、`quote.candlesticks`、`quote.history_candlesticks_by_date`、`quote.intraday` 与 `quote.trading_days` 五个固定只读方法，不配置交易权限。未来任何交易接入都必须另立需求、权限、确认和风控设计，不能因为 CLI 本身具备交易命令就直接暴露。
- 实时报价 pull 的单次代码上限为 500；需要全市场批量读取时分批并纳入调度，不能把 5000 个代码塞进一次请求。订阅仍受账号单连接和同时最多 500 个符号限制，不能把 pull 无月度总量硬上限误解为可同时订阅 5000 只。
- K 线按自然月不同股票代码计额，上线配置前在 [Longbridge Account Center](https://open.longbridge.com/account) 的“我的行情权限”核对实际档位。官方当前没有公开 CLI 用量查询命令，也未承诺账号页提供实时已用计数；若项目以后记录自身不同标的数，必须标为估算且不能代替供应商全局余量。告警必须结合方法解释 `301607`：实时报价表示单次代码数量超限，最近 K 线表示单次请求数据量超限，历史 K 线可表示当月不同股票代码额度超限；不能跨接口套用同一个原因。应用返回 `MARKET_KLINE_LIMIT_EXCEEDED`，应纳入 429 指标和告警。
- 当天分时走 `quote.intraday`；历史交易日分时由 `1m` 历史 K 线还原，因此历史分时也占用同一自然月“不同股票代码”额度。页面按需请求和停止刷新条件见 [前端数据流](frontend.md#十二按需-k-线与分时图的数据流)。近一年日期使用 `quote.trading_days` 判断 CN/HK 交易日并缓存日期结果，当天点按市场时区过滤；响应明确携带 A 股／港股常规交易时段。更早日期超过官方交易日日历范围时不伪造本地结果，而由分钟 K 数据或范围错误决定。

## 4. 上线验收

按以下顺序留下环境化证据：

1. 服务账号运行 `longbridge --version`、`longbridge check` 和一只允许市场的只读报价成功；
2. Spring Boot 以生产配置启动，第一次行情请求只创建一个子进程，重复请求不会重复登录或重复创建进程；
3. 项目登录用户通过本项目 `/api` 取得代码、价格、供应商时间、来源和真实/模拟标识，前端不直接访问 Longbridge；
4. CLI 不存在、未授权、网络超时、上游错误和 sidecar 异常退出时，搜索保留可信目录但价格为空，添加按 `MARKET_QUOTE_UNAVAILABLE` 失败且不写半成品；
5. 停止 Spring Boot 后核对其子进程已退出，没有遗留 `longbridge serve`；
6. 检查浏览器网络响应、前端生产构建、后端 JAR、日志和 Git 历史均不含 Token 或授权缓存；
7. 重新核对上线时的实时报价限流、单次标的数、历史 K 线月度不同标的配额和市场权限；记录实际 CLI 版本、服务账号、Token 持久化方式、网络/代理、实例数、回滚命令和告警负责人。
8. 用一个已经加入测试空间且本月已查询过的股票读取分钟／日／年 K、左拖补历史、当天分时和一个历史交易日，确认同一个 sidecar、来源时间、不复权标记、停止刷新条件和页面错误状态；再用非交易日验证不会把上一交易日数据错标为所选日期。不得用批量搜索候选做 K 线或分时验收。
9. Redis 开启与关闭两条路径均通过上述 API 验证；部署环境的连接、访问权限、缓存过期、故障恢复与环境 Key 隔离通过验收后，才能把本机隔离测试提升为该环境的上线证据。当前尚无可直接套用的 Redis 生产部署配置。

Actuator readiness 与生产告警尚未实现，不能在缺少这些证据时把“进程能启动”写成生产可运维完成。

## 5. 回滚和授权失效

- 外部行情故障时将生产环境的完整属性 `stock-system.market.mode`（标准环境变量为 `STOCK_SYSTEM_MARKET_MODE`）改为 `directory` 后重启 Spring Boot：搜索继续使用 MySQL 标准目录，但价格和行情时间为空，添加研究按现有规则失败。`MARKET_MODE` 只是当前 dev profile 的简写映射；不得切到 `mock` 冒充生产行情。
- 若版本升级导致不兼容，回退到已验证 CLI 版本和应用版本，再重新运行第 4 节；不要删除数据库研究历史。
- Token 过期或撤销时，在服务账号下重新执行官方授权；不修改源码、不把 Token 复制到配置文件。若怀疑泄漏，先从 Longbridge 安全设置撤销授权，再清理受影响运行环境并重新授权。
- 当 Java SDK 提供受支持的 OAuth/Token 入口时，可替换 `LongbridgeQuoteGateway` 实现；切换前保留 sidecar 回滚路径并做同一套接口、故障和凭据验收。
