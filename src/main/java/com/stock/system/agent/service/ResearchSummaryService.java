package com.stock.system.agent.service;

import com.stock.system.agent.dto.ResearchSummary;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.stock.service.StockResearchSourceService;
import com.stock.system.stock.vo.ResearchCycleSourcesResponseVO;
import com.stock.system.stock.vo.ResearchCycleSourcesResponseVO.Coverage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 正式业务生成核心；HTTP 入口须在运行账本、预算和个人会话保护落地后接入。 */
@Service
public class ResearchSummaryService {
    private final StockResearchSourceService sourceService;
    private final ResearchSummaryPrompt prompt;
    private final ResearchSummaryValidator validator;
    private final ObjectProvider<ResearchSummaryModel> models;

    public ResearchSummaryService(StockResearchSourceService sourceService, ResearchSummaryPrompt prompt,
                                  ResearchSummaryValidator validator, ObjectProvider<ResearchSummaryModel> models) {
        this.sourceService = sourceService;
        this.prompt = prompt;
        this.validator = validator;
        this.models = models;
    }

    // 禁止外层数据库事务包住模型网络调用；资料读取和重校验各自使用短读事务。
    @Transactional(propagation = Propagation.NEVER)
    public Result summarize(Long userId, Long workspaceId, Long workspaceStockId, Long researchCycleId) {
        ResearchCycleSourcesResponseVO sources = sourceService.readCycle(
                userId, workspaceId, workspaceStockId, researchCycleId);
        if (sources.scope().coverage() != Coverage.COMPLETE) {
            throw new BusinessException(ErrorCode.RESEARCH_AGENT_SOURCE_LIMIT_EXCEEDED);
        }
        ResearchSummaryModel model = models.getIfAvailable();
        if (model == null) {
            throw new BusinessException(ErrorCode.RESEARCH_AGENT_DISABLED);
        }
        ResearchSummaryPrompt.Input input = prompt.build(sources);
        ResearchSummaryModel.Result generated;
        if (sources.opinions().isEmpty()) {
            generated = new ResearchSummaryModel.Result(new ResearchSummary(
                    new ResearchSummary.Item("本次范围内没有有效观点，暂时无法梳理。", List.of()),
                    List.of(), List.of(), List.of()), null, 0, 0);
        } else {
            generated = model.generate(input);
        }
        ResearchSummary checked = validator.validate(generated.summary(), input.sources());
        StockResearchSourceService.SourceValidity validity = sourceService.revalidate(
                userId, workspaceId, workspaceStockId, researchCycleId, sources);
        return new Result(checked, sources.scope(), input.sources(), validity.newOpinionsAvailable(),
                LocalDateTime.now(), ResearchSummaryPrompt.VERSION, generated.model(),
                generated.inputTokens(), generated.outputTokens());
    }

    public record Result(ResearchSummary summary, ResearchCycleSourcesResponseVO.Scope scope,
                         List<ResearchSummaryPrompt.SourceBinding> sources, boolean newOpinionsAvailable,
                         LocalDateTime generatedAt, String promptVersion, String model,
                         Integer inputTokens, Integer outputTokens) {}
}
