package com.stock.system.agent.service;

import com.stock.system.agent.dto.ResearchSummary;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.stock.service.StockResearchSourceService;
import com.stock.system.stock.dto.ResearchSourceFilter;
import com.stock.system.stock.vo.ResearchCycleSourcesResponseVO;
import com.stock.system.stock.vo.ResearchCycleSourcesResponseVO.Coverage;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 正式梳理核心，负责资料、校验与来源复核；外层运行入口拥有预算和个人会话状态。 */
@Service
public class ResearchSummaryService {
    private final StockResearchSourceService sourceService;
    private final ResearchSummaryPrompt prompt;
    private final ResearchSummaryValidator validator;
    private final ObjectProvider<@NonNull ResearchSummaryModel> models;
    private final ResearchMarketDataService marketData;

    public ResearchSummaryService(StockResearchSourceService sourceService, ResearchSummaryPrompt prompt,
                                  ResearchSummaryValidator validator, ObjectProvider<@NonNull ResearchSummaryModel> models,
                                  ResearchMarketDataService marketData) {
        this.sourceService = sourceService;
        this.prompt = prompt;
        this.validator = validator;
        this.models = models;
        this.marketData = marketData;
    }

    // 禁止外层数据库事务包住模型网络调用；资料读取和重校验各自使用短读事务。
    @Transactional(propagation = Propagation.NEVER)
    public Result summarize(Long userId, Long workspaceId, Long workspaceStockId, Long researchCycleId) {
        ResearchCycleSourcesResponseVO sources = sourceService.readCycle(
                userId, workspaceId, workspaceStockId, researchCycleId);
        return summarizeSources(userId, workspaceId, workspaceStockId, researchCycleId, sources);
    }

    @Transactional(propagation = Propagation.NEVER)
    public Result summarize(Long userId, Long workspaceId, Long workspaceStockId, Long researchCycleId,
                            ResearchSourceFilter filter) {
        return summarizeSources(userId, workspaceId, workspaceStockId, researchCycleId,
                sourceService.readCycle(userId, workspaceId, workspaceStockId, researchCycleId, filter));
    }

    private Result summarizeSources(Long userId, Long workspaceId, Long workspaceStockId, Long researchCycleId,
                                    ResearchCycleSourcesResponseVO sources) {
        if (sources.scope().coverage() != Coverage.COMPLETE) {
            throw new BusinessException(ErrorCode.RESEARCH_AGENT_SOURCE_LIMIT_EXCEEDED);
        }
        ResearchSummaryModel model = models.getIfAvailable();
        if (model == null) {
            throw new BusinessException(ErrorCode.RESEARCH_AGENT_DISABLED);
        }
        // 先保护消息大小，再读取报价；正式模型输入使用与最终页面相同的报价快照。
        prompt.build(sources);
        // 仅借用外层 try-with-resources 拥有的上下文，不能在这里关闭。
        //noinspection resource
        var execution = ResearchModelExecution.current();
        if (execution != null) execution.stage(ResearchAgentProgress.Stage.QUERYING);
        ResearchMarketDataService.Quote quote = marketData.readLatestQuote(userId, workspaceId, workspaceStockId);
        if (execution != null) execution.remainingMillis();
        ResearchSummaryPrompt.Input input = prompt.build(sources, quote);
        ResearchSummaryModel.Result generated;
        if (sources.opinions().isEmpty()) {
            generated = new ResearchSummaryModel.Result(new ResearchSummary(
                    new ResearchSummary.Item("本次范围内没有有效观点，暂时无法梳理。", List.of()),
                    List.of(), List.of(), List.of()), null, 0, 0);
        } else {
            if (execution != null && execution.streamsBody()) {
                boolean[] publish = {true};
                var draft = new ResearchSummaryDraft(candidate -> {
                    if (!publish[0]) return;
                    execution.remainingMillis();
                    sourceService.revalidate(userId, workspaceId, workspaceStockId, researchCycleId, sources);
                    ResearchSummary checkedDraft;
                    try { checkedDraft = validator.validateDraft(candidate, input.sources()); }
                    catch (BusinessException invalid) { publish[0] = false; execution.draft(List.of()); return; }
                    execution.remainingMillis();
                    var paragraphs = new java.util.ArrayList<String>();
                    paragraphs.add("研究概览：" + checkedDraft.overview().text());
                    checkedDraft.changes().forEach(item -> paragraphs.add("观点变化：" + item.text()));
                    checkedDraft.members().forEach(member -> {
                        String name = input.sources().stream().filter(source -> source.authorId().equals(member.authorId()))
                                .findFirst().orElseThrow().opinion().authorNickname();
                        paragraphs.add("成员观点（" + name + "）：" + member.text());
                    });
                    checkedDraft.pendingItems().forEach(item -> paragraphs.add("待核对事项：" + item.text()));
                    execution.draft(List.copyOf(paragraphs));
                }, () -> { publish[0] = false; execution.draft(List.of()); });
                generated = model.stream(input, draft::accept);
            } else generated = model.generate(input);
        }
        if (execution != null) execution.stage(ResearchAgentProgress.Stage.VALIDATING);
        ResearchSummary checked;
        try { checked = validator.validate(generated.summary(), input.sources()); }
        catch (BusinessException failure) {
            throw ResearchModelFailure.summary(failure, "SUMMARY_REPLY_CONTRACT",
                    failure instanceof ResearchSummaryValidator.Rejected rejected ? rejected.rule() : null);
        }
        StockResearchSourceService.SourceValidity validity = sourceService.revalidate(
                userId, workspaceId, workspaceStockId, researchCycleId, sources);
        return new Result(checked, sources.scope(), input.sources(), validity.newOpinionsAvailable(),
                LocalDateTime.now(), ResearchSummaryPrompt.VERSION, generated.model(),
                generated.inputTokens(), generated.outputTokens(), quote);
    }

    /** HTTP 运行入口已核对并预留预算的同一份有界资料；模型等待期间没有数据库事务。 */
    @Transactional(propagation = Propagation.NEVER)
    public Result summarizePrepared(Long userId, Long workspaceId, Long workspaceStockId, Long researchCycleId,
                                    ResearchCycleSourcesResponseVO sources) {
        return summarizeSources(userId, workspaceId, workspaceStockId, researchCycleId, sources);
    }

    public boolean enabled() { return models.getIfAvailable() != null; }

    public record Result(ResearchSummary summary, ResearchCycleSourcesResponseVO.Scope scope,
                         List<ResearchSummaryPrompt.SourceBinding> sources, boolean newOpinionsAvailable,
                         LocalDateTime generatedAt, String promptVersion, String model,
                         Integer inputTokens, Integer outputTokens, ResearchMarketDataService.Quote quote) {}
}
