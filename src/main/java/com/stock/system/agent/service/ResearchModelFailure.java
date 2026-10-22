package com.stock.system.agent.service;

import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.*;
import java.net.http.HttpTimeoutException;
import java.util.*;

/** Allowlisted diagnostics only: never persist exception messages, response bodies, headers or credentials. */
public final class ResearchModelFailure {
    private static final Logger LOG = LoggerFactory.getLogger(ResearchModelFailure.class);
    private ResearchModelFailure() {}

    public static BusinessException translate(RuntimeException failure, String stage) {
        return translate(failure, stage, null);
    }
    static BusinessException translate(RuntimeException failure, String stage, ResearchFinancialAnswerNumbers.Rule validationRule) {
        return translated(failure, stage, validationRule == null ? null : validationRule.name());
    }
    static BusinessException summary(RuntimeException failure, String stage, ResearchSummaryValidator.Rule validationRule) {
        return translated(failure, stage, validationRule == null ? null : validationRule.name());
    }
    private static BusinessException translated(RuntimeException failure, String stage, String validationRule) {
        // 当前上下文由运行入口负责关闭；错误翻译只读取并补充诊断。
        //noinspection resource
        var execution = ResearchModelExecution.current();
        Integer status = execution == null ? null : execution.upstreamStatus();
        String category = "UNCLASSIFIED";
        BusinessException businessFailure = null;
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && visited.size() < 12 && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof BusinessException business) businessFailure = business;
            if (cause instanceof RestClientResponseException http) status = http.getStatusCode().value();
            if (cause instanceof WebClientResponseException http) status = http.getStatusCode().value();
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) { category = "TIMEOUT"; break; }
            if (cause instanceof UnknownHostException || cause instanceof SocketException)
                category = "CONNECTION";
            if (cause instanceof javax.net.ssl.SSLException) category = "TLS";
            if (cause instanceof HttpMessageConversionException || cause instanceof tools.jackson.core.JacksonException)
                category = "RESPONSE_DECODE";
        }
        if ("UNCLASSIFIED".equals(category) && status != null && status >= 400)
            category = status == 429 ? "RATE_LIMIT" : status == 401 || status == 403 ? "UPSTREAM_AUTH" : "UPSTREAM_HTTP";
        if (businessFailure != null) category = businessFailure.getErrorCode().name();
        var diagnostic = new Diagnostic(stage, category, status, execution == null ? 0 : execution.modelCalls,
                execution == null ? null : execution.runId(), execution == null ? null : execution.requestId(),
                validationRule);
        if (execution != null) execution.failure = diagnostic;
        LOG.warn("Research model failure run={} request={} call={} stage={} category={} upstreamStatus={} validationRule={}",
                diagnostic.runId(), diagnostic.requestId(), diagnostic.modelCall(), stage, category, status, diagnostic.validationRule());
        if (businessFailure != null) return businessFailure;
        return new BusinessException("TIMEOUT".equals(category) ? ErrorCode.RESEARCH_AGENT_TIMEOUT : ErrorCode.RESEARCH_AGENT_UNAVAILABLE);
    }
    public record Diagnostic(String stage, String category, Integer upstreamStatus, int modelCall, Long runId, String requestId,
                             @JsonInclude(JsonInclude.Include.NON_NULL) String validationRule) {
        public Diagnostic(String stage, String category, Integer upstreamStatus, int modelCall, Long runId, String requestId) {
            this(stage, category, upstreamStatus, modelCall, runId, requestId, null);
        }
    }
}
