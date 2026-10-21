# BUG-014：缺失比较年度的合法说明被财务校验拒绝

2026-10-18。作用于新生成且带 FINANCIAL 引用的段落；金额保护的原始动机见 [BUG-012](BUG-012-financial-answer-unit-conversion.md)。

## 现象与证据范围

真实综合题集中，“2024 年收入，以及能否计算相对 2023 年同比”在第二次模型派发后被 QUESTION_REPLY_FINANCIAL 拒绝，已知用量结算、正文不保存、后续采样停止。该次没有保留模型原始正文，旧诊断也没有具体规则，不能证明它一定返回了正确文字，或断言下面的缺失年份是其唯一根因。

离线用实际受审 2024 年快照构造合法回答：“2024年收入660257百万元人民币，本次缺少2023年比较列，不能计算2024年相对2023年的同比。”旧校验确定性失败，基线保留在 target/agent-financial-missing-year-baseline.log；由此确认了一个可独立修复的同阶段缺陷。

## 根因与修复

[ResearchFinancialAnswerNumbers](../../../src/main/java/com/stock/system/agent/service/ResearchFinancialAnswerNumbers.java) 先从已引用快照收集数字，再拒绝正文中没有来源的数字。快照只包含 2024 年目标值、2025 年原报等元数据；2023 年比较列恰好不存在，所以合法的“缺少 2023 年”也被当成了无依据数字。直接放行问题中的所有数字会授权用户输入的假金额，不能这样修。

v11 仅从可信年度 current.year 和 COMPARABLE_PERIOD_MISSING 推导上一年度，且只放行明确 YYYY年／FY YYYY 的年份位置。这个年份不会加入可用金额或百分比集合；裸写 2023、写成 2023 元或 2023% 仍拒绝，其他年份也不借此获准。原来数字正则还会吞尾逗号，并把 NFKC 归一化后的中文逗号当作数字分组；现按有效千分位匹配，保持精确原报金额，同时将标点分隔的年份分别读取。

拒绝时只保存 UNSUPPORTED_LITERAL、PERCENT_UNIT_REQUIRED 等固定 validationRule，与阶段、运行编号和 HTTP 状态关联，不保存被拒数字、原始正文、异常消息或凭据。通用失败的该字段为空时省略，旧诊断 JSON 仍可读取；无需数据库迁移。

## 验证与复习

[数值检查](../../../src/test/java/com/stock/system/agent/service/ResearchFinancialAnswerNumbersTest.java) 对比合法缺失年份和金额／比例／裸数／其他年份／负号／小数／伪分组的拒绝；[原生工具检查](../../../src/test/java/com/stock/system/agent/ResearchFinancialQuestionTest.java) 验证读取、引用和最终校验链；[诊断兼容](../../../src/test/java/com/stock/system/agent/ResearchModelFailureTest.java) 验证旧 JSON 与安全输出。Chrome 另检查经 SSE 接受合法缺失说明、保存和刷新不新增模型派发；实际成绩及真实复测状态只见 [任务第 31 节](../../specs/004-research-agent/tasks.md#31-综合题集逐题审查与缺失比较年份补修2026-10-18)。

项目解释时分清“真实失败被拦住”“离线确认一个缺陷”“当前版本真实恢复”三层证据。校验器既要防止错误放行，也要防止正确回答被拒绝；数值相同不等于类型相同，年份、金额、单位和百分比需要分别解释。本例保护仍不是语义证明，科目／期间对应、增长下降和事实边界仍需逐题核对。
