# 本地开发与启动说明

更新时间：2026-10-14

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

真实 longbridge 模式的后端每天 06:35 后分批检查股票状态，启动晚于该时间会补查；目录同步稍晚完成时也会纳入新记录。状态查询复用这个后端的 SDK 连接，因此本地后端关闭时，Windows 目录任务仍能更新股票身份，但不能查询长桥状态；重新启动后补查。Mock 和 directory 模式不调用真实状态接口。页面状态核实时间与报价／K 线时间分开显示，查不到或超过 36 小时未核实显示待核实，详见 [状态设计](../specs/002-stock-research/design.md#每日交易状态查询2026-10-08)。

不是每改一行代码都重新启动。

通常在一次开发会话开始时启动一次，然后保持终端窗口运行：

- 修改Vue组件或CSS：Vite热更新，通常不用重启。
- 修改普通前端TypeScript：通常不用重启。
- 修改Vite配置、安装依赖或修改环境变量：建议重启前端。
- 修改Java代码：当前没有启用热部署，需要重启Spring Boot。
- 修改普通数据库数据：不用重启。
- 新增Flyway迁移：重启后端，让Flyway执行。
- 关闭终端、重启电脑、主动按 `Ctrl+C`：下次开发需要重新启动。

2026-10-12 主源码已完成 Boot 4／Spring AI 框架接入，当前版本只见 [技术栈](../architecture/technology-stack.md#1-后端与数据)。IDE／Maven 需重新载入 pom，再重启后端；前端仍沿用原启动方式。版本默认模型关闭，正常启动不需要模型密钥；004 个人助手的本地启用方法见下方“研究助手的入口与本地启用”，费用边界见 SPEC-004 设计第 5 节。源码更新不代表云端发布。

判断是否正在运行：

- 前端终端显示 `Local: http://localhost:8083/`，说明Vite正在运行。
- IntelliJ运行窗口显示Spring Boot处于运行状态，说明后端正在运行。
- 浏览器能打开8083不等于后端一定运行；登录还需要8082也正常。

如果新启动的 Spring Boot 报 `Port 8082 was already in use`，先读日志末尾的 `APPLICATION FAILED TO START` 和 `Description`：失败的是**这次新启动的进程**，不代表 8082 上没有旧进程。在项目根目录或任意终端执行 `netstat -ano | findstr :8082`，找到 `LISTENING` 行末尾的 PID，再核对它是否属于本项目；只停止确认的旧后端，然后重新启动。若浏览器仍能登录，说明请求可能正由这个旧后端处理，不能据此判断刚修改的 Java 代码已经生效。Vite 的 8083 页面可以继续显示，且前端热更新不会替换运行中的 Java 后端；后端重启成功应看到 `Tomcat started on port 8082` 和 `Started StockSystemApplication`。

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

### 研究助手的入口与本地启用

入口是“研究空间 → 共享自选 → 某只股票的研究详情 → 对应研究周期 → 研究助手”，点击进入该股票／周期的独立问答页。研究详情只留入口，长历史在助手内部滚动，底部输入区保持可见；“← 返回研究观点”回到原周期，普通往返保留本次登录会话内的未发送草稿，刷新浏览器会清除这种内存草稿。“开始梳理”整理该周期可见的成员观点；“观点日期范围”展开日期条件，留空读取完整周期；“继续提问”可直接输入，不要求先生成梳理。引用在问答页打开当前可见原文或生成时行情／财务依据，关闭后继续原聊天位置。“查看更早记录”只读分页；已完成问答继续由数据库恢复，清空用于主动删除当前对象记录，不是缩短研究页的必要操作。

财务查询使用同一助手页，没有单独财务页面；当前核实范围仅茅台、腾讯 2025 年报及 2024 比较列。例如可在这两只股票的助手页问“2025 年营业收入和归母净利润同比如何？”，再点击“查看财务数据依据”核对单位、原报链接与页码。其他股票或期间仍需反馈资料缺口。

`dev` 现在可选导入根目录 `.env.deepseek-local.properties` 和 `.env.research-agent-local.properties`。前者沿用实验时已填的 `deepseek.api-key`，不用再次复制密钥；后者只管理模型开关与正式页面累计预算。其他机器可从 [默认关闭的模板](../../scripts/research-agent-local.properties.example) 复制后再按已有授权修改。本机在 2026-10-14 得到正式页面独立累计 10 元授权，忽略的开关文件已设置：

```properties
local.research-agent.chat-model=deepseek
local.research-agent.budget-yuan=10
```

`deepseek` 使 Spring 创建模型服务；`10` 由 MySQL 账本控制累计费用占用，重启／清空个人记录不重置额度。这是独立于原实验 JSONL 的正式预算，不能把实验授权自动扩大。`DEEPSEEK_API_KEY`、`RESEARCH_AGENT_CHAT_MODEL`、`RESEARCH_AGENT_BUDGET_YUAN` 环境变量优先；存在 Key 本身不会启用付费回答，模板及没有本地开关时仍为 none／0。上述本地导入只影响 dev，生产继续使用部署 Secret。

修改配置后，在 IDEA 的 `StockSystemApplication`（dev）中停止并重新运行后端，等待 `Started StockSystemApplication`；保持原 Vite 前端运行。回到独立助手页，点“重新检查”或刷新页面，关闭提示消失且非空问题的“发送问题”可用后，再手动发送。未启用时也可写草稿，但不会发送；“重新检查”只读状态并保留问题，不会自动调用模型。页面刷新不能替代后端重启。真实回答质量仍按任务单独核验，配置已接通不代表所有问题均已验收。

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

历史手工准备结果位于 `target/market-directory-probe/directory.json`。先查看采集状态和 coverageNote；失败不能使用旧文件假称同步成功。target 是本地中间产物，清理后需要重新采集。北交所使用巨潮 920xxx A 股名单，并与完整分页对照源核对集合，不以旧 43/83/87 前缀猜测所属交易所；具体覆盖证据见 CODE-206G。

Java 源文件启动入口 `scripts/ImportStockDirectory.java` 调用正式 `StockDirectoryImporter`。在项目根目录、编译完成且 classpath 包含 target/classes 和项目依赖时执行：`java --class-path <classpath> scripts/ImportStockDirectory.java test verify <目录JSON>`，会核对实际测试库、迁移、导入、重复导入和回滚；`dev inspect` 只读核对开发库，`dev apply <目录JSON>` 才执行真实开发数据导入。凭据从现有配置/环境读取，不放入命令参数。它不启动应用或端口；迁移前检查 catalog，数据批次异常整体回滚。apply 在 target 保存原股票信息备份、核对已有 ID 不变及全部导入字段后提交，不删除目录缺失记录。

只补派生拼音且不重新抓市场时，先用 `dev export <JSON>` 从现有开发库只读导出可信目录身份，再运行 `python scripts/prepare-stock-directory.py --enrich-pinyin <输入JSON> <输出JSON>`，随后先用 `test verify` 验证整批并回滚，最后才用 `dev backfill <输出JSON>`。backfill 会应用新迁移、在 target 备份原行，并只更新 `pinyin_initials`；任一代码未匹配就整批回滚，不改 `last_synced_at`、来源、状态、ID 或研究关联。

`target/directory-classpath.txt` 是目录导入阶段从 Maven 测试报告的 java.class.path 提取的本机运行辅助文件。它会随依赖与工作区变化，不能作为永久部署配置。已经运行的 IntelliJ 后端需要重启才能加载新的 Java 搜索逻辑；导入本身立即对数据库可见。

### 8.1 每日同步入口与打包后验证

新编排入口 `python -B scripts/sync-stock-directory.py` 先采集、核对并生成新快照，默认不连接数据库；输出 `COLLECTED` 只证明采集核对完成。每次结果在 `target/directory-sync/runs/<runId>/`，最近尝试和最近已核对结果分别为 `last-run.json`、`last-verified.json`；只有数据库正式提交后的 `APPLIED` 才产生或替换 `last-success.json`。本地 Python 依赖见 [目录依赖清单](../../scripts/directory-requirements.txt)，使用 `target/market-python` 时，需让当前 Python 进程的 `PYTHONPATH` 包含该目录。

在项目根目录完成 Maven 打包后，可用以下 PowerShell 进行测试库完整验证；它通过 argv 数组传参，目录 JSON 经 stdin 传递，不将密码或整段 shell 命令拼进参数：

```powershell
$directoryCommand = @('java', '-Dloader.main=com.stock.system.stock.directory.StockDirectorySyncCommand', '-cp', 'target/stock-system-0.0.1-SNAPSHOT.jar', 'org.springframework.boot.loader.launch.PropertiesLauncher', 'test', 'verify')
$directoryCommandJson = ConvertTo-Json -Compress -InputObject $directoryCommand
python -B scripts/sync-stock-directory.py --import-command-json $directoryCommandJson
```

`test verify` 核对实际 `stock_system_test`，导入两次后整体回滚；只有 Java 回执的行数和快照哈希一致才返回 `VERIFIED`。它不执行新迁移，须先按测试规则运行测试后端或 Maven，确认 schema 当前。生产的 `prod apply` 在容器中读取现有 Secret，实际提交到云端 `stock_system`；本地 `dev apply` 也是真实数据操作，不能用来执行自动测试。云端定时调度、锁及运维操作只见 [部署说明](deployment.md#5-每日股票目录同步)；Windows 本机调度见下一节。

#### 8.1.3 Windows 本机每日调度

[安装脚本](../../scripts/install-local-directory-sync.ps1) 为当前 Windows 用户准备忽略的 `.local/stock-directory/`：独立 Python venv、已打包的 Java `app.jar`、同步脚本及不含密码的运行路径配置。该目录在 target 外，`mvn clean` 不影响任务；它运行安装时的代码副本，修改同步代码或重新打包后应重新运行安装器更新，不能把 IDEA 热更新当作计划任务自动升级。

任务名 `StockSystem-DirectorySync`，北京时间每天 06:30，加当前用户登录触发与错过补跑；06:30 前登录不提前采集，当天已经成功则跳过。使用已登录用户的普通权限，不保存 Windows 密码；关机、用户未登录或 MySQL 未运行时不能保证按点执行，登录后补跑，失败按 15 分钟间隔最多重试两次。不唤醒或启动用户未运行的 MySQL 服务，不启动 HTTP／SDK／CLI，不弹出控制台。调度选项依据 [Microsoft 任务设置](https://learn.microsoft.com/en-us/powershell/module/scheduledtasks/new-scheduledtasksettingsset)与[运行身份](https://learn.microsoft.com/en-us/powershell/module/scheduledtasks/new-scheduledtaskprincipal)。

在项目根目录先用 Maven 打包，再运行 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/install-local-directory-sync.ps1`：它准备环境、检查本机时区和既有任务归属，完成一次真实开发目录提交后注册／更新本项目任务；不是自动化测试。`-PrepareOnly` 只准备文件，不同步或注册。测试库验证使用 `.local/stock-directory/venv/Scripts/python.exe -B .local/stock-directory/scripts/run-local-directory-sync.py --verify`，整批重复导入后回滚，结果保存在 `verification/`，不会生成开发库成功指针。写入与清理沿测试规则执行。

日常查看任务用 `Get-ScheduledTask -TaskName StockSystem-DirectorySync` 与 `Get-ScheduledTaskInfo -TaskName StockSystem-DirectorySync`；查看 `.local/stock-directory/state/last-run.json` 的 `status`／`phase`，并对照 `last-success.json`。`APPLIED` 才代表数据库已提交；单次调度退出成功也可能表示当天已完成而跳过。前置配置失败另写 `local-preflight-failure.json`，不覆盖既有成功回执。手工同步执行同一本地入口，追加 `--force` 可以忽略当天已成功判断，但不能绕开锁或校验。

暂停使用 `Disable-ScheduledTask -TaskName StockSystem-DirectorySync`；恢复用 `Enable-ScheduledTask`。移除任务可用 `Unregister-ScheduledTask -TaskName StockSystem-DirectorySync -Confirm:$false`，不会撤销已同步目录或删除研究历史。自动化测试和正常开发目录提交的区别见 [测试数据规则](testing.md#4-测试数据清理)。该 Windows 任务只更新身份，保留目录缺席股票及已有可信状态。每日交易状态查询由正在运行的 longbridge 后端单独负责，时间和补跑条件见 [启动频率](#3-启动频率)；不能把目录任务成功当作长桥状态已核实。

### 8.2 IDEA 提交检查提示“无法解析表 stock”

IDEA 会用本地缓存的数据库元数据检查 Java／JS 字符串里的 SQL；它不通过 Java 编译器验证数据库表。如果缓存仍是旧版 `user/stock_record`，即使真实数据库已有新版 `stock`，提交前分析仍可能把表名／列名标成错误。先区分 IDE 解析错误、Java 编译错误和真实 MySQL 执行错误，不能只凭错误总数判断业务损坏。

在本地 Database 面板打开 MySQL 数据源属性，进入 Schemas，选中 `stock_system` 和 `stock_system_test`，应用后刷新结构；必要时右键数据源选择 Diagnostics → Force Refresh。成功的判断是看到新版 `stock`、`workspace_stock`、`research_cycle`、`stock_opinion` 等表，而非仅看到旧两张表。数据库结构选择和刷新机制见 [JetBrains 元数据说明](https://www.jetbrains.com/help/idea/introspection.html)。

刷新后若仍无法解析，在设置搜索 `SQL Resolution Scopes`：将相关测试源码目录关联本地 `stock_system_test`，目录导入主源码关联同结构的本地 schema，再运行提交前代码分析。配置入口见 [JetBrains SQL 解析作用域](https://www.jetbrains.com/help/idea/settings-languages-sql-resolution-scopes.html)。这只改变 IDE 的补全／静态检查上下文，不修改 Spring profile、真实数据库连接或测试数据保护；不要为消除提示把 SQL 改成硬编码开发库，也不要直接关闭 SQL 检查。

2026-10-08 本次排查先发现两库各 13 张表且都有 `stock`，IDE 缓存却仅有旧 `user/stock_record`。用户强制刷新后，已核对 IDE 元数据包含两库的新版表，旧缓存问题已经解决；提交检查仍报表无法解析，随后确认项目没有 SQL 解析映射，已在本机忽略的 `.idea/sqldialects.xml` 设置项目静态 SQL 解析参考 `stock_system_test`，并用实际 IDEA 2025.2 的解析器验证配置格式。数据源 UUID 属于本机，不提交或复制给其他开发者。用户重新打开项目后提供的截图显示原 43 条错误全部消失，剩 3 条警告。

剩余警告已在源码中处理：文档目录链接指向实际 `index.md`；占位符正则删除多余的右花括号转义；目录校验返回解析后的快照时间，导入与同步复用该结果，非法时间仍在写入前抛出异常。不要为了消除“结果被忽略”而删掉有效校验。本轮 12 项目录 MySQL 集成测试通过，测试库股票与目录测试账号均核对为 0；脚本语法、lint、7 类占位符匹配等价检查和文档链接检查通过。三条警告是否已在 IDEA 清零仍须重跑代码分析确认，源码检查不代替 IDE 验收。

## 9. Longbridge 真实报价

### 9.1 这套方案怎样运行

本地数据流是：

```text
Vue → Spring Boot（权限、目录与参数校验）→ 行情网关
                                          ├─ Redis 命中 → 返回缓存行情
                                          └─ 未命中／关闭／故障
                                               → 进程内短缓存
                                               → 必要时由官方 Java SDK 受控回源
```

搜索先查 MySQL 标准目录，再批量补报价；添加仍由服务端取可信报价后写不可变开始快照；K 线和分时只在用户展开股票时按需读取。Spring Boot 不会为每只股票执行一遍命令，也不会为每个页面请求重新登录。开发配置现在选择官方 Java SDK OAuth：第一次回源时创建一个 `QuoteContext`，后续复用，应用关闭时释放 SDK 资源；不会启动 CLI。生产仍选择 `longbridge serve`，其操作只见部署说明。本地新账号授权、四类 SDK 探测及 2026-10-07 登录后真实网页已通过，实际到期后的自动刷新仍待验收，证据见 SPEC-002 的 CODE-206I。搜索最多 30 个目录候选，但发送的是一个 `symbols` 批量请求。缓存分层及失败边界见 [SPEC-002 设计](../specs/002-stock-research/design.md#46-redis-共享行情缓存与访问保护)。

当前[官方定价说明](https://open.longbridge.com/zh-CN/pricing)写明 OpenAPI 不收激活或接口使用费，因此一次报价不是“消耗一张 OAuth 授权码”或按次扣费；需要控制的是调用速率、长连接数量和各市场行情权限。授权码只用于建立/刷新授权，Token 会复用。[实时报价接口](https://open.longbridge.com/zh-CN/docs/quote/pull/quote)当前没有公开的每日或每月不同标的总量硬上限；单次最多接收 500 个代码，所以理论上查询 5000 只需要拆成至少 10 个请求并遵守每秒 10 次、并发 5 次，不能一次请求或同时订阅 5000 只。项目搜索只取最多 30 个候选并合并成一次请求。

[K 线 CLI 官方说明](https://open.longbridge.com/zh-CN/docs/cli/market-data/kline)支持 `1m/5m/15m/30m/1h/day/week/month/year`，并与[历史 K 线接口](https://open.longbridge.com/zh-CN/docs/quote/pull/history-candlestick)记录了另一套额度：按自然月统计查询过的不同股票代码，基础开户档为 100 只，随账户等级最高到 3000 只；同一股票当月切换周期、回看不同日期或重复查询都只计一个不同标的，但每次未命中项目缓存的读取仍是一笔上游请求。历史 K 线还有每 30 秒最多 60 次的接口频率限制。当前页面已开放全部九种周期，并只在用户展开已经加入空间的单只股票时加载；当天分时同样按需读取，历史分时复用 1 分钟历史 K 线，所以也计入历史 K 线不同标的额度。搜索候选和整张共享列表不会预取这些序列。费用、额度与行情权限可能变化，上线前仍按 [部署检查](deployment.md) 重新核对，不能据此承诺所有市场永久免费实时。

官方文档的“我的行情权限”链接进入 [Longbridge Account Center](https://open.longbridge.com/account)，可核对账号权限和档位；当前公开文档和 CLI 帮助没有提供“本月已用／剩余不同 K 线标的数”的查询命令，不能承诺该页一定显示实时已用计数。项目也不自称掌握供应商全局余量，因为同一账号可能被其他客户端调用；当前只能避免搜索预取、复用同股缓存，并在上游限制错误时明确显示。以后若做本地用量看板，只能标为项目自身请求估算，不能冒充 Longbridge 官方剩余额度。

### 9.2 新账号与官方 OAuth 准备

本地账号与云端账号分别授权。一个长桥账号的行情长连接限制不会因换 OAuth client_id 或增加 Redis 消失；新的账号也须具有自己的 OpenAPI 行情权限。账号注册、验证码、身份资料和授权同意由用户在[官方开发者中心](https://open.longbridge.com/account)完成；若页面要求先开户，按官方条件办理。OpenAPI 权限独立于 App 行情权限，见[官方说明](https://open.longbridge.com/zh-CN/docs/quote/overview)。

`client_id` 是本地程序的公开身份，长桥账号是授权人，Token 是 SDK 保存的访问凭证。先在独立浏览器配置中注册并确认新账号，再在项目根目录的 PowerShell 运行：

```powershell
.\scripts\authorize-longbridge.cmd
```

该命令做两件事：没有本地 client_id 时，按[官方快速开始](https://open.longbridge.com/zh-CN/docs/getting-started)动态注册 `stock-system-local-java-sdk` public client，新注册回调为 `http://localhost:18080/callback`；然后用 `OAuthBuilder` 执行浏览器授权，最长等待 10 分钟。本机 Windows 保留端口范围包含 SDK 默认的 60355，因此新注册和 SDK 均使用 18080；已有 client_id 始终复用，旧配置缺少回调端口时保留原来的 60355，不自动重注册或覆盖配置。脚本在终端打印授权 URL，由用户在已登录新账号的 Chrome 普通窗口打开，不自动唤起默认浏览器。授权页须核对为新账号，浏览器回调时命令窗口保持打开。终端出现 `LONGBRIDGE_OAUTH_OK` 才证明 SDK 授权完成；这一步不连接数据库、不建立行情连接，不能当作新账号行情权限验收。

脚本把公开 client_id 和回调端口写到 Git 忽略的 `.env.longbridge-local.properties`（`local.longbridge.client-id`、`local.longbridge.callback-port`）。授权时临时设置子进程的 `LONGBRIDGE_CLIENT_ID`，不设置 Windows 用户／系统永久环境变量。`dev` 导入该文件，运行目录应为项目根目录；也可通过非敏感的 `LONGBRIDGE_CLIENT_ID` 覆盖。Token 的保存和刷新交给官方 SDK，Windows 位于 `%USERPROFILE%\.longbridge\openapi\tokens\<client_id>`。项目不读取或手写该缓存，也不从旧 CLI 拷贝 Token；旧账号授权文件不自动删除，不影响生产账号。

授权成功后重启本地后端，确保旧 Java 进程拥有的 CLI 连接先退出，再使用新 SDK 连接。2026-10-07 网页搜索、K 线和历史分时已经真实验收；A 股休市当天验证明确的非交易日响应，没有将无曲线算作接口故障。自动刷新仍需在 SDK 管理的授权实际到期后验证，不强制编辑真实 Token 文件或用文件存在冒充刷新成功。已有本地开发服务由用户正常关闭；自动化不得批量结束 Java 或 longbridge。云端的安装、授权、Token 挂载和镜像维持原方案，见[部署说明](deployment.md)。

### 9.3 可配置项与降级

| 环境变量 | 默认值 | 作用 |
| --- | --- | --- |
| `MARKET_MODE` | `longbridge` | `longbridge` 取真实报价；`directory` 只搜目录；`mock` 只用于明确模拟 |
| `LONGBRIDGE_CLIENT_ID` | 本地忽略文件中的 client_id | 本地 OAuth public client 的公开 ID；不是 Token |
| `LONGBRIDGE_REQUEST_TIMEOUT` | `5s` | SDK 请求及本地排队等待上限（分别计时） |
| `LONGBRIDGE_CACHE_TTL` | `5s` | 报价按股票、当天分时按股票与市场日期复用的进程内短缓存；不是历史数据缓存 |
| `LONGBRIDGE_CANDLESTICK_CACHE_TTL` | `1m` | 相同股票、周期、数量和最新／历史日期边界的 K 线进程内短缓存；重启即失效 |
| `MARKET_REDIS_ENABLED` | `true` | 是否启用共享行情缓存；测试 profile 固定关闭，不能用它绕过业务权限校验 |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6380` | Spring Boot 连接的 Redis 地址；Windows 若依使用 6379 时，stock-system 使用 WSL 的 6380 |

Redis 的 TTL、锁、冷却和抖动参数及含义集中见 [缓存设计](../specs/002-stock-research/design.md#46-redis-共享行情缓存与访问保护)，本地默认 Redis 前缀含 dev、sdk 与 client_id，避免旧 CLI 客户端缓存掩盖新账号权限问题；它不是账号授权证明，且不能隔离被错误复用的同一 client_id。完整环境变量映射见 [开发配置](../../src/main/resources/application-dev.properties)。修改后要重启 Spring Boot 才会重新加载；尤其不能把当前历史接口的长 TTL 当成已经验证了“所有数据均已闭市”。

本地 client_id 缺失、当前 OS 账号未授权、Token 目录无权限、网络超时或上游报错时，搜索仍返回 MySQL 可信目录，但价格和行情时间为空；添加研究按 `MARKET_QUOTE_UNAVAILABLE` 失败且不写业务记录。系统不会静默改用 Mock，也不会把缓存过期数据标成实时行情。SDK 超时或连接／授权故障后的当前请求失败，关闭旧 SDK 会话后，下一次请求才重新创建连接；已识别的业务配额错误保留现有连接。SDK 初版将上游发送串行化，并保持至少 100ms 间隔；这不是全局限流，也未测定多用户吞吐。

生产 `longbridge serve` 本身包含交易方法，但项目 CLI 客户端只允许 `quote.quote`、`quote.candlesticks`、`quote.history_candlesticks_by_date`、`quote.intraday` 和 `quote.trading_days` 五个固定只读方法，没有向 Controller 或前端开放任意方法。K 线 `301607` 会作为 HTTP 429 和 `MARKET_KLINE_LIMIT_EXCEEDED` 返回；历史分时遇到该码返回 `MARKET_INTRADAY_HISTORY_LIMIT_EXCEEDED`，遇到分钟历史起始日期限制则返回 HTTP 422、稳定错误码及最早可查日期。近一年 A 股／港股日期通过官方交易日日历判断，休市日返回 400；当天点还按市场时区过滤为请求日期。这些错误可在页面错误区、开发者工具 Network 响应或后端 API 调用结果中看到。部署到服务器还需要固定服务账号、持久化授权、实例数和回滚设计，不能把本机登录成功等同于部署完成；完整清单见 [部署说明](deployment.md#3-longbridge-sidecar-部署前置条件)。

### 9.4 启动、页面请求与连接占用

创建 Spring Bean 不等于建立长桥行情连接。SDK 构造函数只保存会话工厂，首次实际回源才执行 `sessionFactory.open()` 并创建 `QuoteContext`；CLI 同样在首次回源时才启动 `longbridge serve`。因此，新启动的本地后端若从未触发行情回源，不会仅因启动而占用长桥行情连接。已经回源并建连的进程则会保留连接，停止点击或关闭页面不会让后端自动断开，需正常关闭该后端才能释放它拥有的连接。

进入单股行情页或研究详情会自动读取报价，可见当天分时还有定时刷新；“我没手动点刷新”不代表没有请求。暂不开发真实行情时，可显式选择 `MARKET_MODE=mock` 或 `directory` 并重启本地后端，让代码保证不创建真实上游，不能只靠记住不点某页面。两端需要同时使用真实行情时，才需要分别有权限的账号或另行设计统一行情网关。

长桥行情连接与项目 MySQL 是两类独立资源。Spring Boot 启动会连接配置的数据库并校验／执行 Flyway，即使没有网页操作。仓库默认 dev 指向本机 `localhost` 的 `stock_system`；prod 指向容器数据库。数据库是否隔离由实际服务器地址与生效配置决定，不能只看两边相同的库名，也不能把“不查行情”等同于“不连数据库”。

### 9.5 本次迁移的代码定位

| 职责 | 入口与影响 |
| --- | --- |
| 环境与依赖 | `pom.xml` 的 SDK 4.5.0 改为运行依赖；`application-dev.properties` 选择 sdk 并导入本地 client_id；`application-prod.properties` 明确选择 cli；test 仍为 mock |
| 授权 | `scripts/authorize-longbridge.cmd`／`.ps1` → `LongbridgeOAuthSetupTest`；官方 SDK 管理 Token；旧 probe 脚本、粘贴 code、手工兑换与临时写缓存的五个测试辅助类已撤掉 |
| 传输 | `LongbridgeUpstreamGateway` 是四类只读网关的统一上游接口；`LongbridgeSdkClient` 调用 SDK；`LongbridgeSdkSession` 管理 OAuth／Config／QuoteContext；`LongbridgeJsonRpcClient` 保留供 prod 使用 |
| 缓存 | `CachingLongbridgeMarketGateway` 依赖统一上游接口，不再固定依赖 CLI；SDK 保留四类有界短缓存、市场日期与供应商时间 |
| 业务与错误 | 三个 `LongbridgeMarket*Provider` 继续转换项目 DTO；共用异常更名为 `LongbridgeUpstreamException`，保留配额码和分钟历史最早日期；搜索、研究 Service 和前端契约不因传输切换改写 |
| 验证与部署 | `LongbridgeSdkClientTest`、`LongbridgeTransportWiringTest` 和原缓存／provider／CLI 单元测试；授权、SDK 探测和登录后网页已验证，真实 Token 到期刷新仍待验收，具体证据与缺口只见 CODE-206I／status；`deploy/backend` 的 CLI Dockerfile 和云端 Compose 保留生产路径 |

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
