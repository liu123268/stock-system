# BUG-018：JSON 语法与回复结构失败没有分开定位

2026-10-21。作用于追问最终回复的解码边界，不改变模型调用上限、重试或财务事实规则。

## 真实失败与已知范围

v13 的 target/agent-closure-real-20261021-102301-fb76b275 在腾讯年度题三次通过后，茅台题第二次模型返回 HTTP 200、用量已知，随后以 QUESTION_REPLY_JSON／INVALID_RESULT 结束，无最终答案、未保存错误消息，后十次没有派发。

旧实现用一次 strict.readValue 同时解析 JSON 并映射 ResearchReply，只有成功后才切到 QUESTION_REPLY_CONTRACT。因此语法错误与字段／类型不符合契约都会落在 JSON 阶段；原始失败正文没有保存，不能确认本次属于哪一种，也不能补猜具体金额或格式。

## 修复与限制

[ResearchQuestionService](../../../src/main/java/com/stock/system/agent/service/ResearchQuestionService.java) 的 v14 先严格解析唯一 JSON 值，再在 CONTRACT 阶段检查对象形态并映射 DTO。未知字段、错误类型、空段落、非法来源和错误财务数字仍拒绝，保留原大小限制及安全诊断，不记录原始响应或解析异常消息。

同时只兼容完整单段 JSON 围栏及开头 BOM：可有 json 标签或无标签，不扫描夹杂自然语言中的 JSON，不拼接多对象、不修补 JSON 语法、不改事实，也不忽略额外字段。离线证实旧入口不支持这种纯格式包装；**这不证明 v13 那次真实失败就是围栏造成的**。

没有增加自动付费重试。终止的旧请求不恢复、不改写为成功；必要新批次仍在持续授权及原累计预算内逐题核对。

## 验证

[财务追问检查](../../../src/test/java/com/stock/system/agent/ResearchFinancialQuestionTest.java) 覆盖 BOM、JSON／无标签／CRLF 围栏，拒绝前后正文、其他语言、多围栏及尾随 JSON 值；有效 JSON 的 null／数组／未知字段归入契约阶段，围栏中的错误金额仍被财务保护拒绝。各路径仍只有计划内模型调用，没有格式修复的额外请求。完整回归与真实新批次只见 [任务第 33 节](../../specs/004-research-agent/tasks.md#33-最终版本完整回归与原题集核对2026-10-21)。

最终 v14 同一批十题十四次真实输出均通过程序与逐条来源核对，茅台原题此次正常交付；这证明本次新流程完成该题集，不证明旧失败就是围栏问题，也不保证今后输出不会违反 JSON 或 DTO。原始失败与费用仍保留，没有改写旧运行状态。
