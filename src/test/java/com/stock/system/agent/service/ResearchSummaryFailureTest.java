package com.stock.system.agent.service;

import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchSummaryFailureTest {
    @Test void summaryDiagnosticsOnlyRetainTheFixedRuleAndPreserveTheBusinessError() {
        try (var execution = new ResearchModelExecution()) {
            var failure = new BusinessException(ErrorCode.RESEARCH_AGENT_INVALID_RESULT);
            assertThat(ResearchModelFailure.summary(failure, "SUMMARY_REPLY_CONTRACT", ResearchSummaryValidator.Rule.UNKNOWN_SOURCE))
                    .isSameAs(failure);
            assertThat(execution.failure.validationRule()).isEqualTo("UNKNOWN_SOURCE");
            assertThat(new JsonMapper().writeValueAsString(execution.failure)).contains("UNKNOWN_SOURCE")
                    .doesNotContain("sourceIds", "text", "authorId");
        }
    }
}
