package com.stock.system.agent.service;

import com.stock.system.agent.mapper.ResearchAgentRecordMapper;
import com.stock.system.agent.mapper.ResearchAgentRecordMapper.RunRow;
import com.stock.system.agent.mapper.ResearchAgentRecordMapper.Target;
import com.stock.system.agent.vo.ResearchAgentResponseVO.*;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.stock.dto.ResearchSourceFilter;
import com.stock.system.stock.service.StockResearchSourceService;
import com.stock.system.stock.vo.ResearchCycleSourcesResponseVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.UUID;

@Service
@Transactional(propagation = Propagation.NEVER)
public class ResearchAgentService {
    private final StockResearchSourceService sources;
    private final ResearchSummaryService summaries;
    private final ResearchAgentRecordMapper records;
    private final ObjectMapper json;

    public ResearchAgentService(StockResearchSourceService sources, ResearchSummaryService summaries,
                                ResearchAgentRecordMapper records, ObjectMapper json) {
        this.sources = sources;
        this.summaries = summaries;
        this.records = records;
        this.json = json;
    }

    public Session restore(Target target, Long beforeRunId) {
        authorize(target);
        if (beforeRunId != null && beforeRunId <= 0) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        var session = records.session(target);
        if (session == null) return new Session(enabled(), 0, java.util.List.of(), null, false, false);
        records.reconcile(target);
        var rows = records.messages(session.id(), session.version(), beforeRunId);
        var messages = new ArrayList<Run>();
        boolean invalidated = false;
        for (RunRow row : rows.stream().limit(6).toList()) {
            Run message = present(target, row);
            invalidated |= "RESEARCH_AGENT_SOURCES_CHANGED".equals(message.errorCode());
            messages.add(message);
        }
        var active = records.active(session.id(), session.version());
        return new Session(enabled(), session.version(), java.util.List.copyOf(messages),
                active == null ? null : present(target, active), rows.size() > 6, invalidated);
    }

    public Run summarize(Target target, String requestId, ResearchSourceFilter filter) {
        authorize(target);
        String normalized = normalize(requestId);
        String hash = hash(target + ":" + json.writeValueAsString(filter));
        RunRow existing = records.request(target.user(), normalized);
        if (existing != null) return reuse(target, existing, hash);
        if (!enabled()) throw new BusinessException(ErrorCode.RESEARCH_AGENT_DISABLED);
        // 总时限从资料读取开始，不从模型调用开始。
        try (var execution = new ResearchModelExecution()) {
            var material = sources.readCycle(target.user(), target.workspace(), target.stock(), target.cycle(), filter);
            if (material.scope().coverage() != ResearchCycleSourcesResponseVO.Coverage.COMPLETE)
                throw new BusinessException(ErrorCode.RESEARCH_AGENT_SOURCE_LIMIT_EXCEEDED);
            execution.remainingMillis();
            var started = records.begin(target, normalized, hash, !material.opinions().isEmpty());
            if (!started.created()) return reuse(target, started.run(), hash);
            Answer answer = null;
            ErrorCode error = null;
            try {
                var result = summaries.summarizePrepared(target.user(), target.workspace(), target.stock(), target.cycle(), material);
                answer = Answer.from(result);
                execution.remainingMillis();
            } catch (BusinessException failure) { error = failure.getErrorCode(); }
            catch (RuntimeException failure) { error = ErrorCode.RESEARCH_AGENT_UNAVAILABLE; }
            records.finish(target, started.run(), execution, material, answer, error, () -> sources.revalidate(
                    target.user(), target.workspace(), target.stock(), target.cycle(), material));
            return present(target, records.run(target, started.run().id()));
        }
    }

    public Run status(Target target, long runId) {
        authorize(target);
        if (records.session(target) != null) records.reconcile(target);
        return present(target, records.run(target, runId));
    }

    public void clear(Target target) {
        authorize(target);
        records.clear(target, () -> { authorize(target); return null; });
    }

    private Run reuse(Target target, RunRow row, String hash) {
        if (!row.hash().equals(hash)) throw new BusinessException(ErrorCode.RESEARCH_AGENT_REQUEST_CONFLICT);
        return present(target, row);
    }

    private Run present(Target target, RunRow row) {
        String status = row.version() == row.currentVersion() ? row.status() : "CLEARED";
        String error = row.version() == row.currentVersion() ? row.errorCode() : ErrorCode.RESEARCH_AGENT_CLEARED.name();
        Answer answer = null;
        if (status.equals("SUCCEEDED") && row.answerJson() != null) {
            var dependencies = json.readValue(row.dependenciesJson(), ResearchCycleSourcesResponseVO.class);
            try {
                var validity = sources.revalidate(target.user(), target.workspace(), target.stock(), target.cycle(), dependencies);
                var stored = json.readValue(row.answerJson(), Answer.class);
                answer = new Answer(stored.summary(), stored.scope(), stored.references(),
                        stored.newOpinionsAvailable() || validity.newOpinionsAvailable(), stored.generatedAt(), stored.quote());
            } catch (BusinessException failure) {
                // 原文撤回影响整个回答，不能只隐藏引用按钮。权限失效直接拒绝整个读取。
                if (failure.getErrorCode() != ErrorCode.RESEARCH_AGENT_SOURCES_CHANGED) throw failure;
                status = "INVALIDATED";
                error = failure.getErrorCode().name();
            }
        }
        return new Run(row.id(), row.requestId(), status, error,
                error == null ? null : ErrorCode.valueOf(error).message(), row.startedAt(), row.finishedAt(), answer);
    }

    private boolean enabled() { return summaries.enabled() && records.funded(); }
    private void authorize(Target target) {
        sources.verifyReadAccess(target.user(), target.workspace(), target.stock(), target.cycle());
    }
    private String normalize(String id) {
        try {
            if (id == null || id.length() != 36) throw new IllegalArgumentException();
            String normalized = UUID.fromString(id).toString();
            if (!normalized.equalsIgnoreCase(id)) throw new IllegalArgumentException();
            return normalized;
        } catch (IllegalArgumentException failure) { throw new BusinessException(ErrorCode.INVALID_REQUEST); }
    }
    private String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
