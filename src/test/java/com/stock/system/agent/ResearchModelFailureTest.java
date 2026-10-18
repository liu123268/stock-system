package com.stock.system.agent;

import com.stock.system.agent.service.*;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;
import java.net.SocketTimeoutException;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ResearchModelFailureTest {
    @Test void recordsSecondCallTimeoutWithoutFreeingUnknownUsageOrRetrying() {
        ChatModel chat = mock(ChatModel.class);
        var model = new ResearchQuestionModel(chat);
        when(chat.call(any(Prompt.class))).thenThrow(new ResourceAccessException("must-not-save sk-test-secret", new SocketTimeoutException("private-message")));
        try (var execution = new ResearchModelExecution(4)) {
            execution.bind(42, "00112233-4455-6677-8899-aabbccddeeff");
            execution.dispatch(); execution.usage("fixture", 1355, 167);
            execution.dispatch();
            assertThatThrownBy(() -> model.call(List.of(), List.of())).isInstanceOf(BusinessException.class)
                    .extracting(value -> ((BusinessException)value).getErrorCode()).isEqualTo(ErrorCode.RESEARCH_AGENT_TIMEOUT);
            assertThat(execution.failure.category()).isEqualTo("TIMEOUT");
            assertThat(execution.failure.modelCall()).isEqualTo(2);
            assertThat(execution.failure.runId()).isEqualTo(42);
            assertThat(execution.usageKnown()).isFalse();
            assertThat(execution.inputTokens).isEqualTo(1355);
            assertThat(new JsonMapper().writeValueAsString(execution.failure)).doesNotContain("sk-test-secret", "private-message");
            verify(chat, times(1)).call(any(Prompt.class));
        }
    }
    @Test void capturesHttpClassificationAndDropsTheProviderErrorBody() {
        try (var execution = new ResearchModelExecution()) {
            execution.dispatch(); execution.upstreamStatus(429);
            var error = ResearchModelFailure.translate(new IllegalStateException("provider-body-with-secret"), "QUESTION_MODEL");
            assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESEARCH_AGENT_UNAVAILABLE);
            assertThat(execution.failure.category()).isEqualTo("RATE_LIMIT");
            assertThat(execution.failure.upstreamStatus()).isEqualTo(429);
            assertThat(new JsonMapper().writeValueAsString(execution.failure)).doesNotContain("provider-body");
        }
    }
    @Test void classifiesAResponseConversionFailureEvenWhenHttpWasSuccessful() {
        try (var execution = new ResearchModelExecution(2)) {
            execution.dispatch(); execution.upstreamStatus(200);
            var cause = new org.springframework.http.converter.HttpMessageConversionException("private raw response");
            ResearchModelFailure.translate(new IllegalStateException(cause), "QUESTION_MODEL");
            assertThat(execution.failure.category()).isEqualTo("RESPONSE_DECODE");
            assertThat(execution.failure.upstreamStatus()).isEqualTo(200);
            execution.dispatch();
            assertThat(execution.upstreamStatus()).isNull();
        }
    }
    @Test void preservesExistingBusinessErrorsInsteadOfRelabelingAnExecutionLimit() {
        var failure = new BusinessException(ErrorCode.RESEARCH_AGENT_EXECUTION_LIMIT);
        try (var execution = new ResearchModelExecution()) {
            assertThat(ResearchModelFailure.translate(failure, "QUESTION_MODEL")).isSameAs(failure);
            assertThat(execution.failure.category()).isEqualTo("RESEARCH_AGENT_EXECUTION_LIMIT");
        }
    }
}
