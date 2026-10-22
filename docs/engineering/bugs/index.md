# Bug 复盘目录

这里集中列出已经沉淀的、具有复发风险或教学价值的问题复盘。每篇复盘记录现象、根因、修复和验证证据；普通小错误不单独建立复盘。

- [BUG-001：认证页面样式与布局不一致](BUG-001-auth-layout-style-mismatch.md)
- [BUG-002：MyBatis 归档恢复的空值处理](BUG-002-mybatis-null-archive-restore.md)
- [BUG-003：浏览器测试与数据库边界](BUG-003-browser-test-database-boundary.md)
- [BUG-004：股票与账号联合搜索的中文字符集问题](BUG-004-stock-search-character-set.md)
- [BUG-005：邀请时间精度边界](BUG-005-invitation-millisecond-boundary.md)
- [BUG-006：退出后的旧请求改变导航与会话](BUG-006-stale-session-response.md)
- [BUG-007：图表卸载后迟到请求重新启动刷新](BUG-007-market-panel-delayed-refresh.md)
- [BUG-008：进入研究页后丢失已保存提示](BUG-008-saved-result-lost-on-navigation.md)
- [BUG-009：研究目标变化后的旧写入回调](BUG-009-research-target-lifetime.md)
- [BUG-010：无价格时丢失交易状态与旧状态冒充当前核实](BUG-010-trade-status-without-price.md)
- [BUG-011：模型重试次数与首次请求次数混淆](BUG-011-spring-ai-retry-count.md)
- [BUG-012：财务快照正确但模型正文金额或百分比单位出错](BUG-012-financial-answer-unit-conversion.md)
- [BUG-013：报告引用正确但摘要扩大原文对象范围](BUG-013-report-scope-generalization.md)
- [BUG-014：缺失比较年度的合法说明被财务校验拒绝](BUG-014-financial-missing-comparison-year.md)
- [BUG-015：模型未见报价被写成页面没有行情](BUG-015-summary-model-context-and-page-scope.md)
- [BUG-016：金额正确但把收入统称为归母口径](BUG-016-financial-revenue-profit-basis.md)
- [BUG-017：工具参数错误被解释成资料未覆盖](BUG-017-tool-error-is-not-missing-coverage.md)
- [BUG-018：JSON 语法与回复结构失败没有分开定位](BUG-018-reply-json-boundary-diagnostics.md)
- [BUG-019：资料缺口成为财务数字校验的旁路](BUG-019-financial-gap-validation.md)
- [BUG-020：细碎模型分块在背压下乱序](BUG-020-native-stream-backpressure-order.md)
