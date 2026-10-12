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

Java 的 `if`、`for`、`return` 是语言关键字；`ChatClient` 是框架类型，`prompt()`、`user()`、`call()`、`content()` 是库方法。前者结合正式业务的小方法练到能写，后者先认识用途、能读懂参数和返回值，再借助已核对示例与 IDE 补全使用；记不住拼写不等于不会，但只会说概念也不等于能实现。检查点是能根据需求选择步骤，读出输入与输出，亲手改一小块并解释结果。

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

`prompt()` 组织本次请求，`system()` 放回答要求，`user()` 放问题与资料，`call().content()` 沿同步路径取得文字结果。业务要先确定有权资料、再调用、最后检查结果；这段调用不会自己查观点或建立 RAG。正式观点读取入口见 [timeline](../../src/main/java/com/stock/system/stock/service/StockResearchLifecycleService.java)，其周期、分页与撤回边界仍沿规格验证。

复习按“要解决的问题 → 输入资料从哪来 → 谁执行哪一步 → 输出怎样核对”回忆，再到正式功能里亲手改一处；当前观看进度不作为个人掌握证据，教学交接见 [状态](../project/status.md#求职专题学习交接)。依据：[ChatClient](https://docs.spring.io/spring-ai/reference/api/chatclient.html)、[Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)、[Advisor](https://docs.spring.io/spring-ai/reference/api/advisors.html)、[RAG](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)。

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

默认 `spring.ai.model.chat=none`，所以原系统启动不依赖模型密钥。当前主工程只接入框架，生成接口与用户页面仍待实现；启用模型客户端不会自动让它读取 MySQL 或产生研究回答，也不等于预算、授权和会话机制已经完成。[ResearchAiConfiguration](../../src/main/java/com/stock/system/agent/ResearchAiConfiguration.java) 在启用 DeepSeek 时提供零重试的 RetryTemplate，避免框架在失败后隐式补发。首次请求次数与重试次数的区别、实测故障及修复只见 [BUG-011](bugs/BUG-011-spring-ai-retry-count.md)。

框架回归位于正式 `src/test` 的 [SpringAiCompatibilityTest](../../src/test/java/com/stock/system/agent/SpringAiCompatibilityTest.java)，回环替身只检查 HTTP 适配、工具配对、结构转换和实际请求次数；手造向量只检查检索组件过滤／删除，不证明中文报告质量。这些是项目测试，不恢复独立教学工程。学习继续沿正式研究梳理的数据路径，版本迁移由 AI 处理，不要求学生先记完库方法。
