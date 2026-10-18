package com.stock.system.agent.service;

import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.RestClientResponseException;

import java.net.*;
import java.net.http.HttpTimeoutException;
import java.util.*;

/** Allowlisted diagnostics only: never persist exception messages, response bodies, headers or credentials. */
public final class ResearchModelFailure {
    private static final Logger LOG = LoggerFactory.getLogger(ResearchModelFailure.class);
    private ResearchModelFailure() {}

    public static BusinessException translate(RuntimeException failure, String stage) {
        var execution = ResearchModelExecution.current();
        Integer status = execution == null ? null : execution.upstreamStatus();
        String category = "UNCLASSIFIED";
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && visited.size() < 12 && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof RestClientResponseException http) status = http.getStatusCode().value();
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) { category = "TIMEOUT"; break; }
            if (cause instanceof ConnectException || cause instanceof UnknownHostException || cause instanceof SocketException)
                category = "CONNECTION";
            if (cause instanceof javax.net.ssl.SSLException) category = "TLS";
            if (cause instanceof HttpMessageConversionException || cause instanceof tools.jackson.core.JacksonException)
                category = "RESPONSE_DECODE";
        }
        if ("UNCLASSIFIED".equals(category) && status != null && status >= 400)
            category = status == 429 ? "RATE_LIMIT" : status == 401 || status == 403 ? "UPSTREAM_AUTH" : "UPSTREAM_HTTP";
        if (failure instanceof BusinessException business) category = business.getErrorCode().name();
        var diagnostic = new Diagnostic(stage, category, status, execution == null ? 0 : execution.modelCalls,
                execution == null ? null : execution.runId(), execution == null ? null : execution.requestId());
        if (execution != null) execution.failure = diagnostic;
        LOG.warn("Research model failure run={} request={} call={} stage={} category={} upstreamStatus={}",
                diagnostic.runId(), diagnostic.requestId(), diagnostic.modelCall(), stage, category, status);
        if (failure instanceof BusinessException business) return business;
        return new BusinessException("TIMEOUT".equals(category) ? ErrorCode.RESEARCH_AGENT_TIMEOUT : ErrorCode.RESEARCH_AGENT_UNAVAILABLE);
    }
    public record Diagnostic(String stage, String category, Integer upstreamStatus, int modelCall, Long runId, String requestId) {}
}
