# 部署与上线检查

状态：新版已发布；HTTPS、目录同步及隔离部署故障验收通过，用户确认生产登录后报价／图表／历史正常；长期运维边界见下文。

更新时间：2026-10-08

## 1. 文档职责

本文维护生产机器、服务账号、外部依赖、密钥、启动、回滚和上线验收。开发电脑怎样运行见 [本地开发](development.md)，测试环境和清理见 [测试规则](testing.md)，Longbridge 方案取舍见 [ADR-002](../architecture/decisions/ADR-002-longbridge-cli-sidecar.md)。

## 2. 当前云端部署

以下部署配置于 2026-10-08 状态功能发布后现场核对；首次建站证据为 2026-10-02，最新状态验收见 [CODE-206J](../specs/002-stock-research/tasks.md#code-206j每日交易状态核实2026-10-08)，目录与部署隔离故障验收见 [2026-10-07 收尾](../specs/002-stock-research/tasks.md#2026-10-07-v1-收尾目录部署与整体流程)。

| 项目 | 当前配置 |
| --- | --- |
| 主机 | 腾讯云轻量应用服务器，广州；2 核 / 4GB / 60GB；Ubuntu 24.04.4 LTS |
| 容器工具 | Docker 29.8.2，Compose v5.5.1；项目名 `stock-system` |
| 部署目录 | `/home/ubuntu/stock-system-deploy`，宿主机操作账号 `ubuntu` |
| MySQL | `mysql:8.4`，实际 8.4.11；库 `stock_system`，应用账号 `stock_app`；容器上限 1GiB，buffer pool 256MiB |
| Redis | `redis:7.4-alpine`；容器上限 256MiB，缓存上限 128MiB，`noeviction`；关闭 RDB/AOF，仅作可重建缓存及锁 |
| 后端 | `stock-system-backend:release-20261008T150710Z-a3351ce1`；CLI 0.28.5，Java 17，`prod` profile；容器上限 1.5GiB，堆上限 768MiB |
| 前端代理 | `nginx:stable-alpine`，实际 Nginx 1.30.5；静态生产构建与 `/api/` 转发，容器上限 128MiB |
| 网络 | MySQL、Redis 只供 Compose 内网访问；后端映射 `127.0.0.1:8082`；前端映射公网 80／443 和回环 8080；HTTP 自动跳转 HTTPS，ACME 验证路径除外 |

### 文件与配置职责

- `compose.yaml` 管理 MySQL、Redis；`compose.backend.yaml` 增加后端，`compose.frontend.yaml` 增加前端代理，`compose.public.yaml` 增加公网入口，`compose.https.yaml` 最后覆盖 TLS 配置。完整服务操作同时指定五份文件，前两份当前仅保存在云端。模板见 [公网 Compose](../../deploy/frontend/compose.public.yaml)／[HTTPS Compose](../../deploy/frontend/compose.https.yaml)。证书申请和续期另加 `compose.certbot.yaml`，Certbot 为临时工具容器，不常驻。
- 前端在本机 `frontend` 执行 `npm.cmd run build`，只上传 `dist` 产物到云端 `frontend/dist/`，无需在云主机安装 Node。可执行模板见 [前端 Compose](../../deploy/frontend/compose.frontend.yaml) 和 [Nginx 配置](../../deploy/frontend/nginx.conf)。代理保留 `/api/` 前缀，Vue 页面刷新回退 `index.html`；静态资源缺失仍返回 404。
- `backend/app.jar` 是应用包；镜像构建规则见仓库 [Dockerfile](../../deploy/backend/Dockerfile)。`backend/bin/longbridge` 放 Linux CLI，不能直接使用本机 Windows 可执行文件。
- `backend/application-prod.properties` 只读挂载到 `/app/config/application-prod.properties`，对应仓库 [生产配置](../../src/main/resources/application-prod.properties)。`prod` 是同一应用的云端配置：数据库与 Redis 使用服务名 `mysql`、`redis`，CLI 使用 `/usr/local/bin/longbridge`；不另建一套业务代码，也不会自动继承 `dev` 配置。
- `secrets/` 保存 `mysql_root_password`、`mysql_app_password`、`jwt_secret`，通过 Compose secrets 挂载到 `/run/secrets/`；后端通过 `configtree` 读取所需密钥。目录权限 700、源文件 600，不把内容提交或打入镜像。
- MySQL 数据保存在命名卷 `stock-system_mysql_data`；CLI 授权保存在宿主机 `longbridge-home/`，挂载到容器 `/home/stock/.longbridge`。后端以 UID/GID `1000:1001` 运行，授权目录归该账号且权限 700。持久化允许重建容器保留数据，但不等于备份。
- Docker Hub 直连曾超时；当前 `/etc/docker/daemon.json` 使用腾讯云镜像源 `https://mirror.ccs.tencentyun.com`，已成功拉取镜像。
- 本机通过 SSH 密钥登录云端 `ubuntu`，已验证 `sudo -n` 可用，可直接上传文件和执行部署命令。本机私钥留在 `C:\Users\32101\.ssh\id_ed25519`，对应公钥加入云端 `/home/ubuntu/.ssh/authorized_keys`；不在文档或仓库保存密钥内容。公私钥可由本机生成，也可由云平台生成；服务器保存公钥，连接端使用私钥。

### 本机数据库工具连接云端 MySQL

MySQL 未发布宿主机端口。本机 IDEA Database 可启用 SSH 隧道：SSH 主机为云端公网 IP、端口 22、用户 `ubuntu`，使用上述本机私钥；数据库主机填写云端 MySQL 容器内网 IP、端口 3306、用户 `stock_app`、数据库 `stock_system`。密码来自云端 `secrets/mysql_app_password`，不写入文档。此方式无需开放公网 3306 或重启数据库，数据库地址由 SSH 服务器侧访问。

容器内网 IP 可能随重建变化；连接前在云端执行 `sudo docker inspect --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' stock-system-mysql-1` 获取，不把某个内网 IP 视为固定配置。本连接访问真实云端业务库，本地开发后端仍使用本地库。

云端不是本地数据库整库副本：MySQL 镜像首次初始化创建 `stock_system` 和应用账号，后端首次启动由 Flyway 执行包内 V1–V11 SQL 建表／升级，再单独导入股票目录快照；本地账号、空间和研究记录未做整库迁移。2026-10-02 只读核对两端均有 13 张表，表名及迁移版本一致；11 是迁移版本数量，不是表数量。部署相同应用代码不意味着复制已有业务数据。

### 日常操作入口

每日交易状态核实属于后端常驻任务：北京时间 06:35 后按待办股票每批最多 500 个代码使用这个后端已经管理的 CLI，晚启动与晚到目录补查，不启动第二个进程。目录 timer 更新身份，两者分工，状态核实失败保留上次事实并显示待核实。2026-10-08 已完成首次真实检查和 V12 升级，迁移前数据库备份在 `backups/20261008T150710Z-a3351ce1/mysql-before.sql.gz`；生产身份与业务校验值未变、CLI 数量 1，具体状态覆盖见上述 CODE-206J。旧版本自动回滚因新增迁移被工具拒绝，不能通过恢复旧 JAR 或还原整库覆盖新业务数据。

最新前端为 `20261008T152250Z-aa2f24d1`，仅补齐明确其他交易状态的说明，后端仍是上表 V12 镜像。真实 Chrome 已只读确认公网 HTTPS 登录页、401 与最新行情／状态资源；本轮未替用户登录生产账号。

下面依次检查配置、启动服务、查看状态和后端日志；修改挂载配置或环境变量后，应重建后端容器使配置生效。

```bash
cd /home/ubuntu/stock-system-deploy
sudo docker compose -f compose.yaml -f compose.backend.yaml -f compose.frontend.yaml -f compose.public.yaml -f compose.https.yaml config -q
sudo docker compose -f compose.yaml -f compose.backend.yaml -f compose.frontend.yaml -f compose.public.yaml -f compose.https.yaml up -d
sudo docker compose -f compose.yaml -f compose.backend.yaml -f compose.frontend.yaml -f compose.public.yaml -f compose.https.yaml ps
sudo docker compose -f compose.yaml -f compose.backend.yaml -f compose.frontend.yaml -f compose.public.yaml -f compose.https.yaml logs --tail=60 backend
# 修改后端配置后执行：仅重建后端，保留数据库及 Redis
sudo docker compose -f compose.yaml -f compose.backend.yaml -f compose.frontend.yaml -f compose.public.yaml -f compose.https.yaml up -d --no-deps --force-recreate backend
```

`Started` 只说明容器已启动；须看到 Spring Boot 的 `Started StockSystemApplication` 后再验证接口。不要用 `down -v` 做日常重启，它会删除数据库命名卷。

当前入口为 `https://<服务器IP>/`，HTTP 自动跳转 HTTPS，无需本机运行项目或 SSH 隧道。原本机 18080 隧道已结束；服务器回环 8080 保留供内部访问，不作为独立登录入口。

### HTTPS 与证书续期

- 使用 Let’s Encrypt 的 `shortlived` 公网 IP 证书，Certbot 5.4.0 通过 HTTP-01/webroot 申请；浏览器验证 IP，不需要域名。IP 证书约六天有效，必须自动续期。依据 [Let’s Encrypt IP 证书说明](https://letsencrypt.org/2026/03/11/shorter-certs-certbot)。
- 正式证书名 `stock-system-ip`，证书与私钥保存在云端 `tls/letsencrypt/`（宿主目录 700），只读挂载给 Nginx；不复制到 Git、本机或镜像。生产配置见 [HTTPS Nginx](../../deploy/frontend/nginx.https.conf)，验证目录为 `frontend/acme/`。80 端口的验证路径必须持续可达，否则不能续期。
- root 所属 `/usr/local/sbin/stock-system-renew-tls` 由 [续期脚本](../../deploy/frontend/renew-tls.sh) 安装，systemd `stock-system-tls-renew.timer` 每六小时检查（附随机延迟、补跑错过的任务）。证书成功更新后，脚本校验并重载 Nginx；失败由服务退出状态和 journal 保留。定时任务已启用，续期 dry-run 及重载标记处理均验证成功；长期运行与故障告警尚未验收。
- Linux 续期脚本通过仓库 `.gitattributes` 固定 LF 换行，防止 Windows 的 Git 自动转为 CRLF 后无法在 Linux 正常执行；本地 Bash 语法检查只证明脚本语法，不代替证书续期或 Nginx 现场验收。
- 运维检查：`sudo systemctl list-timers stock-system-tls-renew.timer`；失败查看 `sudo journalctl -u stock-system-tls-renew.service -n 80 --no-pager`。更换公网 IP 必须重新申请匹配 IP 的证书并更新 HTTPS 配置。

当前未使用域名。腾讯云说明不解析域名、仅通过公网 IP 直接访问测试可暂不备案；将域名解析到大陆云资源时需先备案，个人用途本身不构成豁免。依据 [腾讯云备案说明](https://cloud.tencent.com/document/product/243/19630)。

### 当前发布与验收

2026-10-07 已经用户授权发布 `20261007T131951Z-cff9eede`，生产目录同步首轮成功、定时器启用。当前目录 stock 为 8359 条，4 个账号、2 个空间；这是当日快照，后续正常使用和同步可能变化。原 8318 个股票 ID 与已有研究关联保留；2026-10-02 的空库初始导入只能作为历史记录，不能当作日常同步。

实际公网 Chrome 严格证书检查首页／登录、桌面／手机、HTTP 跳转、API 401 和资源 404 均通过，未写生产业务数据。用户随后正常登录并确认报价、图表和历史记录正常。正式镜像与真实云端 Redis 的故障／恢复验证采用同机隔离 test 后端和替身 CLI，不挂生产授权；详细六项结果与清理只见上方 tasks，不把它们写成真实生产 Token 撤销或断网演练。

本机发布入口为仓库外 `C:\Users\32101\Desktop\stock-system-部署工具`。本次服务器发布已经 `DEPLOY_OK`，本地最终 curl 因 Windows Schannel 无法访问证书吊销服务误报失败；`Deploy.ps1` 仅追加 `--ssl-revoke-best-effort`，证书链和 IP 校验保留，修复后公网检查成功。发布包 SHA-256 为 `e9e405c41803e4378582bdbbf7235463a57dc31a5f3ba5b7e562b6b849dd79ba`；回滚使用工具的已有发布记录，不删除数据库卷、授权或业务历史。解析和健康检查通过不代表再次完整部署。

当前产物／日志凭据检查通过，Git 历史检查仅在本地进行；旧版本配置存在数据库密码字面量，当前密码来自外部 Secret。用户明确要求保留现有数据库密码，本轮未更换；密码强度与历史清理未整改，检查不宣称证明所有历史均无敏感信息。完整扫描口径见 tasks。

MySQL、Redis、Flyway V12 与应用启动通过，CLI 保持一个由 Java 管理的真实行情进程。证书续期 dry-run 与重载为 2026-10-02 的已验收证据，本次没有重复申请证书。后续整库备份／恢复、主机重启恢复、长期同步和主动告警尚未演练；Flyway 9.22.3 对 MySQL 8.4 的兼容性提示仍需单独处理。

当前网页访问结构如下；浏览器经公网 HTTPS 访问前端代理，HTTP 自动跳转。Actuator、生产告警和自动发布尚未实现。

```text
Browser
  → Nginx（静态前端与 /api 反向代理）
  → Spring Boot
      → MySQL
      → Redis（可重建行情共享缓存）
      → 自己管理的 longbridge serve 子进程
          → Longbridge OpenAPI
```

## 3. Longbridge sidecar 部署前置条件

2026-10-02 本地源码开始切换官方 Java SDK OAuth 和独立账号，云主机明确保持现有 CLI。本节仅负责生产操作；本地准备与验收见[开发说明](development.md#92-新账号与官方-oauth-准备)，不得因为 dev 已改源码而移除云端 CLI、授权挂载或重建线上容器。

新版本打包包含 SDK 与 CLI 两个传输实现，运行时按 profile／transport 选择一个；本地运行过 dev 不会让上传的 JAR 自动固定成 dev。当前 Dockerfile 显式激活 prod，生产配置选择 cli；未设置 transport 的旧生产配置也按代码默认选择 cli。发布仍须核对实际生效 profile、外部配置覆盖、CLI 可执行文件及授权挂载；本地 SDK 单元测试不代替生产 CLI 验收。两套传输是本次分阶段迁移的过渡状态，最终统一接入方式后仍用环境配置区分账号、数据库与密钥。

### 3.1 安装和运行账号

1. 在实际运行 Spring Boot 的机器安装已验证兼容的 Longbridge CLI，并用 `longbridge --version` 核对版本。当前本地验证版本为 `v0.28.5`，升级必须重新跑真实集成测试。
2. 为应用使用固定的非管理员服务账号。CLI 授权和缓存属于这个 OS 账号；不能在管理员或开发者账号登录后，假设服务账号会自动继承。
3. 在同一服务账号下完成 `longbridge auth login`，再用 `longbridge quote 600519.SH` 做最小只读验证。授权通常复用，不是每次报价都重新登录；Token 过期、撤销、账号或机器变化时才需要重新授权。
4. 保证该账号对自身 Longbridge Token 与日志目录拥有最小必要的读写权限。不能授予其他用户广泛读取权限，也不能因为日志目录不可写而只检查项目目录。

### 3.2 凭据和持久化

- Token 由 CLI 保存在服务账号的 `~/.longbridge/openapi/tokens/<client_id>`；项目代码不读取其内容。
- Token 不能写入 Git、镜像层、前端环境变量、启动参数、普通日志或浏览器响应。备份和迁移必须按生产密钥处理，并优先采用供应商支持的重新授权流程。
- Spring Boot 的数据库密码和 JWT 密钥必须通过 Secret 注入，当前方式见第 2 节；数据库账号只授予应用所需权限，不应使用 root。生产镜像、当前 Git 文件、前端构建产物、启动参数和普通日志中都不能出现密码或 JWT 密钥。旧提交若曾包含这些值，不能只靠删除当前文件解决，必须轮换密钥或密码。
- 镜像可以安装 CLI，但不能烘焙 Token。当前受保护的授权目录挂载已验证新容器复用授权；Token 自动刷新与完整重建恢复仍须验收。
- 生产机器需通过 NTP 保持真实时钟准确，并显式对齐三处时间解释：JVM 默认时区设为 `Asia/Shanghai`，MySQL 全局及应用连接会话的 `time_zone` 设为 `+08:00` 或可用的 `Asia/Shanghai`，项目行情转换继续使用代码中的市场时区。上线验收同时查询 JVM `ZoneId.systemDefault()`、MySQL `@@global.time_zone`／`@@session.time_zone` 与 `NOW()`，再和应用日志墙钟对照；JDBC URL 的 `serverTimezone=Asia/Shanghai` 只说明驱动怎样解释时间，不能单独证明数据库会话已经对齐。
- 生产机器还需验证 DNS、TLS 和出站网络；若依赖代理，必须在服务账号及 Java 启动环境中实际验证，不能只以交互式浏览器可访问为依据。

### 3.3 进程和容量

- 当前 `application-prod.properties` 已映射 `MARKET_MODE`、`LONGBRIDGE_CLI_PATH`、`REDIS_HOST` 等变量；配置入口见第 2 节。CLI 路径使用绝对值，避免服务或容器的 PATH 与交互式终端不同。Profile 名称本身不保证安全，须核对最终生效的地址、密钥和业务属性。
- Windows 官方脚本当前默认安装到 `%LOCALAPPDATA%\Programs\longbridge\longbridge.exe`；开发配置会以该位置作为 PATH 缺失时的默认值，生产仍必须显式设置绝对路径。部署验收先执行 `longbridge auth status`；网页显示授权成功但这里仍为 `Token not found` 时，说明 Token 没有写入运行 Spring Boot 的同一 OS 账号，不能把网页状态当作 sidecar 已授权。
- Spring Boot 第一次报价时按需启动一个 `longbridge serve`，应用停止时负责关闭它。进程管理器只监督 Spring Boot，不要再独立常驻第二个 `serve`，否则会与应用争用账号长连接。
- Longbridge 当前规则为一个账号一个行情长连接。Redis 只共享可重建行情和同 Key 锁，不能解除账号连接与供应商限流；没有完成集中式行情网关设计前，同一账号只部署一个启用 `longbridge` 模式的应用副本，不能通过简单增加副本实现扩容。
- 本地开发与云端使用独立后端、MySQL、Redis 和配置；修改本地代码不会自动改变云端已上传的 JAR／静态构建，只有明确发布才更新云端。两边虽可同名 `stock_system`，实际是不同服务器上的库；本地测试固定使用 `stock_system_test`，不把测试请求指向云端业务库。
- 云端有人使用时，同一长桥账号的真实行情连接留给云端；本地普通开发可显式设 `MARKET_MODE=mock`（模拟行情）或 `directory`（仅目录、没有报价），已有本地后端须重启才生效，`test` 默认 mock。多个网页用户共用云端后端的一个行情连接，不是每位用户各连一次。若两边必须同时验证真实行情，需要分别有权限的长桥账号，或另行实现统一行情网关；再次授权同一账号、增加 Redis 都不能消除单连接限制。开发启动方式见 [开发环境](development.md)。
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
9. Redis 开启与关闭两条路径均通过上述 API 验证；部署环境的连接、访问权限、缓存过期、故障恢复与环境 Key 隔离通过验收后，才能把本机隔离测试提升为该环境的上线证据。2026-10-07 已用正式镜像、实际云端 Redis 和隔离 test 后端完成共享命中／不可达降级；上游为替身，真实生产网页由用户确认。

部署故障验收入口与生产只读脚本见 [测试规则 2.4](testing.md#24-部署环境与生产只读验收)。Actuator readiness 与主动告警尚未实现，当前环境验收完成不代表全部生产运维能力完成。

## 5. 每日股票目录同步

目录同步是独立的单次任务，复用运行中后端镜像内的 Java 导入器；它不启动第二个 HTTP 服务或 Longbridge 连接。源码模板在 [目录部署文件](../../deploy/directory/run-directory-sync.sh)、[安装器](../../deploy/directory/install-directory-sync.sh)、[服务](../../deploy/directory/stock-system-directory-sync.service) 和 [定时器](../../deploy/directory/stock-system-directory-sync.timer)。2026-10-07 已完成安装、首轮真实同步并启用定时器，完整验收见 CODE-206G 的日期证据。

部署目录为 `/home/ubuntu/stock-system-deploy/directory/`：`scripts/` 保存经过 SHA-256 校验的允许文件，`venv/` 隔离 Python 依赖，`state/runs/<runId>/` 保存本轮原始下载、日志、标准快照和回执。安装只包含六个 Python／依赖文件、两个 shell 文件和两个 systemd 单元，不上传整个仓库、target、账号、密钥或本地 OAuth 配置。后端仍从现有云端 Secret 读取数据库密码。

初次安装需要 `python3.12-venv`；仅有 `python3 --version` 成功不能证明 `venv` 与 pip 可用。在已核实的 ubuntu／主机和校验过的隔离包目录执行 `sh install-directory-sync.sh`。安装器先拒绝覆盖既有安装，检查后端已经包含新入口，再创建虚拟环境、安装依赖并验证单元；首次同步成功才启用 timer。安装中途失败时，不反复覆盖目录，应定位已经完成的步骤：修复后可启动现有 service，成功后再启用 timer。应用版本发布仍使用仓库外桌面部署工具，不把运维工具搬进业务项目。

timer 按 `Asia/Shanghai` 每天 06:30 触发，附最多五分钟随机延迟，并补跑关机错过的任务；完整任务用 `flock` 排他，跨机器导入还受 MySQL 命名锁保护。采集或覆盖失败不写库，`last-run.json` 保留失败阶段和类别；`last-success.json` 仅在真实提交且回执核对通过后更新。代码与字段机制见 [SPEC-002 设计](../specs/002-stock-research/design.md#42-目录字段接入核对2026-09-08)。

在云端部署目录运行以下命令：第一条确认未来调度，第二条读取本次结果，第三条定位失败阶段；最后一条手动触发同一受锁保护的 service，不能绕开包装器并行运行采集脚本。

```bash
sudo systemctl list-timers stock-system-directory-sync.timer --no-pager
cat directory/state/last-run.json
sudo journalctl -u stock-system-directory-sync.service -n 80 --no-pager
sudo systemctl start stock-system-directory-sync.service
```

暂停后续同步使用 `sudo systemctl disable --now stock-system-directory-sync.timer`；这不撤销已经提交的目录，也不删除股票或研究历史。单元失败和 journal 是当前追溯入口，尚未实现主动告警。后续升级需要独立校验代码与依赖、停用 timer 并保留 state，不能把初次防覆盖安装器当作升级器。

2026-10-08 在本地验证通过后修补了现有云端三个目录 Python 文件：处理原地址 HTTP 307 重试，以及新增已核对身份的覆盖差异；没有更新应用 JAR／前端、修改 Secret／授权或重启容器。新旧文件 SHA-256 核对并在同步锁内替换，旧文件位于 `directory/state/code-backups/20261008-local-schedule-fix/`；原 service 正常同步已返回 APPLIED，详细时间与数量只见 [本轮任务证据](../specs/002-stock-research/tasks.md#2026-10-08-windows-本机每日同步与采集修补)。恢复脚本时同样先确认任务未运行并取得原文件锁，逐文件核对备份哈希，不调用初次防覆盖安装器。Windows 本机调度单独维护在 [本地开发](development.md#813-windows-本机每日调度)，两端各写自己的数据库。

## 6. 回滚和授权失效

- 外部行情故障时，在云端 `compose.backend.yaml` 将 `MARKET_MODE` 改为 `directory`，按第 2 节重建后端：搜索继续使用 MySQL 标准目录，但价格和行情时间为空，添加研究按现有规则失败。当前 dev／prod 均支持该简写映射；不得切到 `mock` 冒充生产行情。
- 若版本升级导致不兼容，回退到已验证 CLI 版本和应用版本，再重新运行第 4 节；不要删除数据库研究历史。
- Token 过期或撤销时，在服务账号下重新执行官方授权；不修改源码、不把 Token 复制到配置文件。若怀疑泄漏，先从 Longbridge 安全设置撤销授权，再清理受影响运行环境并重新授权。
- 当 Java SDK 提供受支持的 OAuth/Token 入口时，可替换 `LongbridgeQuoteGateway` 实现；切换前保留 sidecar 回滚路径并做同一套接口、故障和凭据验收。
