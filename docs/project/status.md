# 当前项目状态

状态核对：2026-09-19。旧页面误操作新周期、研究添加覆盖目录身份、权限校验晚于行情调用及 Longbridge 状态未使用的问题已经修复并完成针对性验证；证据见 SPEC-002 tasks。

## 当前阶段与下一步

认证与普通空间基础边界完成至 [SPEC-001 CODE-006](../specs/001-workspace/tasks.md)。[SPEC-002](../specs/002-stock-research/tasks.md) 本轮审查的 CODE-207E～H 与 206F 已完成，PLAN-207 已重新关闭。

**下一步继续 CODE-208 拼音搜索。** 随后返回 SPEC-001 的治理、改名、个人资料和邀请安全收尾。近期动态、时间线规模与详情行情／价格变化由 CODE-209～211 承接，跨阶段关系见 [路线](roadmap.md)。

本轮用户确认的规则已经实现：开启／重新开启研究直接使用 Longbridge `trade_status`；Normal 和 Halted 可开，Delisted、其他／缺失状态和核验失败拒绝，已有历史不删除。全局正文见 [V1 需求](../product/requirements-v1.md#33-共享自选股与行情)。

## 未完成边界

- CODE-206E 已于 2026-09-18 完成原定 Redis 验收，不再把它当作待做前置。真实添加正常流程也已有页面证据；上市状态、各市场覆盖与失败／并发边界仍按 CODE-206F～H 单独验收。
- 生产部署、北交所完整核对和每日目录同步仍未完成。Redis 的跨连接测试不等于同一 Longbridge 账号可以启动多个真实行情 sidecar。
- V1 全量完成还包括账号／治理、空间概览动态和客观价格变化。Agent 在 Java 核心 V1 独立验收之后；WebSocket/SSE 和额外中间件不自动进入当前范围。

## 环境快照

开发默认 longbridge 并启用 Redis；test 默认 mock 且关闭 Redis。开发／测试端口、Windows 与 WSL Redis 隔离、环境变量和启停方式只见 [开发环境](../engineering/development.md)，sidecar 服务账号与生产前置见 [部署说明](../engineering/deployment.md)。本轮未探测或改变运行环境，不据历史记录推断服务现在正在运行。

最近一次开发库目录核验是 2026-09-08 的 V4、8318 条标准股票，证据见 CODE-206A；2026-09-18 的 V5 迁移证据针对 stock_system_test，不能据此断言开发库已升级。执行数据库操作前重新核对现场。

## 最近验证

- 历史业务证据：2026-09-18 [PLAN-207 验收](../specs/002-stock-research/tasks.md#plan-207-研究生命周期最终证据2026-09-18)记录后端 115 项中 108 项通过、7 项外部探测跳过，前端 32 项及构建／静态检查通过，双会话浏览器与清理完成。这些测试没有覆盖本轮新列出的全部反例。
- Redis、凭据扫描、真实页面命中／未命中及故障降级，见 [CODE-206E 最终证据](../specs/002-stock-research/tasks.md#code-206e-最终验收证据2026-09-18)。空间成员及认证历史证据见 SPEC-001 tasks；重要历史根因保留在 [Bug 复盘](../engineering/bugs/index.md)，不在本快照复制逐日记录。
- 2026-09-20 完整验证：后端 122 项中 115 项通过、7 项外部探测按开关跳过，0 失败、0 错误；其中 8 项针对性单元测试和 15 项 `StockResearchIntegrationTest` 覆盖状态准入、目录所有权与旧周期冲突。前端 38 项单元测试、类型检查、Oxlint、ESLint 和生产构建通过，其中 6 项新反例覆盖 CODE-207G。后端实际连接 `stock_system_test`，集成测试事务回滚；没有调用真实 Longbridge、启动浏览器或临时服务，也没有写入开发库。
