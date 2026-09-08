# 本地开发与启动说明

更新时间：2026-09-04

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

配置职责如下：`application.properties` 保存两边共用的配置，各 profile 文件保存数据库地址和该环境的行情模式。默认 profile 是 dev，所以普通启动仍进入开发库；自动化测试必须显式进入 test。开发默认 `stock-system.market.mode=directory`，测试默认 `mock`；开发可通过环境变量 `MARKET_MODE=mock` 显式选择模拟模式。

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
