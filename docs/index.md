# 项目文档导航

AI 从 [AGENTS.md](../AGENTS.md) 进入，由 [场景路由](project/project-rules.md) 选择资料；本文供人查找，不是开发必读清单。

| 目录 | 职责 | 入口 |
| --- | --- | --- |
| project | 当前进度、执行方式、教学方法、阶段顺序与教学范围 | [状态](project/status.md)、[场景路由](project/project-rules.md)、[工作流](project/workflow.md)、[教学方法](project/teaching.md)、[文档维护](project/documentation.md)、[路线与教学范围](project/roadmap.md)、[原始技术目标](project/technology-reference.md)、[Java 与 Agent 求职能力对标](project/java-agent-market-benchmark.md) |
| product | V1／V2 总范围、体验目的、未来候选 | [V1 需求](product/requirements-v1.md)、[V2 需求](product/requirements-v2.md)、[产品原则](product/product-principles.md)、[候选清单](product/backlog.md) |
| specs | 阶段内做什么、怎么实现、完成证据 | SPEC-001 [需求](specs/001-workspace/requirements.md)／[设计](specs/001-workspace/design.md)／[任务](specs/001-workspace/tasks.md)；SPEC-002 [需求](specs/002-stock-research/requirements.md)／[设计](specs/002-stock-research/design.md)／[任务](specs/002-stock-research/tasks.md)；SPEC-003 UI 迁移 [需求](specs/003-ui-migration/requirements.md)／[设计](specs/003-ui-migration/design.md)／[任务](specs/003-ui-migration/tasks.md)；SPEC-004 研究 Agent [需求与确认记录](specs/004-research-agent/requirements.md)／[设计草案](specs/004-research-agent/design.md)／[任务计划](specs/004-research-agent/tasks.md) |
| architecture | 跨阶段结构、数据模型、技术与视觉 | [总览](architecture/overview.md)、[技术栈](architecture/technology-stack.md)、[数据模型](architecture/data-model.md)、[认证](architecture/authentication.md)、[视觉规范与迭代](architecture/ui-design-system.md)、[模块化单体决策](architecture/decisions/ADR-001-modular-monolith.md)、[Longbridge sidecar 决策](architecture/decisions/ADR-002-longbridge-cli-sidecar.md) |
| engineering | 环境、代码职责、教学、测试与问题根因 | [启动](engineering/development.md)、[接口文档](engineering/api.md)、[部署](engineering/deployment.md)、[目录与分层](engineering/project-structure.md)、[Spring 教学](engineering/spring.md)、[MyBatis 教学](engineering/mybatis.md)、[前端教学](engineering/frontend.md)、[Redis 教学](engineering/redis.md)、[Agent 复习与业务学习](engineering/agent-experiment.md)、[测试](engineering/testing.md)、[Bug 复盘目录](engineering/bugs/index.md) |
| career | 有证据的亮点和领域面试回答 | [亮点](career/highlights.md)、[工程](career/interview/01-project-engineering.md)、[安全](career/interview/02-authentication-security.md)、[数据库](career/interview/03-database-persistence.md)、[前端](career/interview/04-frontend-engineering.md)、[Redis 与缓存](career/interview/05-redis-cache.md) |

[功能地图](system-capabilities.html) 是历史展示快照，不代表当前完成度；[PRODUCT](../PRODUCT.md) 是设计工具摘要；它们不保存新规则。新增长期要求的落点与替换方式见场景路由，不需要用户手工选择。

requirements 是需求，design 是技术设计，tasks 是实施任务，status 是当前快照，roadmap 是阶段路线，ADR 是架构决策及取舍，bugs 是重要问题复盘，interview 是领域问答。
