# BUG-011：模型重试次数与首次请求次数混淆

## 现象与影响

2026-10-12 正式接入 Spring AI 2.0.1 时，回环 HTTP 故障检查发现：设置 `spring.ai.retry.max-attempts=1`，供应商持续返回 503，实际收到两次请求。需求是失败后保留问题、由用户决定重试；隐式补发可能增加费用或产生重复生成。

本次只连接自造回环替身，尚未开放正式研究生成，没有真实扣费、私人资料发送或用户数据损害。

## 根因与修复

当前版本的 Spring AI 自动配置把 `maxAttempts` 交给 Spring Framework `RetryPolicy` 的 `maxRetries`。后者表示首次请求之后还能重试几次；传 1 就是一次首次请求加一次重试，不能仅按属性名称理解它。

正式 [ResearchAiConfiguration](../../../src/main/java/com/stock/system/agent/ResearchAiConfiguration.java) 在 DeepSeek 模型启用时提供 `RetryTemplate(RetryPolicy.withMaxRetries(0))`，覆盖默认重试策略。这里只控制框架自动重试，用户发起新请求、工具循环中的后续模型请求及业务运行预算仍须分别控制；该配置不等于已实现幂等或费用账本。

## 验证与预防

[SpringAiCompatibilityTest](../../../src/test/java/com/stock/system/agent/SpringAiCompatibilityTest.java) 让替身持续返回 503，修复前请求次数为 2，修复后为 1；同时回归正常工具循环，允许它在收到工具结果后继续请求。完整运行、构建与边界只见 [SPEC-004 第 9 节](../../specs/004-research-agent/tasks.md#9-ag-code-002正式后端框架接入2026-10-12)。

以后调整模型适配或重试配置时，核对实际发送次数与失败传播；不能以属性值、编译通过或最终异常推断没有重复请求。次数限制与固定问题集沿规格执行，不另建一套规则。

## 面试复述

需要区分“最多尝试几次”和“首次失败后再重试几次”。这个问题通过 HTTP 替身计数发现，修复使用零重试策略，并同时验证正常工具循环继续有效。实测发生在回环环境，不能包装成线上故障或实际节省了多少费用。
