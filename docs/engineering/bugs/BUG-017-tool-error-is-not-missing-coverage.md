# BUG-017：工具参数错误被解释成资料未覆盖

2026-10-21。作用于受限工具追问的状态解释及安全轨迹，不改变股票权限或放宽参数白名单。

## 真实现象

v12 真实批次 target/agent-closure-real-20261021-100731-40a53315 的前五份回答通过。季度题随后调用 read_financial_facts，工具轨迹为 ERROR，模型却直接回答“系统资料中没有覆盖2025年第三季度单季度数据”。没有成功完成查询，就没有从这次工具结果取得未覆盖的依据；程序检查因此停止，后八次未调用。

旧轨迹只保留工具名、状态和耗时，没有错误码或原始参数，不能反推这次究竟是哪一个参数错误。离线检查确认，工具说明列了三类 series，却只给 period 一个年度例子；模型规则也没有明确区分 ERROR 与成功返回的 UNAVAILABLE。格式说明不足是已确认的契约缺口，不能说已经还原了该次原始参数。

## 修复

[ResearchQuestionService](../../../src/main/java/com/stock/system/agent/service/ResearchQuestionService.java) 的 v13 给出匹配系列的 FY／H1／Q1～Q4 前缀、年份顺序和 Schema 格式；指定季度时按季度查询，由 Java 返回覆盖状态，不以全年数据替代。ERROR／INVALID_REQUEST／INVALID_TOOL_ARGUMENTS 表示查询未完成；有剩余额度时修正参数补查，仍失败则如实说明未完成，不能据此宣称没有该期间。

[执行轨迹](../../../src/main/java/com/stock/system/agent/service/ResearchModelExecution.java) 新增可选 errorCode，只取本程序错误结果中的固定码，不记录原始参数、模型正文、异常消息或凭据。旧三字段构造与 JSON 仍兼容，成功轨迹不增加空错误码字段。它帮助区分参数解码拒绝、业务参数错误和真正成功的未覆盖结果，不追补旧未知原因。

## 验证边界

[财务追问测试](../../../src/test/java/com/stock/system/agent/ResearchFinancialQuestionTest.java) 分别验证正确季度请求完成并返回 PERIOD_NOT_COVERED、参数错误仍为 ERROR／INVALID_REQUEST、模型额外对象参数在 Service 前被拒及安全码保留；[兼容测试](../../../src/test/java/com/stock/system/agent/ResearchModelFailureTest.java) 验证旧轨迹 JSON 还原。原始精确金额、权限与有限调用规则不变。

最终 v14 原题完整批次的季度题成功完成财务工具，并按本次 SOURCE_NOT_CONNECTED 说明未取得季度资料，没有以年度数字代替；没有参数 ERROR 冒充覆盖结果。该真实未取得原因与上述替身的 PERIOD_NOT_COVERED 分开记录，旧参数根因仍未追溯。完整回归及逐题证据只见 [任务第 33 节](../../specs/004-research-agent/tasks.md#33-最终版本完整回归与原题集核对2026-10-21)。查询执行失败、成功但未取得资料、取得资料但答案错误是三种不同结果，不能合并成“没有资料”。
