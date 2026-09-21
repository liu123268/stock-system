# 当前项目状态

状态核对：2026-09-21。SPEC-002 CODE-208 拼音首字母搜索、V7 迁移和现有可信目录回填已经完成；证据见 SPEC-002 tasks。

## 当前阶段与下一步

认证与普通空间基础边界完成至 [SPEC-001 CODE-006](../specs/001-workspace/tasks.md)。[SPEC-002](../specs/002-stock-research/tasks.md) 的研究生命周期审查缺口、Longbridge 状态准入和 CODE-208 拼音搜索均已关闭。

**下一步返回 [SPEC-001 CODE-007](../specs/001-workspace/tasks.md)，完成超级管理员用户、空间治理和操作记录。** 随后依次处理空间改名、个人资料和邀请安全；近期动态、时间线规模与详情行情／价格变化由 CODE-209～211 承接，跨阶段关系见 [路线](roadmap.md)。

数据库目录现在支持代码、简体、繁体、英文和连续拼音首字母前缀搜索；拼音只召回可信身份，候选仍最多 30 条并在确定后一次批量补报价。业务正文见 [SPEC-002 需求](../specs/002-stock-research/requirements.md)，实现与证据见对应 design／tasks。

## 未完成边界

- CODE-206E 与 CODE-206F 已完成，不再当作待做前置；各市场目录覆盖、每日同步和部署可靠性仍按 CODE-206G～H 单独验收。
- 生产部署、北交所完整核对和每日目录同步仍未完成。Redis 的跨连接测试不等于同一 Longbridge 账号可以启动多个真实行情 sidecar。
- V1 全量完成还包括账号／治理、空间概览动态和客观价格变化。Agent 在 Java 核心 V1 独立验收之后；WebSocket/SSE 和额外中间件不自动进入当前范围。

## 环境快照

开发默认 longbridge 并启用 Redis；test 默认 mock 且关闭 Redis。开发／测试端口、Windows 与 WSL Redis 隔离、环境变量和启停方式只见 [开发环境](../engineering/development.md)，sidecar 服务账号与生产前置见 [部署说明](../engineering/deployment.md)。本轮临时 test 服务已关闭并释放 18082／18083，不据此推断用户当前是否另有开发服务运行。

2026-09-21 已通过实际 JDBC 核对并把开发库 `stock_system` 从 V6 升至 V7，对现有 8318 条可信目录完成拼音回填；股票总数、四市场分布和既有 ID 保持。测试库 `stock_system_test` 也为 V7，整批 8318 条验证后回滚为 0。以后执行数据库操作仍须重新核对现场。

## 最近验证

- 历史业务证据：2026-09-18 [PLAN-207 验收](../specs/002-stock-research/tasks.md#plan-207-研究生命周期最终证据2026-09-18)记录后端 115 项中 108 项通过、7 项外部探测跳过，前端 32 项及构建／静态检查通过，双会话浏览器与清理完成。这些测试没有覆盖本轮新列出的全部反例。
- Redis、凭据扫描、真实页面命中／未命中及故障降级，见 [CODE-206E 最终证据](../specs/002-stock-research/tasks.md#code-206e-最终验收证据2026-09-18)。空间成员及认证历史证据见 SPEC-001 tasks；重要历史根因保留在 [Bug 复盘](../engineering/bugs/index.md)，不在本快照复制逐日记录。
- 2026-09-21 CODE-208 完整验证：3 项 Python、7 项目录 MySQL 集成测试及 Longbridge 单次批量报价测试通过；后端完整回归 125 项中 118 项通过、7 项外部探测跳过。前端 38 项单元测试、类型检查、Oxlint、ESLint 和生产构建通过；隔离 Chrome 在桌面／移动视口覆盖原搜索与 `tbdg/TB`，数据清理为 0，临时进程已关闭、端口已释放。未调用真实 Longbridge；详细数据迁移和回填证据见 SPEC-002 tasks。
