# Java Agent 最早实验：理解一次工具追问

本入口属于 [AG-CODE-001](../specs/004-research-agent/tasks.md#1-实施顺序)，用 Java 17 和现有 Gson 依赖，不启动 Spring、数据库、前端或行情连接。全部观点与价格是自造样本。替身答得正确不能证明真实模型会正确选工具或理解资料，实际证据只见 [任务第 6 节](../specs/004-research-agent/tasks.md#6-java-模型实验准备2026-10-09)；财务源预检独立见第 5 节。

## 1. 先理解这一条数据流

问“小陈为什么从看好改为中立？”时，初始消息只有作者和记录数量，没有观点正文。模型可以提出 `read_cycle_opinions({"authorId":"M1"})`；[Java 执行工具](../../scripts/agent-lab/ResearchAgentExperiment.java) 校验名称和参数，读取当前固定研究周期内 M1 的有效观点；然后把工具结果及对应调用 ID 发回模型，模型才有资料组织回答。完整观点已在消息里的对照样本允许直接回答。

模型返回的函数名和参数只是执行请求。实际读取发生在 Java，模型不能因此直接操作数据库、改变用户或空间。工具范围在程序里绑定，参数只有可选作者；增加 workspaceId、未知作者或未知工具都会被拒绝。样本不接正式授权 Service，真实身份和权限将在 AG-CODE-003 另外验收。

[调用循环](../../scripts/agent-lab/ResearchAgentExperiment.java) 按设计第 5 节限制请求次数、工具执行、输入输出及总时限；超时、截断、非法引用、已撤回来源、遗漏成员或用旧记录冒充最新观点不能标成完成。JSON 字段和来源通过只说明程序校验通过，语义是否有依据仍需人工核对。

首次梳理的价格由 Java 添加，保留币种、时间、来源与模拟标记；模型生成概览、变化、成员和待核对事项。模拟报价不可用时仍可整理观点。源码中的 `runSample` 是阅读核心链路的入口；`DeepSeekExperimentClient` 负责 HTTP 与费用记录，`ExperimentChecks` 提供模型替身与边界检查。

## 2. 在电脑上运行离线实验

在项目根目录 `D:\Java\stockweb\stock-system` 的 PowerShell 执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-agent-experiment.ps1 -Mode Offline
```

脚本使用 JAVA_HOME 中已安装的 JDK 编译三份独立源码，Gson 从已有本地 Maven 仓库读取，不安装新依赖；类文件写入忽略的 `target/agent-experiment/classes`。`Offline` 使用事先写好的模型替身响应验证 Java 流程，不读取 API Key，不发网络请求；`Bypass` 仅对子进程生效，不修改系统执行策略。

成功时看到明确标注的模拟报价、模拟梳理与工具追问过程，末行是 `AGENT_LAB_OFFLINE_OK`。检查数来自当前程序实际执行；替身答得正确不代表真实模型质量通过。临时检查账本在 finally 删除，正式实验账本不受离线检查影响。

## 3. 配置就绪后验证真实模型

用户已确认 DeepSeek 官方平台。此入口只接受 `https://api.deepseek.com` 和 `deepseek-flash`；中转平台的地址、模型、费率和余额协议不同，不能套用同一配置。当前模型、模式、预算及价格依据只见 [技术规划第 3.5 节](../architecture/technology-stack.md#35-首轮模型与费用安排)。

当前工作区已创建无密钥的 `.env.deepseek-local.properties`，在 IDE 中打开它，仅填入 `deepseek.api-key`。其他机器复现时，把 [无密钥模板](../../scripts/agent-lab/deepseek-local.properties.example) 复制到项目根目录并改成这个文件名。根目录 `.env.*` 由 Git 忽略；真实 Key 不填回模板、不发聊天、不放命令参数。没有该配置或没有有效 Key 时程序拒绝真实调用。此步骤只是配置本项目，与 WorkBuddy 中已填密钥的设置互不自动同步。

确认模型与费率仍符合技术规划后，在同一目录执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-agent-experiment.ps1 -Mode Live
```

`Live` 先读取官方余额接口，仅核对 CNY 可用额度，不输出原始余额；然后运行 15 个自造样本，包含 EV-001、EV-003、EV-004 各三次及空／单条、已有上下文、注入文字、报价不可用和缺财报对照。最多每样本三次请求，无自动付费重试。Key 尚未配置时不用执行本命令。

[费用实现](../../scripts/agent-lab/DeepSeekExperimentClient.java) 按当次核对的较高单价计入费用上界，缓存优惠不提前假定；每个请求前保守预留 4.016 元，依据是高于官网 1M 上下文的 200 万输入 Token 上界及受控的 2,000 输出 Token，不把字节或汉字当准确 Token。预留不是实际花费，取得合法用量后按较高费率结算。账户余额不足以覆盖该保守预留会停止，不自动充值或切模型。

`.local/agent-experiment/budget.jsonl` 独立记录累计费用上界和未确定用量的占用，文件锁防止实验并发；超时、网络异常、缺用量等不释放不明占用，重启不会重置。达到已确认预算停止；不要删除账本来绕过预算。该保护依赖已核对的模型及费率，只约束本入口，不能控制其他程序对同一账号的花费，也不代替服务商账单。

通过结构校验的结果与必要用量保存在 Git 忽略的 `.local/agent-experiment/live-*.jsonl`、`usage-*.jsonl`，包含模型、提示词版本、工具轨迹、耗时与自造样本答案，不保存 Key、原始请求头、思考正文或供应商错误正文。用样本原文人工检查判断变化、担忧与事实、公告是否确实存在、引用是否支持句子；`STRUCTURE_VALID_REQUIRES_MANUAL_REVIEW` 不代表样本质量验收通过。失败停止后先检查原因和已记录用量，不反复启动付费试错。

协议依据：[DeepSeek 工具调用](https://api-docs.deepseek.com/guides/tool_calls/)、[Chat Completion](https://api-docs.deepseek.com/api/create-chat-completion/) 和 [余额接口](https://api-docs.deepseek.com/api/get-user-balance/)。非思考模式显式关闭 thinking；普通参数及最终回答由 Java 校验，不依赖 Beta strict。真实运行证据只见 SPEC-004 tasks，不在本说明里提前记为已通过。
