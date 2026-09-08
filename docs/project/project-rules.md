# 场景路由与规则维护

所有路径相对本文件。本文是项目要求的查找入口；全局协作要求只在 [AGENTS.md](../../AGENTS.md) 维护。

## 按任务读取，也按此处写入

AI 自动选择匹配行，多场景取并集；先定位相关章节，再读正文和直接代码。不要沿全部链接展开。纯问答只取相关片段，修文案不预读技术栈，代码改动不默认读面试和历史 Bug。

| 触发场景／新增要求类型 | 唯一维护位置及读取范围 |
| --- | --- |
| 所有任务：教学方式、保护改动、规则记忆 | [AGENTS.md](../../AGENTS.md) |
| 接续开发、进度、阻塞、下一步 | [status.md](status.md)；再定位其中当前规格的未完成任务 |
| 需求讨论、功能实施、开发节奏 | [workflow.md](workflow.md) 的需求与范围；对应 `specs` 中本次用户故事的 requirements、design、tasks |
| 技术选型、依赖、中间件、性能、Agent、学习能力缺漏、简历深度 | [workflow.md](workflow.md) 的技术选型、[technology-stack.md](../architecture/technology-stack.md) 对应项、[technology-reference.md](technology-reference.md) 对应原始条目；整体路线评估才完整对照原始清单 |
| V1 范围、全局产品规则 | [requirements-v1.md](../product/requirements-v1.md)；阶段细则在 `specs/<阶段>/requirements.md` |
| 阶段顺序、候选能力 | [roadmap.md](roadmap.md)／[backlog.md](../product/backlog.md) |
| 后端分层、命名、前端目录 | [project-structure.md](../engineering/project-structure.md) |
| 表字段、索引、迁移、数据库修改 | [data-model.md](../architecture/data-model.md)、相关 Flyway 和阶段 design；执行前读 [testing.md](../engineering/testing.md) |
| API、事务、状态设计 | 对应 `specs/<阶段>/design.md`；全局模块关系见 [overview.md](../architecture/overview.md)，重大取舍在其 `decisions/` |
| 登录、JWT、认证问题 | [authentication.md](../architecture/authentication.md)、相关代码和测试 |
| Vue、Store、HTTP、页面行为 | [frontend.md](../engineering/frontend.md) 的实施要求及对应模块章节 |
| UI、响应式、动效、可访问性 | [ui-design-system.md](../architecture/ui-design-system.md)；产品目的变化再读 [product-principles.md](../product/product-principles.md) |
| 启动、端口、配置 | [development.md](../engineering/development.md)；启动进程前同时读下一行 |
| 任何测试、临时进程、测试数据 | [testing.md](../engineering/testing.md)，完整读取执行与清理要求，再选对应测试层次 |
| 文档整理、交接写法与维护要求、教学沉淀、Bug 复盘、面试或里程碑收尾 | [documentation.md](documentation.md)，再读目标工程或 `career` 文件；只有根因对应时才读 `engineering/bugs` |

## 新规则与冲突处理

1. 用户提出长期要求后，AI 判断它是协作规则、场景规则、业务需求还是技术决定，按上表写入原有对应位置；确无合适位置时才新增文件并补路由。临时任务指令不自动升级为永久规则。只维护用户提出或已确认的要求，不把 AI 自己的建议、猜测、篇幅指标或整理偏好追加为长期规则；对规则维护本身的要求也遵守本条。
2. 写清触发场景、应做动作、完成证据和必要例外；普通用户无需记文档名或主动选择场景。
3. 同义要求合并到原条款。新要求明确替代旧要求时直接修改或删除旧条款，搜索本项目的相关关键词并修正引用，不追加一条相反规则让后续 AI 猜。局部例外只改变对应场景。
4. 在系统和开发者约束内，用户最新明确要求优先；已确认规格描述目标，代码／迁移／测试描述实际事实。二者不一致时记录具体差距，不能因代码尚未实现就删需求，也不能用需求假称已经实现。含糊且影响权限、数据关系、成本或不可逆操作的冲突才向用户问一个具体问题；已有授权不重问。
5. 旧记忆、历史笔记、README、PRODUCT、功能地图和面试材料只辅助定位，不覆盖当前权威规则。旧路径失效时沿本入口寻找新位置，不重新创建旧目录。
6. 完成本轮需求、设计或实现后，主动更新对应唯一落点；任务验证通过才勾选 tasks。仅当阶段、阻塞、下一步或最新验证改变时更新 status，更新前遵守 [交接要求](documentation.md#交接内容与交接要求)。交付简述实际更新和未完成事项，不逐项列举未触发的文档。

## 控制上下文增长

- 同一规则只保存一份可执行正文；其他文件用链接或规则编号，不换措辞再写一套。需求描述结果、设计解释机制、测试说明证明方法，这三种职责不能混写成三份规则。
- 当前状态保持短快照，不堆历史；教学与面试可以保留必要背景，但开发任务不默认读取它们。数值和验证证据链接原始落点，避免多份过期数字。
- 只有用户明确要求文档审计、目录结构大改或大阶段全局一致性收尾时才全量扫描项目文档。日常只搜索本次规则相关词和受影响引用。
- 新任务不要求用户粘贴开场模板。识别路由后直接处理当前请求，不复述项目历史或重复确认已定事项。
