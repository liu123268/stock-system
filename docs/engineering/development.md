# 本地开发与启动说明

更新时间：2026-09-10

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

双击项目中的 `scripts/start-dev.cmd`，它会明确激活 `dev` profile，并：

1. 检查8082是否已有后端。
2. 检查8083是否已有前端。
3. 只启动尚未运行的程序。
4. 为前端和后端各打开一个终端，方便查看日志并使用 `Ctrl+C` 停止。

需要运行真实浏览器自动化时，改为双击 `scripts/start-test.cmd`。它明确激活 `test` profile，并使用独立的 18082/18083 端口，避免误复用正在连接开发库的 8082 后端。IntelliJ 中也提供 `StockSystemApplication`（dev）和 `StockSystemApplicationTest`（test）两套共享运行配置。

配置职责如下：`application.properties` 保存两边共用的配置，各 profile 文件保存数据库地址和该环境的行情模式。默认 profile 是 dev，所以普通启动仍进入开发库；自动化测试必须显式进入 test。开发默认 `stock-system.market.mode=longbridge`，测试固定 `mock`；开发可通过 `MARKET_MODE=directory` 暂停外部报价，或用 `MARKET_MODE=mock` 明确进入模拟模式。

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

`scripts/probe-stock-directory.py` 负责公开目录下载，`scripts/prepare-stock-directory.py` 将多种字段批量转换为统一 JSON；都不连接数据库。Python 需要 pandas、openpyxl、`opencc-python-reimplemented==0.1.7`，本机捆绑环境已有前两者，本次 OpenCC 仅装入忽略目录 `target/market-python`。后端无需安装 Python 库。

准备结果位于 `target/market-directory-probe/directory.json`。先查看采集状态和 coverageNote；失败不能使用旧文件假称同步成功。target 是本地中间产物，清理后需要重新采集。当前北交所使用巨潮 920xxx A 股名单，完整覆盖核对未完成，不以旧 43/83/87 前缀猜测所属交易所。

Java 源文件启动入口 `scripts/ImportStockDirectory.java` 调用正式 `StockDirectoryImporter`。在项目根目录、编译完成且 classpath 包含 target/classes 和项目依赖时执行：`java --class-path <classpath> scripts/ImportStockDirectory.java test verify <目录JSON>`，会核对实际测试库、迁移、导入、重复导入和回滚；`dev inspect` 只读核对开发库，`dev apply <目录JSON>` 才执行真实开发数据导入。凭据从现有配置/环境读取，不放入命令参数。它不启动应用或端口；迁移前检查 catalog，数据批次异常整体回滚。apply 在 target 保存原股票信息备份、核对已有 ID 不变及全部导入字段后提交，不删除目录缺失记录。

本轮生成的 `target/directory-classpath.txt` 来自实际 Maven 测试报告的 java.class.path，仅为本机运行辅助文件。它会随依赖与工作区变化，不能作为永久部署配置。已经运行的 IntelliJ 后端需要重启才能加载新的 Java 搜索逻辑；导入本身立即对数据库可见。

## 9. Longbridge 真实报价

### 9.1 这套方案怎样运行

本地数据流是：

```text
搜索：Vue → Spring Boot → MySQL 标准目录 → 一次批量 quote.quote → 返回候选和真实价格
添加：Vue → Spring Boot → 再取可信报价 → 研究事务保存不可变开始快照
K 线：Vue 展开已加入股票 → Spring Boot → quote.candlesticks／history_candlesticks_by_date → 当前图表
分时：Vue 切换分时／日期 → Spring Boot → quote.intraday 或历史 1 分钟 K 线 → 当前图表
                                                       ↑
                                          一个长期运行的 longbridge serve
```

Spring Boot 不会为每只股票执行一遍命令，也不会为每个页面请求重新登录。第一次需要报价时，它启动一个官方 `longbridge serve` 子进程；后续请求通过 stdin/stdout JSON-RPC 复用该进程，应用关闭时一并关闭。搜索最多 30 个目录候选，但发送的是一个 `symbols` 批量请求。相同代码 5 秒内重复读取会命中本进程缓存；Java 端还限制最多 5 并发且相邻上游请求至少 100ms。

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
| `LONGBRIDGE_CACHE_TTL` | `5s` | 相同报价批次或当天分时股票的进程内短缓存；不是历史数据缓存 |
| `LONGBRIDGE_CANDLESTICK_CACHE_TTL` | `1m` | 相同股票、周期、数量和最新／历史日期边界的 K 线进程内短缓存；重启即失效 |

CLI 未安装、当前 OS 账号未授权、日志或 Token 目录无权限、网络超时、上游报错或 sidecar 退出时，搜索仍返回 MySQL 可信目录，但价格和行情时间为空；添加研究按 `MARKET_QUOTE_UNAVAILABLE` 失败且不写业务记录。系统不会静默改用 Mock，也不会把缓存过期数据标成实时行情。异常退出后的当前请求失败，下一次报价请求才尝试重新启动 sidecar。

`longbridge serve` 本身包含交易方法，但项目 Java 客户端只允许 `quote.quote`、`quote.candlesticks`、`quote.history_candlesticks_by_date`、`quote.intraday` 和 `quote.trading_days` 五个固定只读方法，没有向 Controller 或前端开放任意方法。K 线 `301607` 会作为 HTTP 429 和 `MARKET_KLINE_LIMIT_EXCEEDED` 返回；历史分时遇到该码返回 `MARKET_INTRADAY_HISTORY_LIMIT_EXCEEDED`，遇到分钟历史起始日期限制则返回 HTTP 422、稳定错误码及最早可查日期。近一年 A 股／港股日期通过官方交易日日历判断，休市日返回 400；当天点还按市场时区过滤为请求日期。这些错误可在页面错误区、开发者工具 Network 响应或后端 API 调用结果中看到。部署到服务器还需要固定服务账号、持久化授权、实例数和回滚设计，不能把本机登录成功等同于部署完成；完整清单见 [部署说明](deployment.md#3-longbridge-sidecar-部署前置条件)。
