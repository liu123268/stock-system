# BUG-020：细碎模型分块在背压下乱序

2026-10-22。来源为真实框架连接回环 HTTP 的可重复离线实验。此前三个真实梳理请求都在首个预览后失败，但没有保存原始回复，不能逐次证明它们均由此问题导致。

## 现象与定位

梳理与追问原生流原先直接用 `toIterable(1)` 消费 Spring AI 的 ChatModel.stream。业务线程在完整条目到达时查询来源并校验，短暂停顿会向上游施加背压。只有两个正文块的替身通过，不能证明很多细碎分块仍按原序到达。

[回环测试](../../../src/test/java/com/stock/system/agent/ResearchQuestionStreamModelTest.java) 将固定的自造 JSON 逐字符发出，在消费第 20 块时停顿 80ms。修复前完整文字的相等断言失败：后面的编号片段及 JSON 结尾前移，较早片段落到了结尾之后，尽管 HTTP 请求、结束标记和用量均正常。证据为 target/agent-summary-stream-burst-before-20261022.log；不涉及真实模型、真实用户正文或数据库。

固定 Spring AI 2.0.1 的 [DeepSeekApi](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-deepseek/src/main/java/org/springframework/ai/deepseek/api/DeepSeekApi.java) 把窗口归并成 Mono，再用 flatMap 展开；这不是承诺顺序的串联消费。上述实验确认该调用链在当前背压条件下可乱序，不能只把最终 JSON 失败归因于模型写错格式。

## 修复与边界

[ResearchModelStream](../../../src/main/java/com/stock/system/agent/service/ResearchModelStream.java) 先在接收路径提取正文、工具、模型名、结束原因和用量，快速接入最多 4096 项的 onBackpressureBuffer；业务线程再逐项消费并执行来源校验。[Reactor 官方契约](https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Flux.html#onBackpressureBuffer(int)) 说明这种缓冲向上游请求不受下游需求限制的数据，并有队列数量上限。当前 SDK 单 HTTP 分块窗口因此不再受业务数据库停顿的逐项需求影响；这不是通用的 flatMap 排序算法，也没有事后重排或修补 JSON。

队列只保存所需字段，不保留框架思考或原始元数据。接收正文与工具载荷合计最多 65536 个 UTF-16 字符、八个工具调用，模型名／结束标记各有长度限制；最终正文仍限 32000 字符。超限标记整轮无效并在原期限内收尾取用量，不交付截断内容；队列本身溢出或传输中断时没有取得用量则继续保留未知预留。预览异常仍禁止后续发布，已派发费用与正文分别处理，没有自动重试。

修复后的梳理 v7 和追问 v19 共用该传输。回归覆盖消费第 1／20／100 块暂停后原文逐字符完全一致，以及超大载荷拒绝但已知用量照常结算；原结束标记、未知用量、取消、HTTP 错误与请求编码大小检查保留。最终真实采样、浏览器、费用和清理证据只见 [任务第 38 节](../../specs/004-research-agent/tasks.md#38-首次梳理原生分区预览与背压顺序修复2026-10-22)。

## 复习与面试

网络字节按序、框架中的异步合并按序、业务线程最终消费按序，是三个需要分别验证的条件。测试应包含许多细块和慢消费者；只发一整段或两个大块容易漏掉背压边界。缓冲必须同时考虑条数与载荷，停止显示也不等于供应商已经停止计费。升级框架后继续用相同顺序实验核对，不能把这次限定路径的修复推广成任意并发流都自动有序。
