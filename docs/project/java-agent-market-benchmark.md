# Java 与 Agent 求职能力对标

调研日期：2026-10-08（中国标准时间）  
适用目标：Java 后端实习／应届，逐步增加 Agent 应用工程能力  
性质：公开岗位、求职者面经、项目作者复盘与官方资料的交叉证据记录；2026-10-08 按用户要求补查非头部企业与实际经历。产品尺度见 [V2 规划基线](../product/requirements-v2.md#3-正式讨论采用的规划基线)，技术、知识安排与可行性验证见 [技术规划](../architecture/technology-stack.md#34-可行性验证与知识安排)；调研不充当详细规格或实现证明。

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

### 1.1 非头部互联网、行业软件与中小企业补查

公司规模不是统一评级。帆软作为非头部互联网的软件／BI 企业样本；蜀通招聘平台标注 100～299 人，FOSHO 的 [企业自报规模](https://www.linkedin.com/company/fosho-ai) 为 51～200 人，麦可思招聘平台标注 100～499 人。人数不是本轮核验的现时员工统计，不由规模推断岗位必然简单。

| 岗位与日期 | 实际工作产出及门槛区别 | 可支持的判断与读取边界 |
| --- | --- | --- |
| [帆软 AI 团队后端实习生](https://www.shixiseng.com/intern/inn_wanlnzz7qdtt?pcm=pc_SearchList)，2026-09-16 刷新；[同岗位全文](https://www.deizao.cn/m/index/shixixq/jobid/261542) | 开发 Data Agent 模块、结合产品与业务、参与需求方案；Java 等至少一种后端语言、数据库、线程／缓存／异步 I/O 与大模型应用认知 | 后端基础与场景集成是具体工作；未要求实习生训练模型。职位正文可读 |
| [四川蜀通 Java AI 开发实习生](https://www.zhaopin.com/jobdetail/CC336010610J40865980510.htm)，无明确发布日期，2026-10-08 查询 | 导师指导做问答／搜索、RAG 文档处理，将已有 Java API 封装成 Tool，标注比较回答，交付 Demo 与踩坑文档；Java／Boot Web 项目为基础，Spring AI 示例经验为加分 | 入门门槛与独立资深交付区别明显。直开超时，证据来自搜索索引的职位正文，不称已确认当前可投 |
| [FOSHO Java／前端兼 AI 工程化实习](https://www.shixiseng.com/intern/inn_na86nyg982wy)，2026-09-20 刷新 | 把 Agent、RAG、模型 API 接入 SaaS，完成后端数据流与前端功能；包含全栈、Boot／Cloud 和数据库协作要求 | AI 组件接成可使用产品就是实际岗位工作；全栈要求不能自动变成所有 Java 后端实习的门槛。正文可读 |
| [麦可思 AI 应用开发实习](https://m.yupao.com/zhaogong/337876644.html)，企业认证记录 2026-09-30 | 用 Dify／扣子搭建业务工作流，改善 RAG 效果并做数据预处理；要求 Java 或 Python 核心技能与工程基础 | 现成平台配置也属于真实工作，自写代码不天然更高级。平台分类为“大模型算法”，正文偏应用，按实际职责判断 |

同一企业的 [蜀通 3～5 年社招岗](https://www.zhaopin.com/jobdetail/CC336010610J40837065810.htm) 增加独立完整项目、RAG 与微调等要求，不能与实习岗合并。JD 对协议的中文表述也可能有误，技术定义仍以官方资料为准。

### 1.2 求职者亲历与项目作者复盘

以下均为个人自述，未独立验证面试发生、录用结果或自测指标。牛客部分页面只显示月日，按原显示记录，不能仅凭网站年份补出精确发表年份。同一求职者的多个帖子不是独立统计样本；问题沿其简历展开，不能全部升级为本项目必做。

| 来源与层级 | 可核查的具体经历 | 对尺度的含义 |
| --- | --- | --- |
| [亚信安全 Agent 一面](https://api-cdn.nowcoder.com/feed/main/detail/00254f151d484eb89f8ab6781125d05a?sourceSSR=users)，9 月 2 日面试、9 月 9 日编辑；学生秋招自述 | 追问评测集、每题重复次数、端到端耗时、长任务恢复及并发下 Token 预算；正文已访问 | 同样写“Agent”，可追问到质量和运行控制；长任务恢复属于该岗位／经历范围，不作为当前主体前置 |
| [某小厂 Agent 实习](https://www.nowcoder.com/feed/main/detail/2f4e4cde4e524a16aae5f55a89c49273?sourceSSR=dynamic)，9 月 8 日面试；与上例同作者，[连续求职记录](https://api-cdn.nowcoder.com/users/826994329) | 追问客诉项目的一次效果问题及迭代、RAG／CodeGraph 选择、上下文预算、Memory 和开放任务评测 | 匿名公司规模无法核实，原帖抓取不稳定，使用搜索正文与作者主页。能支持“需解释真实取舍和改进”，不能支持所有小厂都如此 |
| [蚂蚁应用工程实习一面](https://www.nowcoder.com/discuss/894720138258173952?sourceSSR=post)，6 月 9 日面试、6 月 11 日发布 | 问两级缓存在哪用、什么量级才需要、个人电脑如何验证 MQ、为什么选 Spring AI、RAG 数据从哪导入、对话循环怎样实现 | 技术越多，被追问的必要性与验证越多；不为关键词增加无业务依据的中间件。正文已访问 |
| [上述亚信安全／匿名小厂帖作者的 Java 基础失败复盘](https://api-cdn.nowcoder.com/feed/main/detail/912ae02efb854c5c99b763c8cd200a99?sourceSSR=post)，9 月 12 日 | 作者自觉 Agent 项目与算法尚可，服务端面试的 AOP／索引追问未答好 | Java 基础仍需独立练习。技术问题与作者推测的落败原因分清；读取来自作者记录，不确认实际淘汰原因 |
| Chandler 学习项目：[2026-05-12 项目复盘](https://chandlerblog.com/blog/rag-agent-backend/)、[2026-06-26 评测复盘](https://chandlerblog.com/blog/rag-evaluation/)、[源码](https://github.com/Joyman0601/RAG) | Java 17＋直接模型 API 起步，先跑完整链路，明确最初内存状态／mock 权限等不足；后补文档检索、工具循环、标准答案与无答案评测 | 展示学生项目可分阶段完成的路径。源码和文章可读，本轮未运行；作者自测分数、题数与组件不作为我们的招聘门槛 |

补充失败记录：[Chandler 2026-08-01 彩排复盘](https://chandlerblog.com/blog/rag-demo-rehearsal-bugs/) 的搜索缓存正文涉及观测数据为零、连接重置和长文档工具结果撑爆上下文；直接文章未成功读取，仅作低权重提醒，不把它当本项目已复现事实。排除缺少过程的泛题清单、未公开采样的市场比例及付费项目宣传。

交叉判断：同一句“做过 Agent／RAG”，可以对应一次接口调用，也可以对应工具、状态、业务集成、评测和部署。对当前用户，推荐以真实业务单 Agent 为主体，并完成小型 RAG 与一个 MCP 的学习实践；完整代码、实际失败与修改前后对比才支持个人能力表达。该推荐是工程判断，不保证录用或覆盖所有岗位。

## 2. 项目难度判断

Java 主线仍按 [实习准备路线](roadmap.md#实习准备与阶段关系) 和原始技术目标推进。岗位样本支持把独立模块开发、基础知识、排错和质量意识作为底座；不能把 Agent 接入当作已经掌握 Java 的证明。

下表是基于岗位要求与项目复杂性的工程判断，不是企业统一难度评级或录用保证：

| 层次 | 可检查结果 | 难度与定位 |
| --- | --- | --- |
| 入门调用 | 一次结构化模型调用 | 较低到中等；可快速验证外部调用，但质量与运行控制仍需另外验证 |
| 业务 Agent | 真实业务工具、研究上下文与回答质量闭环 | 中等偏高；适合在已有 Java 系统上逐步增加应用工程能力 |
| 小型知识检索／外部工具实践 | 少量受控文档的检索、引用或一个 MCP 工具 | 在主体上增加中等难度；大规模语料、多用户授权与多供应商生产运行才继续提高难度 |
| 通用平台／算法 | 复杂多 Agent、自主长任务或模型训练 | 高；岗位和能力范围与当前业务应用目标不同 |

对当前项目，接口和模型调用的代码量不是主要难点。较难的部分是：研究时间线分批读取时是否遗漏、成员观点与事实能否区分、历史上下文能否继续对应当前授权、工具失败后模型是否仍编造完整结论，以及模型／提示词变化后如何比较质量。正式验收结果维护在 V2 需求与后续 specs，不在调研中再维护一套任务清单。

早期评测规模可参考 [Anthropic 的评测资料](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)：从实际失败与代表性任务的小题集开始，例如 20～50 个问题。这是起步建议，不是招聘数量门槛；问题与评分设计应覆盖正确工具选择、引用语义、空数据、过期信息、权限变化、截断和外部失败。代码替身测试与真实模型质量评测证明不同事情，不能相互替代。

不能据此承诺固定工期或把某个完成率直接设为“实习合格线”。模型效果、实际数据与用户学习进度需要试运行后判断，性能和费用只能使用真实测量。可实现性、最早验证与失败替代统一见 [能力验证](../architecture/technology-stack.md#34-可行性验证与知识安排)，不通过新加框架数量证明项目深度。

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
