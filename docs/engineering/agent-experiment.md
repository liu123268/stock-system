# Java Agent 最早实验：理解一次工具追问

本入口属于 [AG-CODE-001](../specs/004-research-agent/tasks.md#1-实施顺序)，用 Java 17 和现有 Gson 依赖，不启动 Spring、数据库、前端或行情连接。全部观点与价格是自造样本。替身答得正确不能证明真实模型会正确选工具或理解资料，最新实际证据只见 [任务第 7 节](../specs/004-research-agent/tasks.md#7-真实模型实验与修正验收2026-10-09)；准备证据见第 6 节，财务源预检独立见第 5 节。

## 1. 先理解这一条数据流

### 1.1 先分清模型、Java 和工具

本项目的 Agent 是围绕研究问题，组织模型、资料查询和回答步骤的 Java 应用模块。DeepSeek 是其中生成文字与提出查询的模型，Agent 模块还需要程序、工具、权限、会话与校验；API Key 只让程序能够调用模型，不会自动建立这些业务能力。先理解这些角色，再学习调用消息和代码。

在本项目中，DeepSeek 模型负责读本次请求中的问题与资料、整理文字，并在需要时提出查询。Java 负责接收登录用户的操作、确定允许读取的对象、调用接口、执行查询和核对结果。工具是 Java 向模型提供的一项有边界的查询能力，例如“读取当前研究周期内某成员的观点”，不是模型直接连接数据库。

我们的 HTTP 调用要显式携带本次需要的资料和对话；接好 API Key 不会让模型自动知道数据库、最新行情或此前聊天。查询资料与生成文字是两项不同工作，所以模型提出工具调用后，Java 还要执行并把结果发回，模型才能依据资料回答。模型也可以在已提供足够资料时直接回答，不能为了展示 Agent 强制每题查询。

### 1.2 用现有样本看一遍

下面是 `baseRecords` 中的自造教学资料，不是真实公司或用户记录：

| 来源 | 小陈在该日的观点 |
| --- | --- |
| op1，2026-09-01 | 预计订单增加，盈利有望改善，因此看好。 |
| op2，2026-09-15 | 目前只有订单预期，没有公告确认订单金额，改为中立等待核实。 |

用户问“小陈为什么由看好改为中立？”时，一条正确的工作过程是：先判断回答需要前后两条原文；模型提出读取小陈观点的请求；Java 在绑定的研究范围内返回 op1、op2；模型解释“小陈原先依据订单预期看好，后来因缺少公告确认改为中立等待核实”，附这两条引用。Java 核对来源存在且可访问，人工再核对解释是否确实受原文支持。这里不能推导出“订单已经减少”或“公司利润已经下降”。

如果改问“报告怎么解释营收变化”，资料要从系统维护的公司报告库中检索相关段落，再交给模型，这条取资料的路径属于 RAG。RAG 是本版必做部分，不需要普通用户上传，也不是把报告永久训练进 DeepSeek；它与上面的观点工具一起服务同一个研究助手。原理与框架依据见 [Spring AI 的检索及上下文增强说明](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)；本实验没有报告检索，正式能力沿 AG-CODE-001R／008 验证。

第一步学习目标是能分清：问题由用户提出，需要补查时由模型提出允许的工具请求，资料由 Java 获取，文字由模型整理，权限和合法引用由 Java 校验；引用存在还需判断是否支持结论。首次按钮梳理是 Java 先按固定范围读资料再交给模型的流程；按问题选工具的追问另行验证，两者见 [开发与学习顺序](../project/roadmap.md#做的顺序与学的顺序)。RAG 是取得相关报告段落的路径，也可由程序预先检索，不要求每次由模型自主决定。理解这一层后再读下面的调用消息和循环，不把运行成功视为用户已经掌握。全程学习顺序见 [V2 地图](../project/roadmap.md#v2-功能与学习地图)。

### 1.3 再看调用消息与代码

问“小陈为什么从看好改为中立？”时，初始消息只有作者和记录数量，没有观点正文。模型可以提出 `read_cycle_opinions({"authorId":"M1"})`；[Java 执行工具](../../scripts/agent-lab/ResearchAgentExperiment.java) 校验名称和参数，读取当前固定研究周期内 M1 的有效观点；然后把工具结果及对应调用 ID 发回模型，模型才有资料组织回答。完整观点已在消息里的对照样本允许直接回答。

这两次模型请求分工不同：第一次带工具清单，使用 `tool_choice: auto`，让模型提出接口原生查询；Java 返回成功的工具结果后，第二次不再带工具，设置 JSON 输出格式，让模型整理已取得的资料。两次都记入调用和预算。Java 没有按题目预先代选作者或强制 required 工具，读取范围仍由 Java 控制；正式多个工具的补查过程后续另验。

模型返回的函数名和参数只是执行请求。实际读取发生在 Java，模型不能因此直接操作数据库、改变用户或空间。工具范围在程序里绑定，参数只有可选作者；增加 workspaceId、未知作者或未知工具都会被拒绝。样本不接正式授权 Service，真实身份和权限将在 AG-CODE-003 另外验收。

[调用循环](../../scripts/agent-lab/ResearchAgentExperiment.java) 按设计第 5 节限制请求次数、工具执行、输入输出及总时限；超时、截断、非法引用、已撤回来源、遗漏成员或用旧记录冒充最新观点不能标成完成。JSON 字段和来源通过只说明程序校验通过，语义是否有依据仍需人工核对。

本次实验暴露的两个区别要能讲清：没有加载正文，可能意味着应该调用工具；“我可以查询”这句话不代表接口发出了 `tool_calls`。同样，单条当前看法不能证明前后变化；程序要求变化引用同一作者至少两条不同记录，仍要人工核对两条原文是否支持解释。不能把结构合格当成答案正确。

首次梳理的价格由 Java 添加，保留币种、时间、来源与模拟标记；模型生成概览、变化、成员和待核对事项。模拟报价不可用时仍可整理观点。源码中的 `runSample` 是阅读核心链路的入口；`DeepSeekExperimentClient` 负责 HTTP 与费用记录，`ExperimentChecks` 提供模型替身与边界检查。

### 1.4 Agent 与框架是什么关系

Agent 的能力来自模型、工具、上下文与执行程序的配合，框架帮助组织这些组件。现有实验用 Java HTTP 和普通方法完成工具循环，证明 Spring AI 不是该循环成立的必要条件。正式功能增加后，采用一套框架可减少模型协议、工具注册、会话和检索适配的重复工作；仍需自己实现权限、查询与结果校验。选框架是实现取舍，不是“Java＋Agent”目标自动要求先学完某门框架课。

| 名称 | 主要语言与职责 | 学习关系 |
| --- | --- | --- |
| Spring AI | Java，提供模型、工具、会话、检索和 MCP 接入组件 | 当前 Java 路线的推荐主选，按功能学核心接口；不是模型或业务权限层 |
| Spring AI Alibaba | Java，基于 Spring AI 增加平台适配、Agent Framework 和 Graph 编排 | 按具体场景引入；第一工具不要求先学完工作流和多 Agent |
| LangChain／LangGraph | Python 与 JavaScript／TypeScript；前者提供高层 Agent 入口，后者管理更细的执行状态和流程 | 属于另一条实现路线。Java 系统可以调用 Python Agent 服务，但会新增 Python 编码、跨服务鉴权和部署成本 |
| LangChain4j | 独立 Java LLM 库，提供模型、工具、Memory、RAG 等能力 | 是 Java 候选替代；并非 Python LangChain 的 Java 移植，API 与版本独立 |

框架可以替换，工具由应用执行、资料必须有权读取、聊天保存与模型上下文不同、检索与回答需分别验证等原理仍适用。教学机构只是课程来源：“黑马 Spring AI”和“尚硅谷 Spring AI”并非两种框架；尚硅谷的 Spring AI Alibaba 专题另含扩展层。具体课程与版本差异见 [课程核查](../project/java-agent-market-benchmark.md#6-课程核查与选学建议2026-10-09)。

定义依据：[Spring AI](https://docs.spring.io/spring-ai/reference/api/)、[Alibaba 架构](https://java2ai.com/docs/overview/)、[LangChain](https://docs.langchain.com/oss/python/langchain/overview)、[LangGraph](https://docs.langchain.com/oss/python/langgraph/overview)、[LangChain4j](https://docs.langchain4j.dev/intro/)。正式接入成本仍需验证，不能凭框架名称保证现有 Boot 依赖兼容。

### 1.5 看到 RAG 后的复习提纲：理解什么、怎样用代码

Java 的 `if`、`for`、`return` 是语言关键字；`ChatClient` 是框架类型，`prompt()`、`user()`、`call()`、`content()` 是库方法。前者的局部编码能力沿 Java 专题训练，后者先认识用途、读懂参数和返回值，再借助已核对示例与 IDE 使用；记不住拼写不等于不会，但只会说概念也不等于能应用。复习检查聚焦根据需求选择步骤、解释输入输出和核对变化结果；是否安排代码动作见 [教学方法](../project/teaching.md#学习目标与参与方式)。

| 课程主题 | 核心职责与本项目场景 |
| --- | --- |
| 模型调用／ChatClient | Java 把问题与资料发给配置好的模型并取得回答；连接成功不表示模型已经读到业务数据库 |
| Prompt | 组织问题、资料与回答要求；要求“根据资料”不能替代实际取资料，也不能代替权限控制 |
| Tool Calling | 模型提出允许的工具请求，Java 校验、查询并回传；例如补查选定周期内成员观点 |
| Chat Memory | 为本次请求提供相关历史上下文；完整聊天记录与模型上下文分别维护，会话身份由应用隔离 |
| 结构化输出 | 让结果便于转为 Java 对象及供页面使用；字段可解析不证明观点和引用正确 |
| RAG | 从系统报告库检索相关段落，再将段落作为上下文用于回答；不是把报告训练进模型。检索可固定执行，也可作为工具 |
| Advisor | 在调用周围组织可复用处理，例如补上下文或记录；先理解用途，不要求初学时读源码 |
| 流式返回 | 逐步取得输出并显示，改变交互方式；不会自动提升回答的事实准确性 |

下面只是调用示意，假设 `chatClient` 已配置、`question` 和 `material` 已由 Java 提供；不是主项目已经接入的实现或完整可运行程序：

```java
String answer = chatClient.prompt()
        .system("根据提供的资料回答；资料不足时说明。")
        .user("问题：" + question + "\n资料：" + material)
        .call()
        .content();
```

`prompt()` 组织本次请求，`system()` 放回答要求，`user()` 放问题与资料，`call().content()` 沿同步路径取得文字结果。`chatClient` 由已配置模型的 Spring AI Builder 创建，`question` 来自当前提问，`material` 来自授权内资料 Service；不会自动读 MySQL。业务要先确定有权资料、再调用、最后检查结果；这段示意不代表正式生成接口已接入，也不会自行建立 RAG。正式输入复用 [周期资料 Service](../../src/main/java/com/stock/system/stock/service/StockResearchSourceService.java)，完整／超限与撤回边界沿规格验收；SQL 接线属于工程实现，不作为当前 Agent 教学主课。

复习用“要解决的问题 → 输入资料从哪来 → 谁执行哪一步 → 输出怎样核对”重建链路，再分析资料或要求变化后的行为；当前观看进度不作为个人掌握证据，参与方式见 [教学方法](../project/teaching.md)，个人交接见 [状态](../project/status.md#求职专题学习交接)。依据：[ChatClient](https://docs.spring.io/spring-ai/reference/api/chatclient.html)、[Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)、[Advisor](https://docs.spring.io/spring-ai/reference/api/advisors.html)、[RAG](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)。

## 2. 在电脑上运行离线实验

在项目根目录 `D:\Java\stockweb\stock-system` 的 PowerShell 执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-agent-experiment.ps1 -Mode Offline
```

脚本使用 JAVA_HOME 中已安装的 JDK 编译三份独立源码，Gson 从已有本地 Maven 仓库读取，不安装新依赖；类文件写入忽略的 `target/agent-experiment/classes`。`Offline` 使用事先写好的模型替身响应验证 Java 流程，不读取 API Key，不发网络请求；`Bypass` 仅对子进程生效，不修改系统执行策略。

成功时看到明确标注的模拟报价、模拟梳理与工具追问过程，末行是 `AGENT_LAB_OFFLINE_OK`。检查数来自当前程序实际执行；替身答得正确不代表真实模型质量通过。临时检查账本在 finally 删除，正式实验账本不受离线检查影响。

## 3. 配置就绪后验证真实模型

用户已确认 DeepSeek 官方平台。此入口只接受 `https://api.deepseek.com` 和 `deepseek-flash`；中转平台的地址、模型、费率和余额协议不同，不能套用同一配置。当前模型、模式、预算及价格依据只见 [技术规划第 3.5 节](../architecture/technology-stack.md#35-首轮模型与费用安排)。

本地配置是项目根目录的 `.env.deepseek-local.properties`，在 IDE 中仅填入 `deepseek.api-key`；已经配置时直接沿用，不重新复制覆盖。其他机器复现时，把 [无密钥模板](../../scripts/agent-lab/deepseek-local.properties.example) 复制到项目根目录并改成这个文件名。根目录 `.env.*` 由 Git 忽略；真实 Key 不填回模板、不发聊天、不放命令参数。没有该配置或没有有效 Key 时程序拒绝真实调用。此步骤只是配置本项目，与 WorkBuddy 中已填密钥的设置互不自动同步。

确认模型与费率仍符合技术规划后，在同一目录执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-agent-experiment.ps1 -Mode Live
```

`Live` 先读取官方余额接口，仅核对 CNY 可用额度，不输出原始余额；然后运行 15 个自造样本，包含 EV-001、EV-003、EV-004 各三次及空／单条、已有上下文、注入文字、报价不可用和缺财报对照。最多每样本三次请求，无自动付费重试。Key 尚未配置时不用执行本命令。

[费用实现](../../scripts/agent-lab/DeepSeekExperimentClient.java) 按当次核对的较高单价计入费用上界，缓存优惠不提前假定；每个请求前保守预留 4.016 元，依据是高于官网 1M 上下文的 200 万输入 Token 上界及受控的 2,000 输出 Token，不把字节或汉字当准确 Token。预留不是实际花费，取得合法用量后按较高费率结算。账户余额不足以覆盖该保守预留会停止，不自动充值或切模型。

`.local/agent-experiment/budget.jsonl` 独立记录累计费用上界和未确定用量的占用，文件锁防止实验并发；超时、网络异常、缺用量等不释放不明占用，重启不会重置。达到已确认预算停止；不要删除账本来绕过预算。该保护依赖已核对的模型及费率，只约束本入口，不能控制其他程序对同一账号的花费，也不代替服务商账单。

通过结构校验的结果与必要用量保存在 Git 忽略的 `.local/agent-experiment/live-*.jsonl`、`usage-*.jsonl`，包含模型、提示词版本、工具轨迹、耗时与自造样本答案，不保存 Key、原始请求头、思考正文或供应商错误正文。用样本原文人工检查判断变化、担忧与事实、公告是否确实存在、引用是否支持句子；`STRUCTURE_VALID_REQUIRES_MANUAL_REVIEW` 不代表样本质量验收通过。失败停止后先检查原因和已记录用量，不反复启动付费试错。

协议依据：[DeepSeek 工具调用](https://api-docs.deepseek.com/guides/tool_calls/)、[Chat Completion](https://api-docs.deepseek.com/api/create-chat-completion/) 和 [余额接口](https://api-docs.deepseek.com/api/get-user-balance/)。非思考模式显式关闭 thinking；普通参数及最终回答由 Java 校验，不依赖 Beta strict。真实运行的通过范围与局限只见 SPEC-004 tasks，不由本操作说明推断正式业务已通过。


## 4. 后端源码兼容预检

[源码副本脚本](../../scripts/check-agent-backend-compatibility.ps1) 的 `Baseline` 模式只替换副本依赖并编译，`Adapted` 模式再适配包路径与 Jackson API，`Verify` 模式复制测试并运行受保护的测试库回归。每次建立新目录，不复制本地密钥、不修改正式源码；脚本只用自己的 [Maven 设置](../../scripts/agent-compatibility-maven-settings.xml)，不改全局镜像。`Verify` 会启动测试 Spring 上下文，并按测试规则关闭资源，不能把它描述为纯编译。

2026-10-12 已把验证过的兼容修改落到正式项目，版本与采用状态只见 [技术栈](../architecture/technology-stack.md#1-后端与数据)，实际验证只见 [任务第 9 节](../specs/004-research-agent/tasks.md#9-ag-code-002正式后端框架接入2026-10-12)。业务 JSON 由 Jackson 3 处理，安全／Flyway 和 MockMvc 测试导包跟随 Boot 4；Mockito 替身改用 Spring 的 MockitoBean／MockitoSpyBean。旧 `findAndRegisterModules` 改为 JsonMapper builder 的模块发现；Mapper 新重载用明确参数类型避免测试编译歧义，原业务断言保留。

默认 `spring.ai.model.chat=none`，所以原系统启动不依赖模型密钥。第 6 节的固定梳理已接个人 HTTP 和页面，第 7 节接受限工具追问；启用客户端仍不会自动读取 MySQL，资料与权限由业务 Service 提供。[ResearchAiConfiguration](../../src/main/java/com/stock/system/agent/ResearchAiConfiguration.java) 在启用 DeepSeek 时提供零重试的 RetryTemplate，避免框架在失败后隐式补发。首次请求次数与重试次数的区别、实测故障及修复只见 [BUG-011](bugs/BUG-011-spring-ai-retry-count.md)。

框架回归位于正式 `src/test` 的 [SpringAiCompatibilityTest](../../src/test/java/com/stock/system/agent/SpringAiCompatibilityTest.java)，回环替身只检查 HTTP 适配、工具配对、结构转换和实际请求次数；手造向量只检查检索组件过滤／删除，不证明中文报告质量。这些是项目测试，不恢复独立教学工程。学习继续沿正式研究梳理的数据路径，版本迁移由 AI 处理，不要求学生先记完库方法。

## 5. 正式业务学习：按周期读取资料

当前源码入口为 [StockResearchSourceService.readCycle](../../src/main/java/com/stock/system/stock/service/StockResearchSourceService.java)，由 [WorkspaceStockController](../../src/main/java/com/stock/system/stock/controller/WorkspaceStockController.java) 调用，返回 [ResearchCycleSourcesResponseVO](../../src/main/java/com/stock/system/stock/vo/ResearchCycleSourcesResponseVO.java)。这个方法是项目自己定义的业务方法，不是 Spring AI API：输入当前用户、空间、空间股票与周期，输出实际读取范围和有效观点原文；暂不调用模型。

`userId` 从认证对象取，不能由请求者填写；`workspaceStockId` 是空间股票关系记录的 ID，不是股票代码或全局 stock.id。WorkspaceService.detail 先检查有效账号和成员关系，解决谁能读该空间；ResearchCycleMapper.selectInWorkspaceStock 再用空间／股票／周期三个条件检查资料归属，解决能读哪个对象。只有成员检查仍可能让用户拿另一个空间的周期 ID 查资料，所以两者必须同时成立。业务异常会中止后续查询。

现有 timeline 每批展示 20 条并允许跨周期，服务于页面浏览；新 readCycle 直接按选定周期统计和查询，服务于资料准备。范围内返回完整正文，超限返回 LIMIT_EXCEEDED 和空正文，不能把页面加载的一批称作整个周期。`readAt`、有效条数、实际读取条数和字符数由 Java／数据库确定；字符限制不等于精确 Token 数。查看原文使用 readOpinion 的新请求，重新校验访问和撤回状态。

StockResearchSourceIntegrationTest 在真实测试库用固定资料检查范围和越权；`@Test` 标识一项检查，MockMvc 执行真实 Controller／认证／Service 链路而不额外启动 Web 端口，事务 fixtures 回滚；跨连接与无外层事务的上下文案例按本轮 ID 精确清理。它证明程序边界，不证明模型能正确理解资料，也不证明学生已经会独立写代码。子范围与并发现已验收：日期／成员条件保存在 Scope.filter，统计和正文仍用同一 RR 快照，交付校验另起新事务。方式见 [教学方法](../project/teaching.md)，个人结果只见 [学习交接](../project/status.md#求职专题学习交接)，最新工程证据见 [任务第 12 节](../specs/004-research-agent/tasks.md#12-ag-code-003范围行情与上下文接入2026-10-13)。

## 6. 正式业务学习：从资料到研究梳理结果

生成核心看 [ResearchSummaryService](../../src/main/java/com/stock/system/agent/service/ResearchSummaryService.java)，实际模型调用看 [ResearchSummaryModel.generate](../../src/main/java/com/stock/system/agent/service/ResearchSummaryModel.java)。原来资料 Service 返回有效观点即结束；现在核心组织消息、调用模型、校验结果，再用 revalidate 核对当前访问和原来源。004 已把它接到个人 HTTP 流程与页面，默认真实模型仍关闭。

[ResearchSummaryPrompt](../../src/main/java/com/stock/system/agent/service/ResearchSummaryPrompt.java) 将回答要求放入 system，将本次股票／周期、来源编号、成员显示信息及有效观点放入 user；作者别名 M1／M2 在 Java 内映射，模型不取得账号 ID 或权限参数。只有实际读出的白名单资料进入消息，ChatClient 不自动查数据库。ANSWER_RULE 是本轮本人提出的表达要求经 AI 反馈后的代码落点，提示词版本便于追踪后续比较。

`call().chatResponse()` 同步返回模型文字、结束原因及用量；之后解析为 [ResearchSummary](../../src/main/java/com/stock/system/agent/dto/ResearchSummary.java)。保留元信息是为了识别截断并承接后续用量核对，文字可以解析为对象仍需业务校验。本单元先理解这条主链路，结构化结果各字段和独立修改在后续单元展开。

[ResearchSummaryValidator](../../src/main/java/com/stock/system/agent/service/ResearchSummaryValidator.java) 能拒绝不存在的来源、用别人的记录描述成员、缺少最新观点或用单条记录冒充前后变化。它不能只靠 ID 证明句子的事实意义；即使引用真实，“认为订单可能增长”被写成“订单已经增长”仍然是质量问题，需要对照原文、提示约束与固定问题集核对。时间按任务使用：普通概述可以省日期，比较变化必须保留先后关系，底层输入仍保留真实日期。

生成期间范围内新增观点可返回带读取时间的旧快照并标记有新资料；撤回或权限失效必须阻止交付依赖它的答案，不能只禁用原文按钮。数据库读取与模型／行情网络调用分开，网络耗时期间不占用业务事务。[行情适配](../../src/main/java/com/stock/system/agent/service/ResearchMarketDataService.java) 复用既有可信股票身份与供应商，价格和日 K 名义价差由 Java 绑定或计算，不由模型猜测；失败说明缺口，最新价不充当历史周期结束价。实际验证见任务第 11～13 节；受控 HTTP／行情只证明程序链路，不证明真实模型质量，本人结果由 status 单独记录。

### 从生成核心到个人页面

页面按钮经 [ResearchAgentController](../../src/main/java/com/stock/system/agent/controller/ResearchAgentController.java) 进入 [ResearchAgentService](../../src/main/java/com/stock/system/agent/service/ResearchAgentService.java)：当前账号与周期授权 → 读取有界资料 → requestId 去重与费用预留 → 调用上述生成核心 → 重查依赖并保存 → 页面展示。刷新只读取已保存结果，不重新调用模型。requestId 表示同一次动作，断线后查原运行状态；用户主动重新梳理才用新标识。

[ResearchAgentRecordMapper](../../src/main/java/com/stock/system/agent/mapper/ResearchAgentRecordMapper.java) 负责短事务。等待模型时不持数据库锁，完成时只保存引用与当前版本仍有效的结果。清空增加版本，旧版本结果不能重新写回；个人正文可以删，已发生或未知的调用费用独立保留。对应表职责只见数据模型 3.13。页面组件通过具体 ID 与账号版本识别真正切换，不能仅因父页面重新创建了内容相同的对象就重置输入。

## 7. 正式业务学习：一次工具追问怎样完成

原来的“研究梳理”由 Java 先固定读观点和报价，再让模型整理。本次新增“继续提问”：模型根据问题提出允许的查询，Java 校验并执行，再把实际结果送回模型。工具调用多了一次取得资料的往返；模型不直接访问数据库，也不能自行选另一个用户、空间或股票。

把已有 Service 接成工具需要同时维护声明和执行两侧：definitions 中的名称用于识别，说明用于帮助模型选择，参数契约约定可填字段、类型及必填项；ResearchQuestionModel 把这些 ToolCallback 与消息一起交给模型。模型返回名称和参数后，execute 以白名单／分支分派到真实业务方法，并继续执行 Java 参数和权限检查。财务工具只让模型选择 series／period／metrics，用户、空间与股票由 context.target 绑定；参数契约不是权限证明，Service 仍重验访问。结果组成工具响应，加入下一次请求。新增工具应核对说明、受限参数、Service 分派和结果处理是否都已接上，不能只在 Prompt 中提到一个方法名。

先沿“最新可用价是多少”看完整链路：页面把问题、当前周期和 requestId 交给 [ResearchAgentService.ask](../../src/main/java/com/stock/system/agent/service/ResearchAgentService.java)；Service 验证访问、读取范围、筛选有效历史、预留费用后，进入 [ResearchQuestionService.answer](../../src/main/java/com/stock/system/agent/service/ResearchQuestionService.java)。第一次 [ResearchQuestionModel.call](../../src/main/java/com/stock/system/agent/service/ResearchQuestionModel.java) 可以返回 `read_latest_quote` 请求，Java 的 `execute` 选择报价 Service，取得价格、币种、时间、来源和模拟标记，再以相同 call ID 组成 ToolResponseMessage。第二次模型请求读到这一结果，写出回答及引用；Java 校验引用、重查权限和来源后保存，页面显示并允许打开依据。这就是本轮真实样本的两次模型请求、一次工具执行。

`read_latest_quote` 是项目定义的工具名，`answer`／`execute` 是项目方法；AssistantMessage／ToolResponseMessage 是 Spring AI 的消息类型，`for`／`switch` 是 Java 语法。无需先背下这些拼写；应能读出“模型提出查什么 → Java 决定能否执行 → 实际数据回传 → 模型回答 → Java 核对保存”。Spring AI 2 的 ChatModel 工具调用由调用方管理执行，我们用显式循环保留额度、权限和用量控制，依据见 [官方工具调用说明](https://docs.spring.io/spring-ai/reference/api/tools/chatmodel-tool-calling.html)。

Service 的返回值先在 Java 内存里；模型提出的工具请求和执行结果分别追加到 `messages` 后，下一次 `model.call` 才会收到这些资料。call ID 用于配对一次工具请求和它的结果，`sourceId` 用于最终回答引用已登记的依据；两者职责不同，引用不能直接拿工具调用编号替代。财务查询的用户／空间／股票由 `context.target` 绑定，模型参数只选择期间和科目；越界或未知字段在执行 Service 前拒绝。

最终回答沿 `parseReply` 分两步处理：`strict.readValue(text, ResearchReply.class)` 把 JSON 字符串转换成含 paragraphs／missingInformation 的对象，再检查段落数量、文字和引用等业务约束；能解析不代表对象符合项目要求。财务查询先把已核对的快照以 `sourceId → Evidence` 保存到 `context.evidence`，并把同一编号和数据交给模型；最终回答引用编号，Java 检查其是否已登记，返回时同时带回答与依据列表，页面按编号匹配并展示依据。编号存在只证明能对应到已登记资料，不能证明资料支持该段结论。离线观察入口为 ResearchFinancialQuestionTest 的 `sendsVerifiedFinancialSnapshotAndKeepsSourcePagesAndJavaChanges`，在 parseReply 的 readValue 行断点并步过，可对照字符串与对象字段；该测试不调用真实模型。

资料够用时模型可以直接回答。例如已有有效研究梳理，追问“订单已经增长了吗”，可以用原观点说明只是猜测，当前缺财报或权威披露；没有权威资料工具就不能补成已确认。价格问题则要求本次查报价，历史聊天里的数字只是旧快照。日 K 的实际区间和名义变化由 Java 计算，模型负责解释其口径；不能把未复权价差说成投资回报。

排查承接原来的 sources → input → generated，再补工具轨迹：没有查到需要的资料，看模型是否提出正确工具；提出了却失败，看 Java 是否拒绝参数、范围或资料上限；工具确实返回了，回答仍错误，比较 ToolResponse 与最终段落。接财务后每问最多四次模型请求、三次工具尝试，错误也计次数；费用统计全部实际派发，有一次用量未知就保留预留。超时、清空或权限变化时不交付迟到答案。精确额度、数据依赖和接口契约只见 [设计](../specs/004-research-agent/design.md#4-首批工具适配)，不要求学习时记住保护数字。

四个自造问题的真实模型核对见 [005 证据](../specs/004-research-agent/tasks.md#14-ag-code-005工具追问与真实样本核对2026-10-14)。它证明当次资料和工具链路可用；源码由 AI 实现，用户的独立理解、审查和迁移证据继续单独记录，不能由测试通过代填。

读测试时核对断言检查的对象：[财务工具测试](../../src/test/java/com/stock/system/agent/ResearchFinancialQuestionTest.java) 人为指定模型的工具请求和最终回复，用于检查 Java 往返、范围和依据；真实检查脚本中的 SUCCEEDED／answer 存在断言，只能证明相应程序条件，正文数值、单位、来源及是否回答本次问题仍需与快照对照。历史报价记录中无关的日 K 缺口已转为一次实际 Prompt 修正，证据见 [缺口聚焦](../specs/004-research-agent/tasks.md#20-报价回答的资料缺口聚焦2026-10-15)；输入规则已修改与真实输出已改善分别验收。

报告六题的真实比较又发现，正确财务快照仍可能被模型在正文换算错。v7 在解析新回答时核对带财务引用段落的数字与原单位组合，拒绝模型自行换算、改数或未取得的百分比；源码入口为 ResearchFinancialAnswerNumbers。它检查“该数值／单位是否在引用材料内”，不证明科目归属、增减方向和整段语义，旧记录也不据此自动改写。真实错误、作用范围和复验见 [BUG-012](bugs/BUG-012-financial-answer-unit-conversion.md)；不能把新增单元测试当作真实模型复测。

## 8. 正式业务学习：断线后查原任务

网络失败只说明浏览器没有收到回包，后端可能仍在执行，也可能已经完成并记账。直接用新编号再发，不能利用原请求去重。所以页面先保留 requestId；有 runId 就查询该运行，没有 runId 就按原 requestId 查找，恢复成功结果或准确失败原因，均不重新调用模型。暂未找到时保留编号，用户再次提交同一动作仍沿用它；确认终态后的新动作才换编号。

入口是 [ResearchAgentService.requestStatus](../../src/main/java/com/stock/system/agent/service/ResearchAgentService.java)：当前授权 → 当前账号的 requestId 查运行 → 再用完整研究对象查询 → 重验来源 → 返回状态／有效答案。客户端 [待确认标记](../../frontend/src/utils/agent-pending.ts) 只存当前标签页的编号、账号和对象，不存问题或凭据；它用于找任务，不能作为权限证明。历史正文仍由后端授权接口返回。

来源变化的检查发生在模型往返之间和交付前。撤回使依赖回答失效；权限失效不再返回正文；清空增加版本，旧任务不能重新写回。它们停止下一步工作，已经发生的模型调用仍计费。临时断网、任务失败和资料失效分别判断，不能统一当作“重新发一次就好”。程序证据见 [006 任务](../specs/004-research-agent/tasks.md#15-ag-code-006记录变化与断线恢复2026-10-14)，这条链路与 HTTP、幂等、状态保存共同服务于可靠恢复。

### 已完成的问答怎样入库

当前 [保存实现](../../src/main/java/com/stock/system/agent/mapper/ResearchAgentRecordMapper.java) 使用 JdbcTemplate 执行 SQL。用户发送问题后先建立 RUNNING 运行；模型和工具在数据库事务外执行，交付前再核对权限、来源及运行版本。最后在一个短事务中更新运行状态，并且只在成功时插入消息：

| 表 | 职责 |
| --- | --- |
| `agent_session` | 用用户、空间、共享股票与周期的唯一组合标识一份个人对话 |
| `agent_message` | `result_json` 保存结构化结果；追问的问题、回答段落及引用依据位于其中的 followUp。`dependencies_json` 保存用于重验有效性的资料依赖 |
| `agent_run` | 保存请求编号／摘要、状态、版本、调用次数与用量，支持幂等和中断恢复；失败不插入成功消息 |
| `agent_budget` | 独立记录已发生或尚未确定的费用占用，不能随清空消息重置 |

结构以 [V13](../../src/main/resources/db/migration/V13__create_research_agent_records.sql)／[V14](../../src/main/resources/db/migration/V14__track_agent_model_and_tool_calls.sql) 为准。页面恢复时读取本人当前对象的最近一批消息，再核对权限和来源；查看更早记录继续分页，不因为打开页面重新生成答案。清空会删除当前对象的 message 正文与依赖、增加会话版本，保留必要运行／费用记录，阻止迟到结果重新出现。

未发送问题由 [草稿 Store](../../frontend/src/stores/agent-draft.ts) 暂存本次登录的内存，往返页面能恢复，刷新或退出后不保留；失败时输入仍留在当前页面，不把它当作已完成问答入库。数据库保存、前端草稿和每轮传给模型的有界历史各有职责，具体上限与权限机制只见 [记录设计](../specs/004-research-agent/design.md#61-保存与撤回处理)。

## 9. 财务读取准备：数字必须带口径

成员说“订单可能增长”仍是观点；取得财报营收数字后，也只能核对对应营收，不能直接证明订单或其增长原因。财务判断需要金额、报告期间、币种、单位和指标定义共同成立。相同名字的“净利润”可能代表归母、合并或调整后口径；同为港币的两个展示金额，也可能经过未说明的汇率换算。

当前链路是模型提出 `read_financial_facts` → [StockFinancialService.read](../../src/main/java/com/stock/system/stock/service/StockFinancialService.java) 核对当前访问及可信股票 → 优先读取系统已核实的年度报告库 → 按必要科目／期间生成 DTO 和 Java 比较 → 再核对权限 → 工具结果回到模型并生成财务引用。未核实的 SDK／CLI 数字仍由 [解析器](../../src/main/java/com/stock/system/stock/financial/LongbridgeFinancialParser.java) 明确缺口，不把金额发给模型猜单位。指定期间没有时说明缺失，不偷偷改答其他年度，也不把数额写成 0。网页显示引用生成时的数字、单位和原报页码；完整覆盖和验证边界只见 [原报与财务工具证据](../specs/004-research-agent/tasks.md#17-官方原报限定覆盖与财务工具2026-10-14)。

关键代码是 [FinancialFacts.compare](../../src/main/java/com/stock/system/stock/financial/FinancialFacts.java)：先判断能不能比较，再用 BigDecimal 计算。例如两笔自造营收都是原报人民币元、同为全年，基期 100.25、本期 120.30，Java 可算出增加 20.05、同比 20%；任一币种转换或单位未知，就不给这个增长率。基期为零／负数时，即便口径正确，也只给绝对变化并说明原因。数字计算由 Java 做，模型负责解释，不能靠提示词猜单位；金额与百分比发送十进制字符串，浏览器直接展示，不再次用浮点数计算。

Longbridge 端点仍只有展示币种和数值，不能完整说明金额单位及利润／原报口径，因此该路径保持 PARTIAL，变化值为空；新原报目录只为已核实样本补齐依据，不把两个公司泛化到所有股票或季度。报告期结束日 `fp_end` 也不是公告日。排查继续沿 sources → input → generated：sources 缺口径时先修数据适配或来源，不把它当成模型漏读。恢复财务引用还要核对当前报告版本及数字；旧来源失效时整条回答失效。无需先背 DTO 拼写，应能判断“这些数字是否回答所问指标，是否具有相同口径”。

报告准备在项目根目录进行：用隔离 Python 环境安装 [解析依赖](../../scripts/requirements-report-source.txt)，按 [清单](../../scripts/report-sources/official-financial-reports.json) 的官方链接取得对应文件并放入 `.local/report-source/pdfs/`。执行 `python scripts/import-official-financial-reports.py` 核对并生成金额目录，`--check` 只比较、不改文件；成功输出两份报告／20 个数值，哈希、单位或行布局变化则失败，需先审阅新来源。原 PDF 和提取文本由 Git 忽略，不把整份报告送给模型；这是受审样本导入，RAG 的分块、向量化与检索另验。

## 10. 财务真实模型检查怎样复现

[固定六题](../../scripts/agent-quality/financial-cases.mjs) 分别检查 A 股年度金额与同比、港股原报币种／单位、缺可比期、未覆盖季度、营收与订单猜测、报价缺口的相关性。期望数字从已审年度报告目录读取，保留金额字符串和原报定位；[程序检查](../../scripts/agent-quality/financial-cases.test.mjs) 验证工具快照与请求上限，不能自动判断自然语言是否支持结论。每次结果仍须逐题审阅，不能把 HTTP 成功或程序检查通过当成模型质量通过。

在项目根目录先打印计划：

```powershell
node frontend/e2e/research-agent-financial-probe.mjs --dry-run
```

它不读取 Key、不连接数据库、不启动服务。检查题目与发送范围后，可在已准备 JDK 17、Node、MySQL 客户端、打包 JAR 及 DB_PASSWORD／JWT_SECRET 环境变量的 PowerShell 7 中执行本地替身：

```powershell
./scripts/run-agent-financial-probe.ps1 -Mode Fixture
```

需要只检查题库中的部分问题时，在项目根目录用 `node frontend/e2e/research-agent-financial-probe.mjs --dry-run --cases=moutai-annual,tencent-original-currency` 先确认计划；随后给启动脚本增加 `-CaseIds @('moutai-annual','tencent-original-currency')`。未指定时仍是原六题；显式空选择、未知或重复编号直接拒绝，不回退执行整套。计划、执行、股票 fixture 与续做均沿同一选择范围；续做时须传相同 CaseIds，禁止混用其他题集结果。定向真实复测仍需对应的新增调用授权，选题参数不代替授权。

[启动脚本](../../scripts/run-agent-financial-probe.ps1) 建立独立 test 后端与前端代理，Fixture 只用回环模型和模拟行情，不触碰真实实验账本。执行前检查测试端口空闲，写入前检查前后端探针及实际 SQL catalog；每题保存、恢复和相同 requestId 去重后，核对另一成员看不到本人记录。finally 只清理本轮随机账号及关联数据，并关闭自己记录的 PID／启动时间对应服务。成功输出 FINANCIAL_PROBE_OK 和 FINANCIAL_PROBE_SERVICES_CLOSED，结果位于忽略的 target/agent-financial-fixture-*。

真实模式另需本轮数据范围及费用授权，命令中的开关只表达执行意图，不能代替授权：

```powershell
./scripts/run-agent-financial-probe.ps1 -Mode Real -ApprovedFinancialData
```

Real 从本地忽略文件读取既有 Key，仅经子进程环境传递，固定 DeepSeek 官方接口，沿用第 3 节原累计 10 元实验 JSONL；不创建新账本、不动正式页面的独立预算，不自动付费重试。单题最多四次模型请求、三次工具尝试。results.json 保存期望、回答、工具轨迹、提示版本与用量，verification.json 区分程序检查和待人工语义审查；完整结果与坏例子归档于 [当前任务证据](../specs/004-research-agent/tasks.md#21-财务六题真实检查与问答存储说明2026-10-17)。

中途失败须先核对保存结果和已发生费用。只有确认是检查器误判、已执行答案能够离线重新通过时，才使用 -ContinueFrom 指向该轮 results.json，继续尚未发问的题。该入口核对相同题集、模型／价格条件、真实／替身模式并拒绝重复项；已执行问题复用保存输出，不发新请求。真正的失败答案不能借续做重新付费。修正提示词后的真实复测须单独安排，不能把旧输出重新标为新版本的效果。

已派发但没有取得用量的请求，即使最终没有回答，也不能当成零费用或随 fixture 清理释放原账本占用。先保留预留并查明用量，再决定后续调用；一次定向复测中部分成功、部分无回答，应逐题记录，不能把一次成功扩大为整套质量通过。实际例子见 [两题 v4 复测](../specs/004-research-agent/tasks.md#23-定向财务复测与-v4-部分结果2026-10-18)。

## 11. 报告正文检索的本地预检

财务工具回答已核实科目的数字，正文检索先寻找能够支持业务说明的段落。[预检程序](../../scripts/rag-preflight/preflight.py) 按受审边界解析中文 PDF，分成带版本／页码／页内位置的短片段，用本机中文模型将问题和片段转成同维向量，再在 Qdrant 本地索引中寻找候选。相似度用于排序，不能证明段落支持结论；输出保留候选和关键词基线，回答模型尚未接入。本轮是条件预检，正式 Java 权限、工具和页面继续沿 008 实施，不增加 Python 业务服务。

在项目根目录，先用 --stage plan 打印来源／模型计划，不读取 PDF 或模型权重，不连接网络／数据库。当前机器已经准备隔离环境及权重；新机器先将 [来源清单](../../scripts/rag-preflight/sources.json) 的两份官方 PDF 放入 .local/report-source/pdfs，文件名、哈希和页数必须匹配，再执行：

```powershell
python -m venv .local/rag-preflight/venv
./.local/rag-preflight/venv/Scripts/python.exe -m pip install -r scripts/rag-preflight/requirements.txt
./.local/rag-preflight/venv/Scripts/python.exe -X utf8 scripts/rag-preflight/download_model.py
./.local/rag-preflight/venv/Scripts/python.exe -X utf8 scripts/rag-preflight/preflight.py --stage retrieve
```

前两步创建项目内环境并安装固定依赖；第三步只下载固定提交的约 95 MB 公共模型文件，核对尺寸及哈希，不发送资料或凭据。最后一步离线运行真实 CPU 向量化、存储和检索，没有模型 API 费用，也不连接业务 MySQL。只想检查解析时用 --stage parse，不加载向量模型。环境、权重与结果均由 Git 忽略，脚本和受审清单保留在版本库。

成功后打印 target/rag-preflight-* 目录。先读其中 review.md，看问题找到哪份报告的哪一页，再用 retrieval.json 查看排名／分数，用 passages.json 核对正文及位置。verification.json 保存版本和清理结果；模型缓存保留，临时向量数据先按本轮 ID 删除并核对 0，再关闭库并删除唯一临时目录。原 PDF 不改写。自动边界检查执行：

```powershell
./.local/rag-preflight/venv/Scripts/python.exe -X utf8 -m unittest discover -s scripts/rag-preflight -p test_preflight.py -v
```

问题集分校准与留出改写两组。门槛只用前一组确定，后者运行后不据其结果继续调参；参数变更保留前后结果。当前 0.45 只适用于本轮小语料候选，不作为通用答案可信度或全市场默认值。检索失败先检查原文是否可读、边界是否完整、输入是否被截断及元数据过滤，再检查排名；无相关片段时反馈缺口，不用模型记忆补报告。已验证范围、实际成绩和未完成事项只见 [001R 任务证据](../specs/004-research-agent/tasks.md#22-中文报告解析与本地检索预检2026-10-17)。

## 12. 报告检索如何进入正式助手

以前报告文字只在 Python 预检中参与查询。现在 [导出脚本](../../scripts/rag-preflight/export_catalog.py) 从同一已审 PDF 与边界生成版本化正文目录，Java 加载并验证文字哈希和位置；提问时经 ResearchQuestionService → ResearchReportService → ReportRetriever，在相同股票／年度内合并本地 BM25 与 EmbeddingModel／Qdrant 候选，取得最多三段，才进入回答模型。章节标题帮助精确词匹配，向量补足改写查询；两者的分数尺度不同，因此按排名融合。关键词相似、向量相似和材料能否支持结论是不同检查，不能用命中代替答案审查。完整机制与后续维护边界见 [设计 4.4](../specs/004-research-agent/design.md#44-报告资料库与-rag-接入)。

模型只传检索文字与可选年度，当前用户／空间／股票／周期由 Java 校验和绑定，观点日期不限制年报年份。向量库是可重建索引，本机受审发布记录是正文权威来源。回复只保存实际引用的 REPORT 依据，放进现有 agent_message.result_json，不新增聊天表；里面保留原文、报告哈希、章节和 PDF 页码。查看记录与引用重校验权限及对应已发布版本：正常更新保留旧依据，移除或记录损坏时不从聊天快照恢复原文，回看不新增模型调用。

本机资产准备在项目根目录运行 scripts/setup-report-rag.ps1 -EnableLocal：核对固定公共权重及官方 Qdrant 文件，写入被忽略的本机开启配置，不启动服务，也不调用回答模型。当前机器已完成这一步；IDEA 重新加载 Maven 后重启 dev 后端，Vue 开发页由 Vite 更新。Java 在首次需要报告检索时启动自己的回环 Qdrant，正常关闭时释放；端口占用不接管其他服务。强制结束后的恢复先核对文件锁及进程身份，不按进程名批量清理。默认与生产仍关闭，其他操作系统需单独落实服务与验证。

页面入口：研究详情 → 研究助手 → “可查询的资料与报告”，查看当前股票已入库的具体章节。可以在腾讯问“2025年年报怎样解释营销服务收入增长”，在茅台问“2025年年报列举了哪些经营风险”。点回答中的“查看报告原文依据”能看实际段落和 PDF 页码，再打开官方原报对应页；没有对应股票／年份、无相关候选和检索故障分别说明。只覆盖受审文字，不等于整份报告或全部最新披露。

开发验收用 scripts/run-agent-report-probe.ps1：独立 test HTTP／真实 MySQL／Chrome、Java 本地向量和 Qdrant 服务端，回答模型是回环替身。先核对两条环境探针与真实 catalog，随机样本 finally 逐表清零，测试目录与 18082／18083／18084／16335／16336 五个端口一并清理；不会调用 DeepSeek 或占用其预算。单独验证 Java 检索时启用 REPORT_RAG_LOCAL_PROBE=true 后运行 LocalReportRetrievalProbeTest；普通回归跳过该本地资产依赖。真实回答质量与生产部署另验，最新结果只见 tasks。

兼容排查：Python ONNX 可运行不证明 Java JNI 路径可用。本机首次 ORT Java 1.31 在 JDK17 的旧 msvcp140.dll 初始化崩溃；仅调整 Java 依赖后重新跑实际模型，不替换系统 DLL／JDK。Qdrant 集合名过长还会触发 Windows 段目录路径限制；用包含完整内容／编码身份的短哈希名解决，不能为缩短路径丢掉版本隔离。Windows ProcessHandle 不提供 arguments，子进程恢复以精确创建时刻、可执行路径、已退出的原父进程、数据目录、端口和独占锁联合校验。

### 检索对照与报告回答评估

默认混合检索，查询词覆盖重排关闭。需要定位差异时，可在临时后端启动参数中用 `--stock-system.agent.reports.retrieval-mode=VECTOR` 或 `KEYWORD` 切换，`--stock-system.agent.reports.rerank=true` 开启本地重排；日常开关和模型预算不随此变更。向量服务失败仍是检索故障，不把关键词结果冒充完整混合结果。

在项目根目录运行下面的离线比较。本机依赖已缓存，所以 `-o` 禁止下载，指定 settings 沿用依赖缓存的仓库身份，避免全局镜像不一致导致误报缺依赖；测试加载真实本地向量模型并临时监听 16334，不连接业务 MySQL 或付费模型。运行前保证端口空闲。

```powershell
$env:REPORT_RAG_LOCAL_PROBE = 'true'
mvn -o -s scripts/agent-compatibility-maven-settings.xml "-Dmaven.repo.local=D:\Java\stockweb\.m2repo" "-Dtest=ReportRetrievalEvaluationTest" test
Remove-Item Env:REPORT_RAG_LOCAL_PROBE
```

输出 `target/report-retrieval-comparison.json`：逐题保存关键词、向量、混合、混合后重排的候选／位置、版本与题集哈希，分别统计首位／前三命中、MRR、无答案拒绝及单次耗时。所有策略共享同次向量查询，耗时含热身、顺序和共享编码影响，不能据此宣称生产提速。参数固定后才跑留出改写题，不根据留出结果继续调参。临时向量目录 finally 删除，16334 关闭；真实排名坏例子保留，不因加入重排就声称更好。

[六题报告题库](../../scripts/agent-quality/report-cases.mjs) 将“候选命中”和“回答受到支持”继续拆开。覆盖营销驱动、未来渠道计划、观点＋财务＋原文组合、缺年度及两类无答案；每题保留应核对的语义清单。先只读打印计划，再运行默认替身模式：

```powershell
node frontend/e2e/research-agent-financial-probe.mjs --suite=reports --dry-run
./scripts/run-agent-report-quality.ps1
```

报告与财务入口共用受保护的运行器；`--suite`／题目编号未知、重复或跨题库时在外部连接前拒绝。替身模式实际串联 test HTTP／MySQL、Java 向量／Qdrant、三工具组合、保存、幂等与恢复，`paidRequests=0`，不能据替身输出关闭真实模型语义质量。结果在 `target/agent-reports-fixture-*`，查看 `results.json` 的提问、输出、原文、工具轨迹及 `verification.json` 的清理结果。程序只验证原文身份、章节及引用关系，`semanticReview` 保持待人工检查。

真实模式仅在已核对本轮问题、公开报告段落外发和预算授权后运行 `scripts/run-agent-report-quality.ps1 -Mode Real -ApprovedReportData`；可用 `-CaseIds` 限定题目，`-ContinueFrom` 仅复用相同题库／模式／范围的已通过部分，绝不重发已执行问题。仍沿原累计 10 元 JSONL，保留既有未知用量预留，新的未知用量或运行失败立即停，无自动付费重试；不新增预算、不覆盖旧账本。六题计划最大每题四次模型请求、三次工具尝试。默认财务入口及其授权开关保持，财务授权不能代替报告外发授权。真实结果必须逐项对照原文审查，代码和替身检查不自动填为通过。

当前报告事实段落采用受审原文摘录交付，来源仍由模型按本次问题选择，Java 补真实原段／页码并保留限定词；成员观点和财务解释另按原规则核对。真实核对评估最终交付结果，不将 Java 摘录写成模型自由概括提高；取舍见 [BUG-013](bugs/BUG-013-report-scope-generalization.md)。已有获准结果可先离线回放：在项目根目录将 REPORT_ANSWER_REPLAY 设为 target 内对应 results.json，再定向运行 ResearchReportAnswerReplayTest，随后对生成的 target/report-answer-support-replay.json 执行同一题集程序契约和原文对照。输出记录输入哈希、旧模型版本、新流程版本及新增模型请求为 0，不覆盖原文件、不读取 Key；回放不替代新版本真实运行或授权。

### 本机报告维护

这是系统维护操作，普通用户仍只在助手页查看资料范围、提问和点原文，不增加上传入口。公开报告发布记录在 `.local/report-rag/library/library.json`；私人聊天、运行与预算继续保存在 MySQL 四表。文件只适用于当前单机小语料，生产共享维护另验，状态与版本机制见设计 4.4。

下面在项目根目录的 PowerShell 运行，使用已打包的 JAR 与当前 Java 17；改过 Java 后先按既有构建流程打包。先导出腾讯单份受审材料，再设置这个终端内的命令参数。Python 导出仍会核对原 PDF 哈希和段落，不读取 Key，不调用模型；超出已审来源的股票不会导出。

```powershell
& .local/rag-preflight/venv/Scripts/python.exe scripts/rag-preflight/export_catalog.py --symbol 00700.HK --output target/report-tencent-reviewed.json
$reportJava = Join-Path $env:JAVA_HOME 'bin/java.exe'
$reportCommand = @('-Dloader.main=com.stock.system.agent.report.ReportMaintenanceCommand', '-cp', 'target/stock-system-0.0.1-SNAPSHOT.jar', 'org.springframework.boot.loader.launch.PropertiesLauncher')
$reportLibrary = '.local/report-rag/library'
& $reportJava @reportCommand status $reportLibrary
```

status 返回固定状态和文档 key，退出码 0、最后一行 JSON 的 status 为 OK 表示成功。import 只导入一份报告，先建候选索引再发布；已经可用的相同版本不会重建。每次展开页面的“可查询的资料与报告”都会重读状态，已启用新代码的后端不必为资料更新再次重启。

```powershell
& $reportJava @reportCommand import $reportLibrary target/report-tencent-reviewed.json
```

处理失败时退出码为 1，不覆盖旧可用版；页面会说明仍可用旧版。修好本机资产或释放被其他程序占用的 16336 后，可将 import 换成 retry。中断停在 PROCESSING 也使用 retry，由新代次接替；没有后台自动重试。维护索引在 library 的同级 maintenance-index，日常查询仍使用自己的目录和 16333。

remove 的最后一个参数是 status 返回的文档 key，会同时禁止新检索和旧聊天原文。普通 import 不恢复已移除资料；确需重新提供时使用 restore，参数与 import 相同。移除是业务禁用，当前不自动物理删除旧正文版本或向量集合；不要为恢复一份报告删除整个 library.json，这会丢掉其他移除状态。

### 模型失败如何排查

“研究助手暂不可用”是给页面的统一反馈，不能单凭它判定数据错误、超时或欠费。先查同一运行的 model_calls 与 tool_trace_json，确认失败前走到了哪步，再查 V15 增加的 model_failure_json：stage 指定梳理／问答模型阶段，modelCall 指第几次模型派发，upstreamStatus 和 category 区分 HTTP、限流、认证、超时、连接、TLS、响应解码或未知错误。安全日志沿相同 run／request 编号串联，不记录原始响应、异常消息或密钥。

例如第 23 节旧腾讯记录的两次财务工具均完成，随后第二次模型请求没有有效用量，且旧日志未保留异常原因；只能定位到后续模型调用，不能补猜具体根因。本次修复诊断不等于该真实接口已恢复。未知用量继续保留原预留，不以已知前一次 Token 释放它；先取得服务商可核对的用量证据，再处理账本，不能将预留当成确认实扣或直接清零。

### SSE 进度与停止如何核对

新页面发出带认证头的 fetch POST 流请求，按真实阶段显示进度；正文仍完整校验和保存后一次显示。出现“正在整理回答”表示已进入模型派发阶段，不表示已有可交付正文。点击“停止生成”后等待当前外部请求返回或超时，再停止后续工具／模型、结算并显示终态；不承诺供应商取消在途请求或免单。正常停留时问题与已有答案保留，停止完成后可继续编辑；草稿只在本次页面会话内存中，刷新不持久化它。

离开／刷新只断开订阅，服务端仍可能完成；回到页面先 GET 原 requestId／runId，不自动再发送付费请求。停止标记存在 V16 的 agent_run.cancel_requested，RUNNING＋true 表示仍在收尾，CANCELLED 表示已结束且未保存正文；已成功运行不会被迟到停止覆盖。排查时同时看 model_calls、tool_calls、用量与 cost_upper_yuan，不能把“前端连接断了”直接当作模型已停。受控复现沿 [测试说明](testing.md#25-研究助手的受控与真实模型检查)，协议和并发边界只维护在 [设计 6.3](../specs/004-research-agent/design.md#63-用户看到的状态)。
