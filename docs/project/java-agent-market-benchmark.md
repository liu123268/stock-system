# Java 与 Agent 求职能力对标

调研日期：2026-10-08（中国标准时间）  
适用目标：Java 后端实习／应届，逐步增加 Agent 应用工程能力  
性质：近期公开招聘样本、难度分析与官方资料的证据记录。正式讨论采用的产品尺度见 [V2 规划基线](../product/requirements-v2.md#3-正式讨论采用的规划基线)，计划技术见 [技术规划](../architecture/technology-stack.md#32-v2-agent-技术规划基线)；调研不充当详细规格或实现证明。

## 1. 对标方法与样本

用户已要求：先查询近期就业市场对 Java 与 Agent 的能力、项目难度和知识栈要求，建立大致标准，再讨论 V2。执行规则只维护在 [工作流](workflow.md#技术选型)。原始学习目标见 [技术参考](technology-reference.md)，实际采用状态见 [技术栈](../architecture/technology-stack.md)。

以下为企业公开岗位描述的小样本，不代表岗位占比、薪资统计或录用保证。发布日期／更新日期与本次查询日期分别记录；旧实习批次只用于能力比较，不能当成当前一定可投的岗位。必备、优先和工作职责也不能合并为所有候选人都必须精通的清单。

| 企业公开岗位 | 页面日期／批次 | 要求或职责重点 | 对本项目的含义 |
| --- | --- | --- | --- |
| [TNG Digital 后端开发实习生](https://www.tngdigital.com.cn/tngd_career/) | 2026-09-24 | Java、Spring／Boot、MySQL、多表 SQL、Git／Linux、计算机基础；强调小模块开发、自测和解释代码，中间件等为加分 | Java 基础和完整交付仍是底座 |
| [海康睿影 Java 开发实习生](https://talent.hikvision.com/home/socity/position?postId=9BE3D2BA90CC2E6E4C8AD09E0A45FACB) | 2026-07-30 | Java／Boot／SQL、Redis 和 MQ 基础、业务模块与文档 | 普通实习无需以大规模架构作为起点 |
| [百度 Java 研发实习 J71214](https://talent.baidu.com/jobs/detail/INTERN/a07f7c26-3619-4965-ada2-24d8df0291d4) | 2026-07-21 | Java、数据结构算法、操作系统、分析解决问题；项目经验优先 | Agent 不能代替基础与编码能力 |
| [哈啰 AI Agent 开发实习 J20996](https://hellobike.zhiye.com/intern/jobs) | 2026-01-22，较早批次 | Java／Python、任务拆解、工具集成、状态管理、交互链路；Java／JVM／多线程和 Spring／Boot／MyBatis；Agent 框架了解或实践优先 | 能解释工具执行和状态管理，且保持后端能力 |
| [百度 Agent 应用开发实习 J105397](https://talent.baidu.com/jobs/detail/INTERN/83a5dbcc-48a6-4356-b6d6-b4da9f0a2528) | 2026-09-03 | Agent 与知识库开发、数据治理、样本与评分规则、工作流、上下文传递、异常和状态追踪 | 质量评估与工程边界需要成为项目内容 |
| [美图 Agent 中台实习](https://hr.meitu.com/en/jobCampus/5996c55a-6237-4732-bee0-89b8ce53bbad) | 页面注明 2026 暑期，未见发布日期 | 记忆、复杂工作流、外部知识检索、评测和全链路观测；RAG／Tool Calling／Harness 等；接受 Java 等多种语言 | 平台类实习要求更深，作为后续提高目标 |
| [腾讯 Agent 可观测研发工程师](https://careers.tencent.com/jobdesc.html?postId=2029862287371825152) | 2026-09-14 更新；五年以上经验 | 大规模系统、Logs／Traces／Metrics、SDK 和数据接入 | 社招平台要求只作上限参照，不当作实习准入 |

由这些样本作出的判断：没有统一的“Java 加 Agent”难度标准；业务应用、知识库、平台、模型算法岗位应分别看待。框架名称有差异，底层能力主要落在数据／工具接入、上下文与状态、可靠性和效果验证。Java 可用于应用工程，但这不意味着所有偏 Python 的岗位都接受 Java 项目作为全部证明。

## 2. 项目难度判断

Java 主线仍按 [实习准备路线](roadmap.md#实习准备与阶段关系) 和原始技术目标推进。岗位样本支持把独立模块开发、基础知识、排错和质量意识作为底座；不能把 Agent 接入当作已经掌握 Java 的证明。

下表是基于岗位要求与项目复杂性的工程判断，不是企业统一难度评级或录用保证：

| 层次 | 可检查结果 | 难度与定位 |
| --- | --- | --- |
| 入门调用 | 一次结构化模型调用 | 较低到中等；可快速验证外部调用，但质量与运行控制仍需另外验证 |
| 业务 Agent | 真实业务工具、研究上下文与回答质量闭环 | 中等偏高；适合在已有 Java 系统上逐步增加应用工程能力 |
| 知识检索／外部工具扩展 | 文档检索链路与标准工具集成 | 中等偏高到高；新增难点是文档质量、检索和权限，以及跨服务失败处理 |
| 通用平台／算法 | 复杂多 Agent、自主长任务或模型训练 | 高；岗位和能力范围与当前业务应用目标不同 |

对当前项目，接口和模型调用的代码量不是主要难点。较难的部分是：研究时间线分批读取时是否遗漏、成员观点与事实能否区分、历史上下文能否继续对应当前授权、工具失败后模型是否仍编造完整结论，以及模型／提示词变化后如何比较质量。正式验收结果维护在 V2 需求与后续 specs，不在调研中再维护一套任务清单。

早期评测规模可参考 [Anthropic 的评测资料](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)：从实际失败与代表性任务的小题集开始，例如 20～50 个问题。这是起步建议，不是招聘数量门槛；问题与评分设计应覆盖正确工具选择、引用语义、空数据、过期信息、权限变化、截断和外部失败。代码替身测试与真实模型质量评测证明不同事情，不能相互替代。

不能据此承诺固定工期或把某个完成率直接设为“实习合格线”。模型效果、实际数据与用户学习进度需要试运行后判断，性能和费用只能使用真实测量。

## 3. 技术复核结论与项目依据

选型与能力对应的唯一正文见 [技术规划](../architecture/technology-stack.md#32-v2-agent-技术规划基线)；完整知识目标仍保留在 [原始技术参考](technology-reference.md#用户原始文字)。不把岗位中出现过的所有框架名变成实施依赖。

本次代码核对表明已有可复用的读取链路：

- [StockResearchService](../../src/main/java/com/stock/system/stock/service/StockResearchService.java) 查询当前空间的研究列表；
- [StockResearchLifecycleService](../../src/main/java/com/stock/system/stock/service/StockResearchLifecycleService.java) 提供研究详情和分批时间线，工具适配不能把一页误当成整个周期；
- [StockMarketPreviewService](../../src/main/java/com/stock/system/stock/service/StockMarketPreviewService.java) 提供空间访问边界内的报价与行情读取；
- [WorkspaceAccessService](../../src/main/java/com/stock/system/workspace/service/WorkspaceAccessService.java) 提供用户／成员访问检查。

这些是已有业务能力，不表示已经向模型开放工具，也不表示已经通过 Agent 验收。

版本复核补充：此前只核对了 Boot 与 AI 的兼容关系；本次进一步核对维护周期，官方 [Boot 3.5.16 发布说明](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/) 明确这是 3.5.x 最后一个开源发行版。因而“Boot 3.5＋AI 1.1 兼容”不代表它是新的长期接入首选。目标线、迁移风险和退出路径已同步技术规划；[官方矩阵](https://github.com/spring-projects/spring-ai#getting-started)、[Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html) 与 [MyBatis-Plus starter 说明](https://baomidou.com/getting-started/) 是版本判断的依据，实际项目兼容性尚未验证。

## 4. 官方技术依据

- [Spring AI 工具调用](https://docs.spring.io/spring-ai/reference/api/tools.html)：模型请求工具调用，应用执行工具并回传结果；框架 API 随版本变化，以最终选定版本为准。
- [Spring AI 可观测性](https://docs.spring.io/spring-ai/reference/observability/)：模型／工具观测、Token 与敏感内容记录边界。
- [Spring AI RAG](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)：检索与上下文组合、元数据过滤及空检索结果处理。
- [MCP 官方介绍](https://modelcontextprotocol.io/docs/2026-07-28/getting-started/intro)：外部系统与工具连接标准。
- [Anthropic Agent 评测说明](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)，2026-01-09：从实际问题的小题集开始，检查执行记录，结合确定性检查与质量评估。
- [Spring AI Alibaba 概览](https://java2ai.com/docs/overview/)：图运行时、工作流与 Agent 能力；只作候选比较。

本文件保存调研依据与建议尺度，不能充当详细规格、实现证明或个人掌握证明。后续岗位目标、招聘要求或框架兼容性变化时按工作流重新核对。
