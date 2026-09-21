# 本地开发与启动说明

更新时间：2026-09-11

## 1. “前端”到底是什么

前端是运行在浏览器中的页面程序，负责输入框、按钮、布局、动画和用户交互。本项目的前端源代码在 `frontend` 目录中，使用Vue和TypeScript编写。

源代码只是磁盘上的文件，本身不会监听网络端口。执行 `npm run dev` 后，Vite才会启动一个正在运行的进程：

```text
Vue源代码（磁盘文件）
  -> Vite开发服务器（运行中的程序）
  -> 监听8083端口
  -> 浏览器可以访问
```

可以把源代码理解为菜谱，把正在运行的Vite理解为按照菜谱工作的厨房。只有菜谱没有厨房，浏览器就拿不到页面。

## 2. 为什么以前有时不用单独启动前端

旧版HTML放在Spring Boot的 `static` 目录中，页面和Java后端由同一个Spring Boot进程提供，所以只启动后端就能看到旧页面。

现在采用前后端分离：

```text
Vue/Vite：8083
Spring Boot：8082
MySQL：3306
Redis（WSL2）：6380
```

开发阶段Vue和Spring Boot是两个独立程序，因此需要各自运行。

## 3. 启动频率

不是每改一行代码都重新启动。

通常在一次开发会话开始时启动一次，然后保持终端窗口运行：

- 修改Vue组件或CSS：Vite热更新，通常不用重启。
- 修改普通前端TypeScript：通常不用重启。
- 修改Vite配置、安装依赖或修改环境变量：建议重启前端。
- 修改Java代码：当前没有启用热部署，需要重启Spring Boot。
- 修改普通数据库数据：不用重启。
- 新增Flyway迁移：重启后端，让Flyway执行。
- 关闭终端、重启电脑、主动按 `Ctrl+C`：下次开发需要重新启动。

判断是否正在运行：

- 前端终端显示 `Local: http://localhost:8083/`，说明Vite正在运行。
- IntelliJ运行窗口显示Spring Boot处于运行状态，说明后端正在运行。
- 浏览器能打开8083不等于后端一定运行；登录还需要8082也正常。

## 4. 一键启动

数据库环境不靠手工改配置切换，而是由启动入口选择：

| 用途 | 启动入口 | 后端 / 前端 | 数据库 | 数据含义 |
| --- | --- | --- | --- | --- |
| 自己开发、手工体验 | `scripts/start-dev.cmd` | `8082` / `8083` | `stock_system` | 需要保留的开发数据 |
| 自动浏览器验收 | `scripts/start-test.cmd` | `18082` / `18083` | `stock_system_test` | 可回滚、可精确清理的测试数据 |

在已经设置上述环境变量的同一个 PowerShell 窗口运行 `.\scripts\start-dev.cmd`，它会明确激活 `dev` profile，并：

1. 检查8082是否已有后端。
2. 检查8083是否已有前端。
3. 只启动尚未运行的程序。
4. 为前端和后端各打开一个终端，方便查看日志并使用 `Ctrl+C` 停止。

需要运行真实浏览器自动化时，在同一个已设置环境变量的窗口运行 `.\scripts\start-test.cmd`。它明确激活 `test` profile，并使用独立的 18082/18083 端口，避免误复用正在连接开发库的 8082 后端。当前本机 `.run` 中也有 `StockSystemApplication`（dev）和 `StockSystemApplicationTest`（test）两套 IntelliJ 运行配置，但该目录被 Git 忽略；从 IntelliJ 启动时也必须在运行配置中提供这三个环境变量。

配置职责如下：`application.properties` 保存两边共用的非敏感配置，各 profile 文件保存数据库地址和该环境的行情模式；本机数据库用户名固定为原有的 `root`，它不是秘密，生产可用 Spring Boot 标准属性 `SPRING_DATASOURCE_USERNAME` 覆盖为最小权限账号。数据库密码和 JWT 签名密钥只从 `DB_PASSWORD`、`JWT_SECRET` 环境变量读取，不能写回 properties 或启动参数。默认 profile 是 dev，所以普通启动仍进入开发库；自动化测试必须显式进入 test。开发默认 `stock-system.market.mode=longbridge`，测试固定 `mock`；开发可通过 `MARKET_MODE=directory` 暂停外部报价，或用 `MARKET_MODE=mock` 明确进入模拟模式。

启动前在同一个 PowerShell 窗口提供数据库密码和至少 32 个 UTF-8 字节的开发密钥；尖括号内容需要替换为你自己的值，不能把示例原样复制：

```powershell
$env:DB_PASSWORD = '<本机数据库密码>'
$env:JWT_SECRET = '<仅本机使用、至少32字节的随机密钥>'
.\scripts\start-dev.cmd
```

测试也使用同一组密码和密钥环境变量，但启动 `scripts\start-test.cmd` 后会连接 `stock_system_test`。启动脚本会先检查两个变量是否存在，避免因为遗漏配置而误以为数据库或认证代码故障；关闭当前 PowerShell 窗口后，这些变量不会自动写入系统环境。

环境变量不是加密保险箱：当前 PowerShell 会话中的 `$env:DB_PASSWORD` 主要是避免把密码写进 Git 和启动参数，拥有本机权限的程序仍可能读取进程环境；如果把它设置成 Windows 用户／系统永久环境变量，也可能以明文形式保存在系统配置中。因此本地学习环境只能把它视为“降低误提交风险”的做法，生产环境必须由部署平台的 Secret 或密钥管理服务注入，不能把 `.env` 或普通环境变量文件当作长期密码库。

## 5. 为什么正式上线后不用手工运行 `npm run dev`

Vite开发服务器只服务于开发阶段。上线前执行生产构建，会得到普通HTML、CSS和JavaScript文件。正式环境通常由Nginx自动提供这些文件，并将 `/api` 代理到Spring Boot。

```text
开发：Vite 8083 + Spring Boot 8082
生产：Nginx 80/443 -> 前端文件与后端API
```

服务器上的Nginx和Java应用会配置为服务启动时自动运行，所以最终用户不需要打开任何命令行。

## 6. 如何停止

在对应终端中按 `Ctrl+C`：

- 前端终端按一次，停止8083。
- 后端终端按一次，停止8082。

不要通过任务管理器批量结束所有Java或Node进程，因为电脑上可能还有IntelliJ、DataGrip或其他项目在使用它们。

## 7. 自动化检查与浏览器验收

测试命令、分层选择、写入前核对和数据清理统一见 [测试与验收规则](testing.md)。普通验收不用 format 自动改写文件。

使用本文件的测试启动入口后，在 frontend 目录运行 `npm.cmd run test:e2e`，它调用真实浏览器脚本。自定义端口通过 `VITE_DEV_PORT`、`VITE_API_TARGET`、`FRONTEND_URL`、`BACKEND_URL` 覆盖；Chrome 和 MySQL 路径通过 `CHROME_PATH`、`MYSQL_CLIENT_PATH` 设置，不修改源码。

PowerShell 使用 npm.cmd，Maven 使用系统 mvn。本机 Maven 缓存位于工作区的 .m2repo；测试命令以 testing.md 为准。这里的绝对路径描述本机环境，不作为可移植部署配置。

## 8. 股票目录导入

`scripts/probe-stock-directory.py` 负责公开目录下载，`scripts/prepare-stock-directory.py` 将多种字段批量转换为统一 JSON；都不连接数据库。Python 需要 pandas、openpyxl、`opencc-python-reimplemented==0.1.7` 和 `pypinyin==0.55.0`；本地依赖安装在忽略目录 `target/market-python`，后端无需安装 Python 库。拼音使用词组词典生成小写首字母，人工核实的多音字补充读音仍写入 `searchAliases`。

准备结果位于 `target/market-directory-probe/directory.json`。先查看采集状态和 coverageNote；失败不能使用旧文件假称同步成功。target 是本地中间产物，清理后需要重新采集。当前北交所使用巨潮 920xxx A 股名单，完整覆盖核对未完成，不以旧 43/83/87 前缀猜测所属交易所。

Java 源文件启动入口 `scripts/ImportStockDirectory.java` 调用正式 `StockDirectoryImporter`。在项目根目录、编译完成且 classpath 包含 target/classes 和项目依赖时执行：`java --class-path <classpath> scripts/ImportStockDirectory.java test verify <目录JSON>`，会核对实际测试库、迁移、导入、重复导入和回滚；`dev inspect` 只读核对开发库，`dev apply <目录JSON>` 才执行真实开发数据导入。凭据从现有配置/环境读取，不放入命令参数。它不启动应用或端口；迁移前检查 catalog，数据批次异常整体回滚。apply 在 target 保存原股票信息备份、核对已有 ID 不变及全部导入字段后提交，不删除目录缺失记录。

只补派生拼音且不重新抓市场时，先用 `dev export <JSON>` 从现有开发库只读导出可信目录身份，再运行 `python scripts/prepare-stock-directory.py --enrich-pinyin <输入JSON> <输出JSON>`，随后先用 `test verify` 验证整批并回滚，最后才用 `dev backfill <输出JSON>`。backfill 会应用新迁移、在 target 备份原行，并只更新 `pinyin_initials`；任一代码未匹配就整批回滚，不改 `last_synced_at`、来源、状态、ID 或研究关联。

`target/directory-classpath.txt` 是目录导入阶段从 Maven 测试报告的 java.class.path 提取的本机运行辅助文件。它会随依赖与工作区变化，不能作为永久部署配置。已经运行的 IntelliJ 后端需要重启才能加载新的 Java 搜索逻辑；导入本身立即对数据库可见。

## 9. Longbridge 真实报价

### 9.1 这套方案怎样运行

本地数据流是：

```text
Vue → Spring Boot（权限、目录与参数校验）→ 行情网关
                                          ├─ Redis 命中 → 返回缓存行情
                                          └─ 未命中／关闭／故障
                                               → 进程内短缓存
                                               → 必要时由 longbridge serve 受控回源
```

搜索先查 MySQL 标准目录，再批量补报价；添加仍由服务端取可信报价后写不可变开始快照；K 线和分时只在用户展开股票时按需读取。Spring Boot 不会为每只股票执行一遍命令，也不会为每个页面请求重新登录。第一次真正需要回源时，它启动一个官方 `longbridge serve` 子进程；后续请求通过 stdin/stdout JSON-RPC 复用该进程，应用关闭时一并关闭。搜索最多 30 个目录候选，但发送的是一个 `symbols` 批量请求。缓存分层及失败边界见 [SPEC-002 设计](../specs/002-stock-research/design.md#46-redis-共享行情缓存与访问保护)。

当前[官方定价说明](https://open.longbridge.com/zh-CN/pricing)写明 OpenAPI 不收激活或接口使用费，因此一次报价不是“消耗一张 OAuth 授权码”或按次扣费；需要控制的是调用速率、长连接数量和各市场行情权限。授权码只用于建立/刷新授权，Token 会复用。[实时报价接口](https://open.longbridge.com/zh-CN/docs/quote/pull/quote)当前没有公开的每日或每月不同标的总量硬上限；单次最多接收 500 个代码，所以理论上查询 5000 只需要拆成至少 10 个请求并遵守每秒 10 次、并发 5 次，不能一次请求或同时订阅 5000 只。项目搜索只取最多 30 个候选并合并成一次请求。

[K 线 CLI 官方说明](https://open.longbridge.com/zh-CN/docs/cli/market-data/kline)支持 `1m/5m/15m/30m/1h/day/week/month/year`，并与[历史 K 线接口](https://open.longbridge.com/zh-CN/docs/quote/pull/history-candlestick)记录了另一套额度：按自然月统计查询过的不同股票代码，基础开户档为 100 只，随账户等级最高到 3000 只；同一股票当月切换周期、回看不同日期或重复查询都只计一个不同标的，但每次未命中项目缓存的读取仍是一笔上游请求。历史 K 线还有每 30 秒最多 60 次的接口频率限制。当前页面已开放全部九种周期，并只在用户展开已经加入空间的单只股票时加载；当天分时同样按需读取，历史分时复用 1 分钟历史 K 线，所以也计入历史 K 线不同标的额度。搜索候选和整张共享列表不会预取这些序列。费用、额度与行情权限可能变化，上线前仍按 [部署检查](deployment.md) 重新核对，不能据此承诺所有市场永久免费实时。

官方文档的“我的行情权限”链接进入 [Longbridge Account Center](https://open.longbridge.com/account)，可核对账号权限和档位；当前公开文档和 CLI 帮助没有提供“本月已用／剩余不同 K 线标的数”的查询命令，不能承诺该页一定显示实时已用计数。项目也不自称掌握供应商全局余量，因为同一账号可能被其他客户端调用；当前只能避免搜索预取、复用同股缓存，并在上游限制错误时明确显示。以后若做本地用量看板，只能标为项目自身请求估算，不能冒充 Longbridge 官方剩余额度。

### 9.2 第一次准备

在运行 Spring Boot 的同一个 Windows 账号下完成以下核对：

1. `longbridge --version`：确认 CLI 已安装且当前终端能找到它；本机已验证版本为 `v0.28.5`。
2. `longbridge auth login`：只在首次使用、Token 失效或授权被撤销时完成浏览器授权，不是每次查行情都执行。
3. `longbridge quote 600519.SH`：先把账号、网络和 A 股只读权限与项目代码分开验证；能返回价格后再启动 Spring Boot。

本机 `longbridge.exe` 为 16,547,328 字节（约 15.8 MiB）；项目不复制它，也不读取 CLI 保存在 `~/.longbridge/openapi/tokens/<client_id>` 的 Token。若新终端能运行 CLI、但从旧 IntelliJ 启动的 Java 找不到它，原因通常是 IntelliJ 没继承更新后的 PATH。重启 IntelliJ，或在运行配置中设置：

```text
LONGBRIDGE_CLI_PATH=C:\实际安装位置\longbridge.exe
```

项目在 Windows 下还会从当前账号的 `LOCALAPPDATA\Programs\longbridge\longbridge.exe` 查找官方默认安装位置；找不到时才依赖 PATH。`LONGBRIDGE_CLI_PATH` 是可执行文件路径，不是 Token。不能把授权码或 Token 放进环境变量、启动参数、项目配置或日志。

### 9.3 可配置项与降级

| 环境变量 | 默认值 | 作用 |
| --- | --- | --- |
| `MARKET_MODE` | `longbridge` | `longbridge` 取真实报价；`directory` 只搜目录；`mock` 只用于明确模拟 |
| `LONGBRIDGE_CLI_PATH` | `longbridge` | CLI 可执行文件；服务/IDE 找不到 PATH 时设绝对路径 |
| `LONGBRIDGE_REQUEST_TIMEOUT` | `5s` | 单个 JSON-RPC 请求等待上限 |
| `LONGBRIDGE_CACHE_TTL` | `5s` | 报价按股票、当天分时按股票与市场日期复用的进程内短缓存；不是历史数据缓存 |
| `LONGBRIDGE_CANDLESTICK_CACHE_TTL` | `1m` | 相同股票、周期、数量和最新／历史日期边界的 K 线进程内短缓存；重启即失效 |
| `MARKET_REDIS_ENABLED` | `true` | 是否启用共享行情缓存；测试 profile 固定关闭，不能用它绕过业务权限校验 |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6380` | Spring Boot 连接的 Redis 地址；Windows 若依使用 6379 时，stock-system 使用 WSL 的 6380 |

Redis 的 TTL、锁、冷却和抖动参数及含义集中见 [缓存设计](../specs/002-stock-research/design.md#46-redis-共享行情缓存与访问保护)，完整环境变量映射见 [开发配置](../../src/main/resources/application-dev.properties)。修改后要重启 Spring Boot 才会重新加载；尤其不能把当前历史接口的长 TTL 当成已经验证了“所有数据均已闭市”。

CLI 未安装、当前 OS 账号未授权、日志或 Token 目录无权限、网络超时、上游报错或 sidecar 退出时，搜索仍返回 MySQL 可信目录，但价格和行情时间为空；添加研究按 `MARKET_QUOTE_UNAVAILABLE` 失败且不写业务记录。系统不会静默改用 Mock，也不会把缓存过期数据标成实时行情。异常退出后的当前请求失败，下一次报价请求才尝试重新启动 sidecar。

`longbridge serve` 本身包含交易方法，但项目 Java 客户端只允许 `quote.quote`、`quote.candlesticks`、`quote.history_candlesticks_by_date`、`quote.intraday` 和 `quote.trading_days` 五个固定只读方法，没有向 Controller 或前端开放任意方法。K 线 `301607` 会作为 HTTP 429 和 `MARKET_KLINE_LIMIT_EXCEEDED` 返回；历史分时遇到该码返回 `MARKET_INTRADAY_HISTORY_LIMIT_EXCEEDED`，遇到分钟历史起始日期限制则返回 HTTP 422、稳定错误码及最早可查日期。近一年 A 股／港股日期通过官方交易日日历判断，休市日返回 400；当天点还按市场时区过滤为请求日期。这些错误可在页面错误区、开发者工具 Network 响应或后端 API 调用结果中看到。部署到服务器还需要固定服务账号、持久化授权、实例数和回滚设计，不能把本机登录成功等同于部署完成；完整清单见 [部署说明](deployment.md#3-longbridge-sidecar-部署前置条件)。

## 10. Redis 本地学习环境

Windows 端的 Spring Boot 通过 `localhost:6380` 访问 Ubuntu WSL2 中的 Redis；Windows 若依项目保留使用 `localhost:6379`，两者互不干扰。本机学习环境已在 Ubuntu 安装 Redis Server 与真正的 `redis-cli`；新机器需要另行安装。以下命令在 Windows PowerShell 运行，`wsl.exe -d Ubuntu --` 表示交给 Ubuntu 执行。先用 `redis-cli -p 6380 ping` 检查：返回 `PONG` 表示服务已经运行，无需再启动；确认尚未运行时才执行带有端口、临时目录和缓存持久化设置的直接启动命令，再检查一次。安装、启动服务和检查连通是三件不同的事。

```powershell
wsl.exe -d Ubuntu -- redis-cli -p 6380 ping
```

本机已配置当前 Windows 用户登录时直接启动 stock-system 的 WSL Redis。登录启动项直接调用 `wsl.exe`，使用端口 `6380`、`/tmp` 临时目录并关闭 RDB/AOF；它不会触碰 Windows 若依使用的 `6379`。如果要撤销当前用户的自动启动项，在 PowerShell 执行：

```powershell
Remove-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name 'StockSystemWSLRedis6380'
```

这是登录时启动，不是每次请求启动。若 Windows 或 WSL 被完全关闭，下一次登录时会再次直接启动 Redis；如果手动停止 Redis，当前登录会话不会自动反复拉起它。

只有 `ping` 不是 `PONG`、且确认没有其他任务正在使用 Redis 时，才执行启动命令，再次检查连通性：

```powershell
wsl.exe -d Ubuntu -- redis-server --port 6380 --bind 127.0.0.1 --dir /tmp --save "" --appendonly no --daemonize yes
wsl.exe -d Ubuntu -- redis-cli -p 6380 ping
```

开发结束后，如 Redis 是本次启动且没有其他任务在复用，可关闭它并用第二条命令确认连接已拒绝。`shutdown nosave` 会停止服务且不生成此次关闭时的持久化快照，只适用于这里可重建的本地学习缓存；它不是生产数据备份或清理方案。

```powershell
wsl.exe -d Ubuntu -- redis-cli -p 6380 shutdown nosave
wsl.exe -d Ubuntu -- redis-cli -p 6380 ping
```

### 10.1 用图形界面观察这一个 Redis

桌面 Redis 图形工具连接的是“Redis 服务”，不是连接 Windows 或 WSL 的文件夹。新建连接时填写：地址 `127.0.0.1`、端口 `6380`、密码留空、用户名留空；连接名称可以写“WSL Redis（stock-system）”。`6379` 是 Windows 若依的另一实例，不要在这个连接里填写。连接成功后，左侧看到的 Key 就是 WSL Redis 当前数据库的内容。

PowerShell 也可以用下面几组只读命令观察同一实例。它们都带 `-p 6380`，否则 `redis-cli` 会默认找 `6379`，容易误查到若依 Redis：

```powershell
# 服务版本、进程和运行状态
wsl.exe -d Ubuntu -- redis-cli -p 6380 info server

# 当前 Redis 使用了多少内存；DBSIZE 是 Key 的数量，不是字节数
wsl.exe -d Ubuntu -- redis-cli -p 6380 info memory
wsl.exe -d Ubuntu -- redis-cli -p 6380 dbsize

# 只按项目前缀渐进扫描 Key；不要在共享或生产 Redis 上使用 KEYS *
wsl.exe -d Ubuntu -- redis-cli -p 6380 scan 0 match "stock-system:market:*" count 100

# 把 <key> 替换成上一步看到的完整 Key
wsl.exe -d Ubuntu -- redis-cli -p 6380 ttl "<key>"
wsl.exe -d Ubuntu -- redis-cli -p 6380 memory usage "<key>"
wsl.exe -d Ubuntu -- redis-cli -p 6380 get "<key>"
```

`TTL` 的单位是秒：正数表示还剩多久，`-1` 表示该 Key 没有过期时间，`-2` 表示 Key 不存在。行情 Key 会因为 TTL 到期自动删除；因此通常不需要每天手工清理。确实只想删除一个确定的测试 Key 时使用 `del "<key>"`，不要凭感觉删除。`flushdb` 会清空当前数据库的全部 Key，本机也只有在确认没有其他任务使用它时才允许执行；Windows 若依的 `6379` 不会被这个命令清理。

`INFO memory` 中的 `used_memory_human` 是当前占用，`maxmemory_human:0B` 表示没有设置 Redis 的内存上限，不表示 Redis 占用了 0 字节；是否需要容量策略要结合部署环境另行决定。本机 `/tmp`、关闭 RDB/AOF 的设置意味着 Redis 重启后 Key 为空，这是缓存重建而非数据丢失；股票身份、研究周期和观点仍以 MySQL 为准。

当前测试类已经把数据库集成测试显式绑定到 `test` profile，普通回归不依赖真实 Redis；这不是 Maven 自动选择了 test profile。单元测试和显式 Redis 集成测试怎样运行、分别能证明什么见 [测试说明](testing.md#22-单元测试怎样运行与阅读)。关闭 Redis 后应用的降级机制及尚未完成的验证仍以缓存设计和对应 tasks 为准。
