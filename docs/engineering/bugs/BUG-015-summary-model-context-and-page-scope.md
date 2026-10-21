# BUG-015：模型未见报价被写成页面没有行情

2026-10-19。作用于研究助手的首次周期梳理，不扩大为全部追问、历史正文或账号昵称规则。

## 真实现象与定位

综合真实验收中，财务／报价八次采样核对通过后，周期梳理返回完整结构、正确的甲看多转中性与乙看空，但待核对项写“本次未提供公告、财报、新闻或行情资料”；同一 Answer.quote 实际为 AVAILABLE、612 HKD、LOCAL_MOCK、simulated=true。overview／changes 还出现成员 M1 和 op-8949 等内部标识。程序成功不能算作语义通过，逐题门禁拒绝该条并停止后续五次；已知用量仍结算。

旧 ResearchSummaryPrompt 直接告诉模型“仅提供研究观点，没有提供…行情资料”，同时只在系统规则说“报价由 Java 另行展示”。Service 虽已读取报价，却未将状态传入模型，因此模型把自身输入的边界写成了整个页面的边界。原提示也只有结构字段示例，未明确正文应使用显示昵称；原 Validator 只验证结构和引用关系。

原完整证据保留在 target/agent-closure-real-20261018-225214-0f06d571/results.json。版本化回放 [summary-scope-regression.json](../../../src/test/resources/agent/summary-scope-regression.json) 保存真实正文、引用、时间及报价，源结果哈希为 4d5e846c…bc702；authorUserId 使用本地别名数字，不复制测试登录身份。回放与真实新回答分别记录。

## 修复与边界

梳理 v3 使用已读取的同一 Quote 快照构造 Prompt，最终页面仍直接展示这份 Java 快照，没有第二次行情请求，也不让模型生成价格字段。AVAILABLE 不等于今日真实报价，UNAVAILABLE 不等于市场没有报价，null 不等于已经查询；模型不能把“尚未核实订单／盈利”改写成页面未提供行情。序列化消息和完整 HTTP 的原有上限继续生效，报价查询前仍先预检观点消息大小。

Prompt 明确 text 用 authorName，M 编号和 op 编号只在结构字段；Validator 拒绝正文中本次已知内部编号的独立字面。真实显示昵称或原文恰好包含 M1 等术语时保留，防止把本地展示检查扩大为禁词或账号政策。旧回答不追溯改写，未识别的自然语言错误仍由语义核对发现。拒绝只记录固定 SUMMARY_REPLY_CONTRACT 阶段与业务分类，不记录原始模型正文或异常消息。

## 验证与复习

[真实坏例子回放](../../../src/test/java/com/stock/system/agent/ResearchSummaryReplayTest.java)、[Service](../../../src/test/java/com/stock/system/agent/ResearchSummaryServiceTest.java) 与 [Validator](../../../src/test/java/com/stock/system/agent/ResearchSummaryValidatorTest.java) 分别验证旧输出拒绝、同一报价输入与展示、可用／不可用状态及合法昵称／术语边界。Chrome 用本地替身检查 SSE 错误保留旧结果、错误不入消息和安全失败阶段；具体次数与后续真实复测只见 [任务第 32 节](../../specs/004-research-agent/tasks.md#32-梳理报价范围与正文编号补修2026-10-19)。

项目解释时先画清每层实际看到的数据：Java 已查到什么、模型收到什么、页面展示什么，三个集合不能互相替代。结构和引用验证通过只说明格式与关系合法，不证明所有覆盖说明正确；修复原始输入与继续做真实语义核对缺一不可。
