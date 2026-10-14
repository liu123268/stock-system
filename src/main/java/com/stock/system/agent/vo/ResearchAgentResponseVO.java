package com.stock.system.agent.vo;

import com.stock.system.agent.dto.ResearchSummary;
import com.stock.system.agent.dto.ResearchReply;
import com.stock.system.agent.service.ResearchMarketDataService;
import com.stock.system.agent.service.ResearchSummaryService;
import com.stock.system.stock.vo.ResearchCycleSourcesResponseVO;
import com.stock.system.stock.financial.FinancialFacts;
import java.time.LocalDateTime;
import java.util.List;

/** HTTP 只返回展示需要的引用索引，不直接序列化内部原文／账号绑定。 */
public final class ResearchAgentResponseVO {
    private ResearchAgentResponseVO() {}

    public record Reference(String sourceId, Long opinionId, String authorId, String authorName,
                            LocalDateTime createdAt) {}
    public record Answer(ResearchSummary summary, ResearchCycleSourcesResponseVO.Scope scope,
                         List<Reference> references, boolean newOpinionsAvailable, LocalDateTime generatedAt,
                         ResearchMarketDataService.Quote quote, FollowUp followUp) {
        public Answer(ResearchSummary summary, ResearchCycleSourcesResponseVO.Scope scope, List<Reference> references,
                      boolean newOpinionsAvailable, LocalDateTime generatedAt, ResearchMarketDataService.Quote quote) {
            this(summary, scope, references, newOpinionsAvailable, generatedAt, quote, null);
        }
        public static Answer from(ResearchSummaryService.Result result) {
            return new Answer(result.summary(), result.scope(), result.sources().stream().map(source ->
                    new Reference(source.sourceId(), source.opinion().id(), source.authorId(),
                            source.opinion().authorNickname(), source.opinion().createdAt())).toList(),
                    result.newOpinionsAvailable(), result.generatedAt(), result.quote());
        }
    }
    public record Evidence(String sourceId, String type, String label, ResearchMarketDataService.Quote quote,
                           ResearchMarketDataService.DailyPrices dailyPrices, FinancialFacts financialFacts) {
        public Evidence(String sourceId, String type, String label, ResearchMarketDataService.Quote quote,
                        ResearchMarketDataService.DailyPrices dailyPrices) {
            this(sourceId, type, label, quote, dailyPrices, null);
        }
    }
    public record FollowUp(String question, ResearchReply reply, List<Evidence> evidence, boolean historyOmitted) {}
    public record Run(Long runId, String requestId, String status, String errorCode, String message,
                      LocalDateTime startedAt, LocalDateTime finishedAt, Answer answer) {}
    public record Session(boolean enabled, long contextVersion, List<Run> messages, Run activeRun,
                          boolean hasEarlierMessages, boolean invalidated) {}
}
