package com.stock.system.agent;

import com.stock.system.agent.dto.ResearchSummary;
import com.stock.system.stock.vo.ResearchCycleSourcesResponseVO;
import com.stock.system.stock.vo.ResearchCycleSourcesResponseVO.OpinionSource;

import java.time.LocalDateTime;
import java.util.List;

/** 仅用于正式组件测试的自造资料，不是证券事实。 */
final class ResearchSummaryFixtures {
    static final LocalDateTime DATE = LocalDateTime.of(2026, 10, 13, 9, 0);

    static ResearchCycleSourcesResponseVO sources() {
        return sources(List.of(
                opinion(1L, 11L, "成员甲", "BULLISH", "认为新订单可能增长。", DATE),
                opinion(2L, 11L, "成员甲", "NEUTRAL", "没有公告确认，改为等待。", DATE.plusDays(1)),
                opinion(3L, 22L, "成员乙", "BEARISH", "担心利润率下降。", DATE.plusDays(1))));
    }

    static ResearchCycleSourcesResponseVO sources(List<OpinionSource> opinions) {
        long textCount = opinions.stream().mapToLong(source -> source.reason().codePointCount(
                0, source.reason().length())).sum();
        return new ResearchCycleSourcesResponseVO(20L, "SAMPLE.HK", "自造测试股票", 30L,
                "ACTIVE", DATE, null, new ResearchCycleSourcesResponseVO.Scope(DATE.plusDays(2),
                opinions.size(), textCount, opinions.size(), ResearchCycleSourcesResponseVO.Coverage.COMPLETE,
                100, 20_000), opinions);
    }

    static OpinionSource opinion(Long id, Long authorId, String nickname, String stance,
                                 String reason, LocalDateTime date) {
        return new OpinionSource(id, authorId, nickname, "UPDATE", stance,
                reason, null, "MEDIUM", null, null, date);
    }

    static ResearchSummary summary() {
        return new ResearchSummary(new ResearchSummary.Item("成员甲转为等待，成员乙担心利润率。",
                List.of("op-2", "op-3")),
                List.of(new ResearchSummary.Item("成员甲由看好转为中立。", List.of("op-1", "op-2"))),
                List.of(new ResearchSummary.Member("M1", "成员甲等待公告确认。", List.of("op-2")),
                        new ResearchSummary.Member("M2", "成员乙担心利润率下降。", List.of("op-3"))),
                List.of(new ResearchSummary.Item("本次未读取公告，无法确认订单是否实际增长。", List.of())));
    }
}
