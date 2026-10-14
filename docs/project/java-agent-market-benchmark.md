# Java 与 Agent 求职能力对标

首次调研：2026-10-08；最近复核：2026-10-12（中国标准时间）

适用目标：Java 后端实习／应届，逐步增加 Agent 应用工程能力  
性质：公开岗位、求职者面经、项目作者复盘与官方资料的交叉证据记录。第 1～4 节保留 2026-10-08 的基础证据；第 5／6 节记录 2026-10-09 的岗位与课程核查；第 7 节保存 2026-10-12 的 Java 基础补漏；第 8 节是同日进一步取得近期 Java／Agent 官方完整岗位后，对教学地图的再评估。产品尺度见 [V2 规划基线](../product/requirements-v2.md#3-正式讨论采用的规划基线)，技术、知识安排与可行性验证见 [技术规划](../architecture/technology-stack.md#34-可行性验证与知识安排)；调研不充当详细规格或实现证明。

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

交叉判断：同一句“做过 Agent／RAG”，可以对应一次接口调用，也可以对应工具、状态、业务集成、评测和部署。对当前用户，推荐以真实业务单 Agent 为主体，完整 V2 必须把系统报告库 RAG 接入同一助手，并完成一个 MCP Server 的真实实践；独立小实验只作提前验证。完整代码、实际失败与修改前后对比才支持个人能力表达。该推荐是工程判断，不保证录用或覆盖所有岗位。

## 2. 项目难度判断

Java 主线仍按 [实习准备路线](roadmap.md#实习准备与阶段关系) 和原始技术目标推进。岗位样本支持把独立模块开发、基础知识、排错和质量意识作为底座；不能把 Agent 接入当作已经掌握 Java 的证明。

下表是基于岗位要求与项目复杂性的工程判断，不是企业统一难度评级或录用保证：

| 层次 | 可检查结果 | 难度与定位 |
| --- | --- | --- |
| 入门调用 | 一次结构化模型调用 | 较低到中等；可快速验证外部调用，但质量与运行控制仍需另外验证 |
| 业务 Agent | 真实业务工具、研究上下文与回答质量闭环 | 中等偏高；适合在已有 Java 系统上逐步增加应用工程能力 |
| 完整业务 Agent 的资料与工具扩展 | 系统报告 RAG 接入真实问答与引用页面；一个 MCP Server 被客户端实际发现和调用 | 在主体上增加中等难度，是本项目完整 V2 的必做部分；大规模语料、多用户授权与多供应商生产运行才继续提高难度 |
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

2026-10-08 版本复核补充：此前只核对了 Boot 与 AI 的兼容关系；本次进一步核对维护周期，官方 [Boot 3.5.16 发布说明](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/) 明确这是 3.5.x 最后一个开源发行版。因而“Boot 3.5＋AI 1.1 兼容”不代表它是新的长期接入首选。目标线、迁移风险和退出路径已同步技术规划；[官方矩阵](https://github.com/spring-projects/spring-ai#getting-started)、[Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html) 与 [MyBatis-Plus starter 说明](https://baomidou.com/getting-started/) 是版本判断的依据。当时实际项目兼容性尚未验证；2026-10-12 后续正式验证见 [AG-CODE-002](../specs/004-research-agent/tasks.md#9-ag-code-002正式后端框架接入2026-10-12)，不以本节历史状态代替当前技术栈。

## 4. 官方技术依据

- [Spring AI 工具调用](https://docs.spring.io/spring-ai/reference/api/tools.html)：模型请求工具调用，应用执行工具并回传结果；框架 API 随版本变化，以最终选定版本为准。
- [Spring AI 可观测性](https://docs.spring.io/spring-ai/reference/observability/)：模型／工具观测、Token 与敏感内容记录边界。
- [Spring AI RAG](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)：检索与上下文组合、元数据过滤及空检索结果处理。
- [MCP 官方介绍](https://modelcontextprotocol.io/docs/2026-07-28/getting-started/intro)：外部系统与工具连接标准。
- [Anthropic Agent 评测说明](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)，2026-01-09：从实际问题的小题集开始，检查执行记录，结合确定性检查与质量评估。
- [Spring AI Alibaba 概览](https://java2ai.com/docs/overview/)：图运行时、工作流与 Agent 能力；只作候选比较。

本文件保存调研依据与建议尺度，不能充当详细规格、实现证明或个人掌握证明。后续岗位目标、招聘要求或框架兼容性变化时按工作流重新核对。

## 5. 2026-10-09 重新核查与计划对照

用户再次要求同时核查大厂、非头部企业、行业实践与求职者经历，以避免只按宽泛 JD 安排一学期。本轮以企业官方／企业发布的职位正文、作者原帖和一线工程分享为主。区分发布时间、刷新时间、招聘批次、页面日期和读取时间；可访问不等于仍有名额。公司名、平台规模标签与匿名作者的“小厂”称呼不作为统一规模评级。同一企业多个职位展示层级差异，不计算市场占比。

### 5.1 岗位正文：Java 业务、Agent 应用与算法／平台分别比较

本表正文均已实际访问；无日期页面只记本次复核，不伪造发布日期。

| 来源、日期与层级 | 入场条件与工作／加分项的区别 | 对当前路线的意义 |
| --- | --- | --- |
| [百度 AI 开放平台研发实习 J103363](https://talent.baidu.com/jobs/detail/INTERN/ef30e579-2d3d-4539-ad90-884521b815c9)，页面日期 2026-07-21，未区分发布／更新 | 必备 Java、集合／并发／JVM、Boot 独立 REST 服务、MySQL／Redis；MQ 优先。Agent／RAG／Function Calling／MCP、流式、Token 计费、鉴权／限流／观测列加分；工作含 AI 能力服务化 | 直接支持 Java＋AI 工程路径；不能把全部平台职责或加分项变成学生共同门槛 |
| [百度 Agent 应用实习 J105397](https://talent.baidu.com/jobs/detail/INTERN/83a5dbcc-48a6-4356-b6d6-b4da9f0a2528)，页面日期 2026-09-03 | 要求计算机基础、大模型常用技术与原理、LangChain／Agent 实践；工作含知识库、数据治理、评测样本与规则、上下文与异常状态；未明确语言／学历 | RAG、流程和评测有岗位依据；不能只凭 Java 项目宣称满足此岗全部条件 |
| [美图 Go／Java 后端实习](https://hr.meitu.com/en/jobCampus/67ec47a2-7f1c-42b6-9c63-5f6515ac9520)，2026 暑期、5 个月以上，未见发布日期 | Java 方向要求 Boot／Cloud、多线程／JVM、SQL／Redis、Linux、Kafka 概念；有 AI Coding 实践，了解 Prompt Engineering 或 RAG 基本概念；业务 Agent／完整项目列加分 | 同一公司也有业务后端结合 AI 的路径；暑期材料不能当十月新岗位 |
| [海康软件产品 Java 实习](https://talent.hikvision.com/home/socity/position?postId=3DBC4346A3391150639BCB4895723F64)，发布 2026-06-04 | 本科、Java IO／线程／集合、Spring／MyBatis／MySQL；参与需求、编码、测试、缺陷定位与部署 | 普通 Java 基础与模块交付仍需保留；该岗未逐项要求 Agent |
| [用友 Java 实习—友新星 27 届](https://www.nowcoder.com/jobs/detail/449276?channel=npJobTab&deliverSource=1&pageSource=5001)，公司 HR 发布，无发布日期 | 本科、计算机基础；工作含 Boot／Cloud 接口、SQL、测试排错，以及协助 AI 权限／会话／输出适配；5 天／周、至少 5 个月 | 业务后端增加 AI 能力是实际工作；未逐项列 RAG／MCP，不推导所有 AI 岗要求 |
| [帆软 Data Agent 后端实习](https://www.shixiseng.com/intern/inn_wanlnzz7qdtt?pcm=pc_SearchList)，2026-09-16 刷新、11-01 截止 | 本科、Java 等一种后端语言、数据库与调优、线程／缓存／异步 I/O、ML／NLP 基础与 LLM 应用认知；参与模块和产品需求 | 较新样本支持后端＋场景 Agent；刷新不是首次发布 |
| [蜀通 Java AI 实习](https://www.zhaopin.com/jobdetail/CC336010610J40865980510.htm)，无发布日期，本次正文可读 | Java／算法、Boot Web 项目、MVC／MyBatis／Maven、AI 初步认识；带教 RAG、Java API 工具封装、答案标注比较；框架示例经验加分 | 10 月 8 日仅索引的证据已增强。带教尺度可参照；页面出勤／时长描述有差异，不替企业解释 |
| [爱搭 AIda Java 后端／全栈实习](https://xxgc.shzq.edu.cn/2026/0331/c594a52400/page.htm)，高校院系发布 2026-03-31 | 要求 1 年 Java 经验、Boot／Cloud／MyBatis、SQL／Redis／JVM、前端联调与工具；AI／ML 和 Docker／CI/CD 优先；至少 6 个月 | 实习名下也有偏高要求；不把此例当普通实习统一最低线 |

上限与语言对照：[百度 Agent 算法／多模态实习 J105461](https://talent.baidu.com/jobs/detail/INTERN/d4d70ef3-73e6-4ce2-bcc1-11c384cc48f0)，页面日期 2026-09-09，明确精通 Python、熟悉 PyTorch；[帆软高级大模型应用算法工程师](https://join.fanruan.com/social/detail?id=9857)，无日期、3 年研发要求，含框架二次开发、多 Agent、混合检索／重排及生产优化。名称有“应用／Agent”不代表同一门槛，这些不是当前路线已覆盖岗位。

补充官方索引：[蔚来 2027 届 Agent 应用岗](https://nio.jobs.feishu.cn/campus/position/7673028746206677267/detail) 返回过 Java 等一种语言、Web／数据库／鉴权基础及 RAG／评测职责，但直开无正文、公开 HTML 404；只作低权重补充，不声称当前可投。

### 5.2 面经：看实际追问与求职背景

以下原文已访问，均为作者自述；学历、实习、面试发生及结果未独立验证。题目受其简历、岗位和资历影响，不能合并为普通本科必做清单。

| 第一人称来源与日期 | 实际追问 | 如何用于我们的准备 |
| --- | --- | --- |
| [托马斯杨儿春招复盘](https://www.nowcoder.com/discuss/869231276035760128)，正文明确 2026 年 2 月末至 4 月初，自述软件硕士、有云计算后端实习 | Java／并发／JVM、SQL／事务／Redis、算法；AI 有分块、混合检索／重排、文档过期、记忆、评测和 AI Coding 验证 | 基础与 Agent 并行；作者背景较强，自报次数含交流，不转换为市场题频或录用率 |
| [万隽 FOSHO AI 应用一面](https://www.nowcoder.com/feed/main/detail/d95927b94e994681beee22975766721e?sourceSSR=dynamic)，正文 26.7.16、07-23 编辑，自称小厂 | 视频项目实际耗时／上限、长任务、检索组装、瓶颈；Coding Agent 上下文、为何自研和选语言 | 小公司也会深挖真实实现；一次零八股不支持停学 Java，视频功能不搬入股票项目 |
| [百度大模型生态集成实习面经](https://www.nowcoder.com/feed/main/detail/62d4ca9866d84d63bf6eafbb0a947bb8)，标题 26.4、04-22 编辑 | Python、新需求流程、Skill／计划模式、检索／向量匹配、工具、设计模式 | 基础 Python 与 AI 开发验证有价值；此岗位不能代表所有 Java 应用岗 |
| [深信服 Agent 实习一二面](https://www.nowcoder.com/feed/main/detail/14b2c379ae434062a009aefea9fc5df9)，09-02，年份未独立确认 | 业务目的／完整数据流、评测、难题、AI Coding 质量；百工具发现／注入、MCP 区别；现场算法 | 练链路与排错；百工具／多 Agent 属其项目上下文。作者称通过后收到感谢信，不据技术准备保证录用 |
| [亚信安全 Agent 一面](https://www.nowcoder.com/feed/main/detail/00254f151d484eb89f8ab6781125d05a)，标题 9.2、09-09 编辑，年份未确认 | 评测集、重复次数、耗时、任务恢复、并发 Token 预算预占 | 正式评测与运行控制须可解释；长任务／训练知识不全部成为首版实现要求 |

学生项目对照沿第 1.2 节 Chandler 的 2026-05／06 原文与源码：参考渐进补齐和评测方法，不引用未复现分数为招聘门槛或本项目指标。模拟面试、二手改写、泛题单、付费项目宣传不作主要面经证据。

### 5.3 工程实践：完成标准应超过一次成功回答

| 一线来源与日期 | 核对到的实践 | 启发与边界 |
| --- | --- | --- |
| [美团《Agent 评测白皮书》01](https://tech.meituan.com/2026/09/10/Agent-Evaluation-White-Paper-01.html)，2026-09-10，企业技术团队 | 按业务任务、环境、参考依据和规则评估，结果／过程分开看；保留失败，变更后回归 | 明确问题与判断依据，定位失败步骤；不搬入完整企业评测平台 |
| [Spotify 工程师 Pratik Rasam 的 Java 生产实践](https://www.infoq.com/presentations/spotify-multi-agent-ai-architecture/)，页面 Recorded at 2026-10-08 | Java ADK 广告系统；年龄范围／地理实体歧义和工具 500，改枚举、上下文、确定性校验与结构化错误；结合轨迹、规则和模型评分 | 工具设计、验证和坏例子改进有真实价值；国外生产经验不作国内实习门槛，多 Agent 因该产品分工，不强制照搬 |
| [Anthropic 工程评测实践](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)，2026-01-09 | 从小而明确的代表任务起步，记录过程／结果、重复试验，人工校准评分器 | 20～50 题仅为起步建议；明确样本和评分比完整评测平台更先需要 |

另读 [阿里云云原生账号 2026-09-28 调研](https://www.cnblogs.com/alisystemsoftware/p/23146808)：自述线下沙龙 1906 份问卷，涉及上下文、成本、观测和评测。它是自选工程受访者调查，不是招聘调查或全国随机企业样本；不由其百分比推导岗位、语言占比或学生门槛。

### 5.4 与现有计划的对照结果

保留 Java 系统＋有界研究 Agent 的功能路线，调整学习权重和完成证据。现地图已有 RAG、工具、会话、MCP、框架、安全、费用与评测，不通过再加热门功能证明难度。

| 原计划 | 本次处理与理由 | 唯一落实位置 |
| --- | --- | --- |
| Java 后端为基础，加 Agent 应用 | 明确主投 Java 后端及接受 Java 的应用岗；Python 硬要求、算法／高级平台另查差距，基础和算法持续准备 | [实习准备路线](roadmap.md#实习准备与阶段关系) |
| 八组主题，模型基础偏调用 | 补生成、Token／上下文、Embedding、Attention 的概念原理与 RAG／微调区别，能解释，不增训练课程 | [能力覆盖表](../architecture/technology-stack.md#原始-agent-目标覆盖与验收) 与路线学习深度 |
| 评测已有固定集，RAG 已要求检索／重排比较 | 加强实际比较产出和坏例子：依据、条件、失败阶段、修改和复测；不把 15 题自造实验扩写为成熟业务证明 | [AG-CODE-007／008](../specs/004-research-agent/tasks.md#1-实施顺序) |
| 带做与独立练习结合 | 当时提出业务修改、工具／检索改动、故障定位、短演示与取舍表达，分别验个人能力与功能；当前教学参与与证据分类沿教学方法，不由历史建议设逐课编码门槛 | [教学方法](teaching.md)；V2 模块产出见 [学习地图](roadmap.md#怎样学与完成标准) |
| 基础 Python 随脚本／适配学 | Java 保持主语言；脚本基础不能替代 Python 主语言岗位。后来明确选该路线，再补独立 Python Web／API 能力 | 技术栈原始目标覆盖表 |
| 高级 Agent 后学，外部条件提前预检 | 保留；Skills／Harness 对应任务、状态、工具、观测与评测理解，不另造平台；中文检索、框架、财务／授权各自早验 | 技术规划与 [开发顺序](roadmap.md#做的顺序与学的顺序) |

推荐尺度为中等偏高的应用工程：连续可用、数据与依据真实，受限工具和系统报告 RAG 能组合，失败能定位、效果能比较，启动与演示可复现。有以上岗位与实践依据，但不是统一录用线；推进按个人独立能力和阶段验证判断，不承诺固定学期内掌握或保证就业。

## 6. 课程核查与选学建议（2026-10-09）

用户要求核实外部 GPT 推荐的尚硅谷 Spring AI Alibaba、黑马 2026 AI 版天机学堂及一个月学习安排，随后追问框架必要性、LangChain 路线和课程时效。本节保存 2026-10-09 的课程核查与当时选材依据；当前观看安排、业务实践顺序及个人能力标准只见 [现有学习地图](roadmap.md#v2-功能与学习地图)，不将调研中的选材表当成另一套执行课表。框架关系见 [入门解释](../engineering/agent-experiment.md#14-agent-与框架是什么关系)，建议不新增已确认业务范围。

### 6.1 课程比较与读取边界

| 主办者原始页面 | 当时可核实内容 | 选材理由与使用边界 |
| --- | --- | --- |
| [java1234 官方 Spring AI2.0 基础课](https://www.bilibili.com/video/BV1DAoZBRERx/)，2026-04-26 | 原作者公开视频有 16 集目录，分集约 3 小时；单体示例，含 ChatClient、Advisor、结构化输出、Memory、Tool Calling、向量／RAG、MCP | 保留为入门选材依据，是否补看片段沿当前学习地图。录制早于 2.0 正式版，精确配套 BOM 未核实；版本核对和代码练习由 AI 带学，不要求学生自行迁移，不以三小时视频代替业务实践 |
| [黑马 SpringAI＋DeepSeek 大模型应用开发](https://www.bilibili.com/video/BV1MtZnYtEB3/)，2025-04-01 | 官方 UP 主页面索引有 26 集目录；分集时长相加约 8 小时 32 分钟。包含模型调用、ChatClient、会话记忆／历史、提示词、工具调用、PDF 向量检索与多模态 | 保留研究记录，当前不追加整套观看要求；以后确有讲解缺口时，由 AI 指定具体章节并处理代码版本差异 |
| [尚硅谷 Spring AI Alibaba：B 站官方入口](https://www.bilibili.com/video/BV1pvWGznEqh/)，2025-09-23；[官网目录](https://www.atguigu.com/video/347/) | 官方 UP 主原页索引可读；官网有 86 节目录，明确先修 JDK17＋、Boot3、Maven、Redis、大模型入门。覆盖结构化输出、持久化记忆、SSE、RAG、Tool Calling、MCP 和百炼生态案例；标明教程／资料免费下载；原始精确 BOM 仍未取得 | 移出本阶段默认学习安排。原理不能整体判为失效，但原课不能视作 2.0.1 的原样跟写教材；用户尚不能自行核对 API，不再要求其自行选专题补课 |
| [黑马 2026 AI 版天机学堂](https://www.bilibili.com/video/BV1MFokBkEzC/)，2026-04-28 | 官方 UP 主页面索引有 106 集目录，主办者定位为 AI＋Java 微服务进阶课。可见 aigc 微服务、Nacos 提示词热更新、持久化会话、ES 检索、多智能体协作和故障修正；未核实到完整精确依赖和独立 MCP／评测专题 | 当前不列为入门后的必修第二课。用户尚未掌握微服务，运行与理解其完整工程会叠加独立学习成本；以后补微服务或遇到明确专题问题再参考 |

核查依据为主办者介绍和可读分集目录；B 站部分直接打开返回 412，使用搜索索引中的主办者原页内容补足。未逐集观看、领取配套源码或运行课程工程，不能据此评价哪位老师讲得最好，也不能保证全部下载链接或代码可运行。黑马视频为公开分集页，未见付费解锁说明；API／云资源费用仍须分别核对。当前没有必须购买培训的依据。

尚硅谷视频补查：上轮只给官网入口，本轮找到上述官方 B 站发布；[尚硅谷官方课程专栏](https://www.bilibili.com/read/cv5216534)的索引也将 Spring AI Alibaba 对应到同标题的“01_教程简介”。原页索引会混入关联合集目录，章节编号以官网该套 86 节目录核对；不据此冒认所有关联合集都是本课内容。原视频发布于 2025 年，仍需沿 6.3 核对现行 API，方便观看不等于版本更新。

java1234 标题中的“2027”不是录制日期；[原作者公开文章](https://blog.csdn.net/caoli201314/article/details/160716256)能核对 Java／ChatClient 示例，但没有完整精确 BOM。该课与天机初次发布均早于 [Spring AI2.0 GA 的 2026-06-12](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)，不能仅凭年份或“2.0”标题保证使用最终正式 API。当时选它作为入门材料，是因为较小规模和核心专题覆盖，配套代码尚未完整验证，实际代码不直接承诺照搬。

用户反馈不能自行阅读官方 API 后，再次窄查近期候选：[图灵学院徐庶 38 集原视频](https://www.bilibili.com/video/BV12ju16hEYM/)可核对原作者与课程介绍，但未取得公开稳定版 BOM；另见老杜 9 月和乐哥 8 月原作者课程的 B 站搜索索引，仍未取得足够的独立目录与配套依赖。当时没有核实到可按稳定版完整跟写、适合本阶段的替代主课，不将新标题／上传日期当作推荐依据。选材和版本验证由 AI 承担，后续观看与实践安排由学习地图维护；不声称近期中文课程不存在，也不声称新版练习已经完成。

### 6.2 章节与项目的对应

下表保留 2026-10-09 的课程片段与项目主题对应，供 AI 在实际理解缺口需要时选材；当前是否观看及本人任务由 [学习地图](roadmap.md#怎样学与完成标准) 和 [学习交接](status.md#求职专题学习交接) 决定，不按表中序号重新开始课程。教学方式沿 [教学方法](teaching.md)，版本迁移由 AI 处理。Java 框架采用与 HTTP 实验边界分别见技术栈和任务证据。

三小时入门讲组件和最小示例；本项目实践讲有权资料、真实查询、会话对象、引用与失败处理；尚硅谷增加专题细节及 Alibaba 生态；天机还需理解既有微服务工程与跨服务问题。它们不能按“短课→长课→项目课”的时长机械排列。接下来的主实践是现有股票系统，框架版本升级本身不要求拆成微服务。

| 素材对应主题 | 可参考的课程片段 | 与本项目实践的关系 |
| --- | --- | --- |
| 1. 模型、消息与一次请求 | java1234 1、3；第 2 集是百炼接入，已有官方 DeepSeek 账号不因视频重开户；4～5 的 Ollama 暂不作为必修 | 在带学下理解谁发送问题、模型返回什么，亲手改一处问题或输入校验；依赖与模型适配由 AI 先核对 |
| 2. 提示词与结构化输出 | java1234 7～9，Advisor 先理解在请求前后做什么，不要求第一遍读源码 | 在正式研究梳理功能中修改一段提示词或输出校验，区分模型生成与程序验证；结合主项目本阶段的版本与代码验证 |
| 3. 只读工具与循环 | java1234 11，对照正式受限工具；由 AI 解释当前工具循环接口 | 由 Java 绑定登录用户、空间、股票和周期，读取有权观点并回传；能处理缺资料、撤回、分页不完整及越权，而不只看最终文字 |
| 4. 会话与页面交互 | java1234 10、6；结合正式会话与流式代码安排小改动 | 区分保存聊天与送给模型的上下文，完成个人研究对象隔离、恢复／清空，再补 SSE 与结束／失败状态 |
| 5. 系统报告检索 | java1234 12～14；由 AI 带学报告处理、检索和引用 | 提前验证中文报告解析与检索；正式接系统维护、脚本导入的报告库，核对段落引用和更新／移除。课程上传案例只用于理解资料处理，不新增普通用户上传功能 |
| 6. MCP 实践 | java1234 15；由 AI 先核对当前 SDK 与依赖，再带学客户端／服务端 | 一个客户端真实发现并调用受控工具，说明 MCP 与 Tool Calling 的关系；再按需要学习编排 |

这张表只说明课程素材可以解释哪些项目问题，不规定当前授课顺序或重新观看要求。尚硅谷与黑马旧课的可用原理保留在研究判断中；以后若需慢讲材料，由 AI 指定具体章节，不能仅说“按需补课”。

学习章节与开发验收分开：初次研究梳理是 Java 固定读取资料后的整理；追问才检验模型按需选择工具。当前 `StockResearchLifecycleService.timeline` 每批约 20 条且可以跨周期，正式工具需核对选定范围，不能将首批页面内容当作整周期资料。模型循环的自造样本已有实现，但尚未调用正式业务 Service；本轮未新增实现或重跑模型。

当时对一个月安排的判断：第一段成果宜为理解并参与实现一个有权限、引用和失败处理的真实工具闭环，再按个人表现推进会话和报告检索；不以固定四周承诺完成全部主题、掌握多 Agent 或达到招聘要求。完整 V2 的 RAG／MCP 目标、Java 并行练习及当前授课顺序由学习地图维护。

### 6.3 课程版本与招聘说法的复核

本轮追查“四月与现在的差别”：[官方 2026-03-26 公告](https://spring.io/blog/2026/03/26/spring-ai-2-0-0-M4-and-1-1-4-and-1-0-5-available/)发布 1.1.4／2.0.0-M4，[2026-04-27 公告](https://spring.io/blog/2026/04/27/spring-ai-1-0-6-1-1-5-2-0-0-M5-available-now/)发布 1.1.5／2.0.0-M5，2.0 正式版到 6 月才发布。因此 4 月 26 日首发的课程处于预发布时期，不能视作已经讲完正式版；但发布时间也不能代替源码中的精确依赖版本。当前稳定版为 2.0.1，2.1.0-M1 是预发布，不要求为了“最新”追预发布。

| 四月后已核实的相关变化 | 对当前研究助手的影响 | 学习处理 |
| --- | --- | --- |
| [2.0 RC1 工具循环改造](https://spring.io/blog/2026/06/06/spring-ai-2-0-0-RC1-available-now/)移除各 ChatModel 内部自动循环及 internalToolExecutionEnabled，推荐 ChatClient＋ToolCallingAdvisor；Memory 默认在循环外，窗口按用户轮次截断 | 正好涉及多次查询和续聊。普通会话记录与中间工具轨迹不能简单当成同一份持久化记录 | 保留“模型提调用→Java 执行→回传”的原理，正式跟写时核对循环入口与 Memory 组合 |
| [当前升级说明](https://docs.spring.io/spring-ai/reference/upgrade-notes.html)列出 MCP 注解、传输模块及 SDK API 迁移 | 老课的依赖、导包和定制请求代码可能需要调整；第一个 Java 本地业务工具不依赖 MCP | 在 MCP 段核对当前依赖与调用方式，不要求先拆服务 |
| [2.0.1 发布记录](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.1)含工具调用限制、流式取消／循环状态、PDF 与向量检索等修复 | SSE 和 RAG 主原理仍可学，结束、取消、解析与检索效果要按实际版本验证 | 按当前稳定 API 实践，并验证项目失败场景，不按版本标题推断效果 |
| [2.0.x 官方兼容说明](https://docs.spring.io/spring-ai/reference/getting-started.html)对应 Boot4.0／4.1，当时工程是 Boot3.2.0 | 主项目依赖有兼容改造成本；升级 Boot 与采用微服务是两项不同选择 | 当时先隔离验证框架，该轮没有升级；当前采用状态只见技术栈 |

当时选课判断：已有概念材料仍可用于理解，正式代码需要补该轮核查的 API；若找到确实展示 2.0 正式版 BOM、工具循环和 MCP API 的近期材料，可减少迁移负担。不能以“2026／2027 最新”标题或上传日期替代这些证据。当时未取得精确完整课程源码，不承诺逐行照搬可运行，也不把旧课整体判为作废；当前是否继续观看沿学习地图决定。

- 2026-10-09 核查当时主工程为 Java17、Boot3.2.0、MyBatis-Plus3.5.5，未引入 Spring AI；后续实际迁移只见 [技术栈](../architecture/technology-stack.md#1-后端与数据)。核查时 [Spring AI 官方发布列表](https://github.com/spring-projects/spring-ai/releases) 将 2.0.1 标为最新稳定版，2.1.0-M1 为预发布；[官方兼容说明](https://docs.spring.io/spring-ai/reference/getting-started.html) 的 2.0.x 对应 Boot4.0／4.1。正式集成继续沿技术栈中的隔离兼容验证，不因跟课升级主项目。
- [Boot3.5.16 官方说明](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/) 已宣布 3.5.x 开源支持结束。旧版本可以匹配课程练习，但不能自动作为本项目长期目标；[MyBatis-Plus 官方安装说明](https://baomidou.com/getting-started/install/) 的 Boot4 starter 自 3.5.13 起提供，当时的 3.5.5 需另验迁移，当前采用状态沿技术栈。
- [Spring AI Alibaba 发布列表](https://github.com/alibaba/spring-ai-alibaba/releases) 的稳定标记在 1.1.2.2，2.0.0-M1.1 仍为预发布，并依赖旧的 Spring AI2.0.0-M1。两框架版本不同步，课程里的百炼适配／编排代码不证明可直接兼容当前 Spring AI 稳定线。
- 黑马短课发布早于 [Spring AI1.0 GA 公告](https://spring.io/blog/2025/05/20/spring-ai-1-0-GA-released) 的 2025-05-20，因此原始 API 可能较旧；未取得源码，不能断言具体里程碑版本。所列视频配套精确依赖均未运行核验，学习时取其流程，实际代码按选定版本官方文档适配；模型继续使用已选 DeepSeek，不因课程默认百炼重新要求开户或切换。
- 原 GPT 的高德样本可以在 [职先机历史页](https://jobs.ultraai.site/jobs/alibaba_campus/internship%3A199903720028) 查到：2026-09-09 收录、09-23 确认下线，含 Java、Spring AI、Tool Calling、RAG。官方职位当前未取得可读正文，日期属于聚合页记录，不能描述为仍可投或官方双重核实。
- [百度 AI 开放平台研发实习 J103363](https://talent.baidu.com/jobs/detail/INTERN/ef30e579-2d3d-4539-ad90-884521b815c9) 官方正文再次可读，页面日期 2026-07-21：Java／Spring Boot／MySQL／Redis／计算机基础为要求，Agent／RAG／Function Calling／MCP 项目列为加分。支持 Java＋AI 工程路径的存在，不证明全部 Java 岗都要求 Agent。
- [凌脉 AI 应用实习面经](https://www.nowcoder.com/feed/main/detail/76f5be8b8bd5420b94d32a66e26a7ad9) 的作者原页索引自述面试时间为 2026-07-11，追问资料、检索链路、MCP 鉴权及 Token；未经独立验证，作为项目追问参考，不作为统一招聘门槛。[Anthropic 的工程建议](https://www.anthropic.com/engineering/building-effective-agents) 与 [美团评测实践](https://tech.meituan.com/2026/09/10/Agent-Evaluation-White-Paper-01.html) 分别支持按问题增加复杂度、保留失败并比较变更效果，不要求初学者复制整套生产平台。

### 6.4 LangChain 分支与课程时效

LangChain 是另一条框架路线，值得了解，但不作为本学期 Java 主项目与开始投 Java／接受 Java 的 Agent 实习的必修依赖。正式学习时机由目标岗位明确要求熟练 Python／LangChain、决定承担 Python Agent 服务或经验证确实需要某项 Python 库触发；“主流”本身不足以要求同时学两套。具体学期顺序与投递准备检查只见 [实习准备与阶段关系](roadmap.md#实习准备与阶段关系)。选择 Python Agent 应用方向时，需要补真实 Python 方法／API 和工程能力；Java＋Python 服务还需处理授权边界、超时与部署。本项目当前推荐学一种 Java 框架；LangChain4j 是可验证的 Java 替代，其 starter 仍有版本门槛，兼容性须按当前技术栈另验，不沿用调研当时 Boot 3.2 的判断。语言和职责的解释只见上方入门材料。

| 主办者原始课程 | 核实内容与边界 | 使用时机 |
| --- | --- | --- |
| [LangChain 官方 Essentials—Python](https://academy.langchain.com/courses/langchain-essentials-python) | 官方标 Free，18 节、约 1 小时视频；模型、消息、工具、Memory、MCP、结构化输出及人审；代码为 Python | 可用于了解生态，进入动手前补 Python 基础；不当作 Java 课程 |
| [尚硅谷 2026 LangChain](https://www.bilibili.com/video/BV1rv7A6oEeP/)，2026-06-23 | [官方总览](https://www.atguigu.com/video/llm/)声明基线 1.2.12、Python 前置，含 Agent、中间件、记忆、RAG；1.2.12 是课程基线，不是本轮最新版本结论 | 将来扩展 Python Agent 路线时的中文主课候选 |
| [尚硅谷 LangGraph](https://www.bilibili.com/video/BV1z3NY66EY1/)，2026-07-15 | [官方目录](https://www.atguigu.com/video/366/)有 132 条，Python 前置，含状态、分支、Checkpoint、人审、流式和 LangSmith | 理解模型／工具后，再按复杂状态与恢复需要学编排 |

另核实 [黑马 2026 LangChain＋LangGraph](https://www.bilibili.com/video/BV178w1z7EHQ/)发布于 2026-03-20，但本轮可见公开视频目录仅 28 集，重点为工具、记忆、LangSmith 与 AI 私厨，不能凭标题证明已经完整公开 Graph／RAG／系统评测课程。后续选择时先核对实际可读目录。

官方 [LangChain 当前入口](https://docs.langchain.com/oss/python/langchain/overview)为 create_agent；[v1 迁移指南](https://docs.langchain.com/oss/python/migrate/langchain-v1)将旧 Chain 等移至 langchain-classic。课程若主要围绕旧 LLMChain／AgentExecutor 等，应明确旧 API 基线，不能装最新包盲跑。选课同时核对原作者、实际发布时间、语言／前置、框架基线和目录；新上传或新标题不等于重录过当前 API，近期视频也可能需要迁移。

## 7. 2026-10-12 Java 后端教学范围复核

本轮重新读取用户提供的三个官方岗位，并补一个九月发布的企业官方后端实习样本。以下是能力比较，不是正在招聘的岗位清单；页面可读或有投递按钮不证明岗位仍开放。第 1／5 节的面经和工程实践仍保留为当时证据，本轮没有将其重新标成最新样本。

### 7.1 官方岗位与读取边界

| 官方岗位 | 页面日期／本轮读取状态 | 一般要求与优先条件 | 对教学范围的作用 |
| --- | --- | --- | --- |
| [海康睿影 Java 实习](https://talent.hikvision.com/home/socity/position?postId=9BE3D2BA90CC2E6E4C8AD09E0A45FACB) | 2026-07-30 发布，正文可读；入口属于社会招聘，岗位标题明确为实习 | Java／面向对象、Boot、MySQL／SQL、Redis、MQ 概念、Tomcat、文档和项目经验；微服务／分布式概念列优先 | 请求容器、基础中间件与真实业务解释不可漏；不用优先项把实习范围扩大为微服务平台 |
| [海尔 Java 实习](https://maker.haier.net/client/practicemobile/jobdetail/id/10001632) | 2026-06-29 发布，正文明确已过期；2027 届、出勤与时长需单独核对 | Java／面向对象、数据结构算法、数据库基础、开发测试和协作；框架、Linux、前端、开源等列加分 | 算法基础并行，框架和工程工具服务实际开发，不把加分项统称硬门槛 |
| [美图 Go／Java 后端暑期实习](https://hr.meitu.com/jobCampus/67ec47a2-7f1c-42b6-9c63-5f6515ac9520) | 本轮官方全文已成功读取，该地址重定向至用户原 `/en/` 链接；未见发布日期，描述为 2026 暑期、实习 5 个月以上 | Java 分支含 Boot／Cloud、多线程／JVM、MySQL／Redis／SQL 优化、Kafka 概念、Linux／Git及 AI 开发基础；高并发／分布式／Docker、业务 Agent 和完整项目列加分 | 补 Linux 与 MQ 概念，保留并发／JVM／SQL；暑期样本不写成十月新岗位，Cloud 不因此成为独立必修 |
| [TNG Digital 后端实习](https://www.tngdigital.com.cn/tngd_career/) | 页面日期 2026-09-24，官方全文可读；条件写 2026 届、每周至少 4 天、至少 4 个月 | Java／集合／异常、真实 Boot 项目、SQL／join、Git／Linux、算法及 JVM／线程／网络概念；Redis／MQ、分布式／幂等／限流、监控、模式与 SQL 优化列加分 | 支持基础、独立小模块、自测与排错共同验收；此岗位资格不能推为所有在校生的统一条件 |

海康另一个 [软件产品 Java 实习](https://talent.hikvision.com/home/socity/position?postId=3DBC4346A3391150639BCB4895723F64) 的官方搜索正文可读、直接打开偶发超时，页面日期为 2026-06-04，涉及 IO、线程、集合、设计模式、MVC／MyBatis 和测试部署；只作补充，不称近期新发布岗位。

### 7.2 原始目标、官方机制与范围修订

与用户原始清单对照，原九块列法漏写了 Queue／Deque、NIO、ThreadLocal、线程池参数／队列／拒绝策略、JVM 对象创建与基础排查、Interceptor、OAuth2 思想、动态 SQL／N+1、分页／批量／慢 SQL、TCP／DNS、Linux 及用户态／内核态。它们补入已有专题；不增加课程数量，不删除原始文字。尚未采用的机制标为知识准备，MQ 的实际实践与更完整观测／交付目标继续按阶段进入。

官方机制补核支持以下边界：

- [JDK 17 ThreadLocal](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ThreadLocal.html) 与 [ThreadPoolExecutor](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html)：线程本地值、清理、线程复用以及队列／饱和／拒绝属于基础使用问题，不要求读 AQS 源码。
- [Spring MVC DispatcherServlet](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet.html) 与 [Security Servlet 架构](https://docs.spring.io/spring-security/reference/servlet/architecture.html)：容器、过滤器链和前端控制器职责不同；Filter 可提前结束请求，不能假定每个请求都经过 Controller 或全局 MVC 异常处理。
- [RFC 9110 幂等语义](https://www.rfc-editor.org/rfc/rfc9110.html#section-9.2.2)：关注重复请求的预期服务端效果，返回值可以不同；是否可安全重试仍须结合实际接口和业务。
- [Redis 锁模式](https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/) 与 [RabbitMQ 消息确认](https://www.rabbitmq.com/tutorials/tutorial-two-java)：锁所有权与失效、消息确认／重投递和消费幂等需要解释；本项目单 Redis 锁不称 Redlock，了解 MQ 不称已接入。

当前采用版本已由主 POM、starter／BOM 与业务代码重新只读核对，集中见 [技术栈](../architecture/technology-stack.md)，不在调研记录复制版本快照。案例需区分 dev SDK／prod CLI、池化 Web DataSource／目录命令的直接 DataSource、DTO 校验异常／其他请求绑定异常，以及局部幂等／全局幂等键。

结论：保留九块主线、并行算法与操作系统，补齐上述基础；范围、深度及四项验收只在 [教学范围](roadmap.md#java-后端教学范围) 维护。Cookie／Session／JWT 原理归安全，Profile／自动配置归 Spring，JSON 概念归 Java 基础，Jackson 请求转换归 Web；其他专题用实际链路和引用承接，避免重复教学。既有进度继续沿 status，本轮文档审计不产生个人掌握或新业务验收证据。

## 8. 2026-10-12 Java 与 Agent 岗位及教学地图再评估

用户进一步明确要评估教学地图本身。本轮取得 11 个有近 90 天公开发布字段的官方完整职位：Java／后端 6 个、Agent／AI 应用 5 个，覆盖实习、应届、头部与行业企业；不继续用第 7 节的过期或暑期样本替代近期主证据。读取日统一为 2026-10-12（中国标准时间），这是小样本比较，不能计算市场占比或保证投递成功。

### 8.1 完整官方正文、日期与资格

动态官网在普通网页抓取时可能只有页面框架。本轮通过企业招聘页面自身的公开只读接口取得完整职位正文，区分要求、优先项和职责。字节时间字段为 Unix 毫秒，换算成 +08:00；北森日期未附时区，按官网显示记录。以下“发布”是当前公开字段，不证明历史首次发布或从未重新上架。

| Java／后端岗位 | 官方发布字段与资格 | 任职要求及需要保留的区别 |
| --- | --- | --- |
| [字节 Seed 后端实习](https://jobs.bytedance.com/campus/position/7675681181492807941/detail) | 2026-08-19 18:04:08；深圳，本科及以上在读、日常实习 | Go／Python／Java／C/C++ 至少一种，Web 后端、数据库／缓存、网络与分布式基础，代码质量与动手验证；AI／开源为加分。高并发、容灾等是职责，不能倒推为已独立达到同规模架构要求 |
| [字节番茄小说／红果短剧／番茄畅听后端实习](https://jobs.bytedance.com/campus/position/7677434632173832453/detail) | 2026-08-24 11:28:41；北京，本科及以上计算机在读，每周至少 4 天、5 个月 | Java 等至少一种，数据结构／算法／操作系统与业务抽象；系统调优、制定代码／测试规范属于职责。具体到岗要求优先于通用实习介绍的 3 个月 |
| [捷顺 Java 应届](https://jieshun.zhiye.com/campus/jobs?jobAdId=64fd1bd0-5cab-4bcc-9fe0-05e06f25780b) | 2026-08-25 17:56:15；本科及以上、校园全职 | Java 基础、了解 JVM、学习过 Boot／Cloud／MyBatis；MySQL／Redis／RabbitMQ／Kafka 中至少两种；Maven／Git 等。项目与竞赛优先，不要求四种中间件全部熟练 |
| [金证 2027 Java 应届](https://szkingdom1.zhiye.com/campus/jobs?jobAdId=ed0ea4c2-dc9c-4bbe-adc6-08df1122a574) | 2026-08-13 15:53:08；本科及以上、2027 应届 | Spring／Dubbo／Cloud 一种或类似技术，明确要求熟悉 JS／AS／AJAX／HTML5／CSS 等前端技术，前后端联调、DNS／HTTP、沟通与短期出差适应；金融实习优先，职责包含实施部署 |
| [金证 2028 Java 实习](https://szkingdom1.zhiye.com/campus/jobs?jobAdId=afff0ebe-e806-49b8-b2dc-f9487550fb25) | 2026-08-13 15:53:08；本科及以上、2028 届在校生 | 框架、联调与网络要求类似上条，同样要求熟悉 JS／AS／AJAX／HTML5／CSS 等前端技术；金融经验、6 个月及以上实习列优先。毕业届别是独立资格，不能默认当前用户可投 |
| [思必驰 2027 Java 应届](https://aispeech.zhiye.com/campus/jobs?jobAdId=be29e279-3393-416d-9cf5-695feb2e1271) | 2026-08-28 19:23:30；2027 届本科及以上 | 扎实编程、Java／Python／Go／JS 至少一种，数据结构、网络和工程实践；明确要求主动用 AI 工具完成研发闭环、判断产出质量。语音／音视频／SIP 等项目列优先 |

| Agent／AI 应用岗位 | 官方发布字段、资格与性质 | 语言、技术要求与优先条件 |
| --- | --- | --- |
| [字节 Agent 全栈实习：数据平台](https://jobs.bytedance.com/campus/position/7673799813086955829/detail) | 2026-08-14 16:23:25；本科及以上在读，每周 4 天、至少 3 个月；官方分类为前端 | Python／Java／Go／Node.js 至少一种服务端语言，前端、HTTP 与接口闭环；模型 API、Agent 框架、Prompt／评测动手为优先。接受 Java，但不能称纯后端岗位 |
| [思必驰 2027 Agent 开发](https://aispeech.zhiye.com/campus/jobs?jobAdId=55edb0b1-0a32-435f-951c-4bb5bfae8db3) | 2026-08-28 19:29:38；本科及以上应届，应用与平台后端 | Java／Python／Go／TS 至少一种，熟悉一种 Agent 框架、微服务／REST，了解流式与 LLM；ReAct、分布式或 RAG 项目优先。会话、权限、审计、MCP、检索与失败恢复写在职责 |
| [联通数智 AI 平台 J10886](https://cudataintelligence.zhiye.com/campus/jobs?jobAdId=3aded2a7-8e7f-4275-85e4-70d955428afe) | 2026-08-31 20:29:29；硕士及以上应届 | Python／Java／Go 至少一种，Spring Boot 等至少一框架，数据库、REST、Redis／Linux／Git／Docker基础，了解 Vue／React 及 ElementPlus／AntDesign 等前端框架／组件库；SSE／异步经验优先，Agent／RAG 项目加分。语言可用 Java，学历仍有独立门槛 |
| [神州信息 2027 AI Agent J22512](https://dcits.zhiye.com/campus/jobs?jobAdId=2f60efee-ebcb-461c-8a73-8162b24a032c) | 2026-08-20 10:54:17；应届硕士、金融应用开发 | 明确要求熟练 Python、一种 AI 框架、Function Calling／ReAct／RAG／向量库／Prompt；微调或评测经历优先。应用岗也可能有 Python 硬要求 |
| [联通数智智能体应用 J10885](https://cudataintelligence.zhiye.com/campus/jobs?jobAdId=ab177523-718c-46b5-9902-1b119b32f768) | 2026-08-31 20:29:29；硕士及以上应届 | 明确要求 Python、LLM 基本原理、至少一种应用技术及 MCP／工具认知；框架使用与项目为加分，职责含日志、状态、异常和评测 |

公开接口的在线状态为字节 `channel_online_status=1`、北森 `Status=1`，表示本轮数据可查询；未登录或提交简历，未核 HC。北森 `EndTime=2222-02-02` 是占位值，不作为真实截止时间。部分详情接口省略显示字段会返回 `PostDate=0001-01-01`，上述日期取完整列表的 `PostDate`，并核对 `ChangeDate`、GUID、标题和正文。字节 Seed 岗在聚合页仍叫“飞书”，本记录采用当前官方标题；金证另一个标题带 2027 的实习岗实际发布于 2025-08-05，未列入近期主样本。

复查来源：字节 [Seed 完整接口](https://jobs.bytedance.com/api/v1/job/posts/7675681181492807941)、[番茄完整接口](https://jobs.bytedance.com/api/v1/job/posts/7677434632173832453)、[Agent 完整接口](https://jobs.bytedance.com/api/v1/job/posts/7673799813086955829)；北森各官网用公开 `POST /api/Jobad/GetJobAdPageList` 查询列表，按职位 GUID 与官网 `BSGlobal.PortalId` 读取 `GET /api/JobAd/GetJobAdInfo`。对应 [思必驰 Agent 详情](https://aispeech.zhiye.com/api/JobAd/GetJobAdInfo?jobAdId=55edb0b1-0a32-435f-951c-4bb5bfae8db3&category=2&portalId=ba4fe488-9d6c-4a0d-bd81-2d62ec0cff63)、[捷顺详情](https://jieshun.zhiye.com/api/JobAd/GetJobAdInfo?jobAdId=64fd1bd0-5cab-4bcc-9fe0-05e06f25780b&category=2&portalId=8101428c-ac8d-4ba6-bf3c-84c38800de5a)、[金证应届详情](https://szkingdom1.zhiye.com/api/JobAd/GetJobAdInfo?jobAdId=ed0ea4c2-dc9c-4bbe-adc6-08df1122a574&category=2&portalId=7f04bfd9-a96f-4878-a66e-b76fe8a65fce)、[神州详情](https://dcits.zhiye.com/api/JobAd/GetJobAdInfo?jobAdId=2f60efee-ebcb-461c-8a73-8162b24a032c&category=2&portalId=56a70213-bcfb-4cc6-bb56-e0798d036d11)、[联通 AI 平台详情](https://cudataintelligence.zhiye.com/api/JobAd/GetJobAdInfo?jobAdId=3aded2a7-8e7f-4275-85e4-70d955428afe&category=2&portalId=6f158f01-4a80-488b-b0ff-d60fe0085dbc)，均是招聘页面自身的公开读取入口。

补充索引只用于边界比较：小红书 [Agent 引擎实习](https://job.xiaohongshu.com/campus/position/21231) 官方域名索引明确接受 Java，发布日期未公开；[社区 Agent 应届](https://job.xiaohongshu.com/campus/position/22018) 未写语言且混合研究／工程；[REDstar AI Coding](https://job.xiaohongshu.com/campus/position/21828) 属顶尖人才计划。它们没有列入上面的近 90 天完整正文计数，也不能用标题推导全部语言或算法要求。

### 8.2 面试与工程实践交叉核对

本轮补查 [9.10 爱学习 Java 实习自述](https://www.nowcoder.com/feed/main/detail/248055ff78ba49c5ad5dfa1b3ef822dd) 与 [9.24 九方 Agent 面试自述](https://www.nowcoder.com/feed/main/detail/92efed84eb68493d82132e03b7e43ae0)：搜索索引可见 Java 并发／线程池／Boot／MQ，以及检索、MCP 权限与 Java 基础的追问，原页未再次取得可读正文。只作追问线索，日期与经历未独立验证，不冒充官方要求或完整题库；第 5 节已访问的旧原文保留当时边界。

工程机制重新读取 [美团 2026-09-10 Agent 评测全览](https://tech.meituan.com/2026/09/10/Agent-Evaluation-White-Paper-01.html) 与 [Anthropic 2026-01-09 Agent evals 实践](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)。前者为近期企业实践，后者是仍适用的原始工程说明，不称十月新发布。二者支持用任务、轨迹、结果、成本和失败比较判断效果，不能用一次回答成功代替质量验证；它们不是学生必须复制的完整生产平台。

### 8.3 教学地图判断与实际调整

总体判断：九块 Java 与八组 Agent 的覆盖合理，能对应本轮应用和后端样本；缺口主要在优先级、深度与个人产出。没有依据把多 Agent、RL、训练模型、高级 GC、完整云原生平台或多个框架一并提高为当前共同门槛。

落实的调整包括：九块按知识归属而非线性重学；共同底座与目标岗位额外条件分开；专题级四项标准区分核心实践和原理理解；AI 辅助研发加入质量判断闭环；Agent 单元维持模型／工具／会话／检索主线；MQ、Redisson、分布式、CI／观测等原始目标补进入条件和产出；评测和权限从首个可核对例子贯穿。RAG 正式接同一助手、检索／重排比较及 MCP 真实发现调用仍为已确认必做，不被样本里的“加分”降级。

岗位差距须诚实表达：本轮没有统一点名 Spring AI，通用链路能力可迁移，指定另一框架的 API 实践仍须另验；思必驰 Agent 的微服务要求不能由模块化单体冒充；金证两条岗位的前端“熟悉”与联通 AI 平台的前端“了解”分别核对，不把前后端数据流理解当作满足前端开发要求；明确 Python／硕士／研究或顶尖计划的岗位需要独立核对。实际依赖、实现／功能证据、学习安排和本人表现分别由技术栈、specs、roadmap、status 维护。文档同步修正已正式接入框架／资料首段的状态和 V1 非阻塞尾项并行条件，不产生新的业务或个人验收证据。

同日对齐复审再次读取上述 11 个官方岗位的完整正文与发布字段，日期及语言／学历分流与记录一致；补明前端条件并删除番茄摘要中正文未支持的“上线”表述。跨文档修正了入口的并行口径、历史课程选材与当前安排的边界、当前本人任务的唯一落点、周期资料覆盖摘要、DEFAULT／显式 RR 的面试适用范围及协作成果的表达。原始用户清单与 Git 基线逐字一致；本次文档复核和修正未新增业务实现或个人掌握证据。

当前执行正文只见 [岗位分流与优先级](roadmap.md#岗位分流与学习优先级)、[Java 教学范围](roadmap.md#java-后端教学范围)、[V2 学习地图](roadmap.md#v2-功能与学习地图)；本节保存依据和此次调整理由，不再存第二套课表。
