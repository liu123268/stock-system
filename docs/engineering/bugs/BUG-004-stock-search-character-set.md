# BUG-004：中文目录搜索遇到 ASCII 代码列

2026-09-08，真实目录首次搜索测试发现：数字代码查询可运行，中文“腾讯”查询报 MySQL 3854，提示无法把 utf8mb4 字符转换为 ascii，API 返回 500。

`stock.code` 刻意使用 ASCII 保存标准代码。原查询同时在 code、name 和别名上执行 LOCATE，并用 code 与用户输入比较以优先显示精确代码。中文输入进入代码比较时，MySQL 按表达式字符集规则尝试转为 ASCII；OR 的其他分支能匹配中文，也不能保证这一分支不会计算。

修复在查询表达式中使用 `CONVERT(code USING utf8mb4)`，让 LOCATE 与排序比较兼容中文，存储列仍保留原来的 ASCII 身份约束。所有输入继续参数绑定，百分号不作为通配符。当前子串搜索有固定结果上限，不能据此宣称命中代码索引或已做性能优化。

`StockDirectoryIntegrationTest` 使用真实 MySQL 覆盖简体、繁体、英文、带后缀代码及特殊输入，并通过 MockMvc 证明中文请求成功。首次异常是在测试库发现，开发库导入前已修复。另一个测试读到旧名称来自 JDBC 更新绕过同一测试内的 MyBatis 一级缓存：测试在原生 JDBC 写入后清除会话缓存，实际导入命令在独立进程运行。

面试说明：列级字符集与整库 UTF-8 配置不同；复合搜索必须核对每个表达式的转换规则，不能依赖 OR 的短路顺序。事务回滚保护测试数据，SQL 参数绑定保护输入边界，两者解决不同问题。

## UI-09 补充：账号与昵称联合搜索（2026-10-01）

治理后台真实 Chrome 输入中文“找不到的账号”时，没有得到空列表，而是返回 500。日志定位到 AdminQueryMapper.countUsers：`app_user.username` 使用 ascii／ascii_bin，原 LOCATE 同时比较 username 与 UTF-8 nickname，中文参数被尝试转换为 ASCII，触发同一 MySQL 3854 根因。账号只允许小写 ASCII 并不能证明搜索词也只有 ASCII；搜索框还负责中文昵称。

用户总数与列表两条查询都改为 `LOCATE(#{keyword}, CONVERT(u.username USING utf8mb4) COLLATE utf8mb4_bin)`，昵称比较继续沿原规则。显式 COLLATE 保留原 ascii_bin 的大小写敏感账号匹配：本机 MySQL 上单用 CONVERT 得到默认 utf8mb4_0900_ai_ci，会使原本不匹配的大写账号词也命中。存储约束、参数绑定和 LOCATE 字面子串语义保持；没有改表结构，也不将此修复声称为索引优化。

AdminGovernanceIntegrationTest 的真实 MySQL／MockMvc 回滚场景同时检查中文昵称、emoji、英文账号、字面百分号、无匹配中文以及大写账号不匹配，并核对 total 与 items 一致。浏览器重新验证中文搜索与空状态通过，证据见 [UI-09 tasks](../../specs/003-ui-migration/tasks.md#ui-09-正式迁移2026-10-01)。本补充属于同一字符集问题，不另建重复 Bug。
