package com.stock.system.agent;

import com.stock.system.agent.service.ResearchExperimentBudget;
import com.stock.system.agent.service.ResearchModelExecution;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class ResearchExperimentBudgetTest {
    @TempDir Path directory;
    @Test void sharedLedgerCountsOldSpendKeepsUnknownUsageAndRefusesBeyondTenAfterRestart() throws Exception {
        Path ledger = directory.resolve("budget.jsonl");
        Files.writeString(ledger, "{\"event\":\"reserve\",\"requestId\":\"old\",\"amountCny\":\"4.016\"}\n"
                + "{\"event\":\"settle\",\"requestId\":\"old\",\"amountCny\":\"0.08202\"}\n");
        var mapper = JsonMapper.builder().build();
        try (var budget = new ResearchExperimentBudget(mapper, ledger.toString())) {
            assertThat(budget.state().spentUpperYuan()).isEqualByComparingTo("0.08202");
            budget.reserve("unknown-one", 100);
            assertThatThrownBy(() -> new ResearchExperimentBudget(mapper, ledger.toString())).isInstanceOf(IllegalStateException.class);
        }
        try (var budget = new ResearchExperimentBudget(mapper, ledger.toString())) {
            budget.reserve("unknown-two", 100);
            assertThatThrownBy(() -> budget.reserve("third", 100)).isInstanceOfSatisfying(BusinessException.class,
                    failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.RESEARCH_AGENT_BUDGET_EXCEEDED));
            assertThat(budget.state().unknownReservations()).isEqualTo(2);
        }
    }
    @Test void usageAcrossModelCallsAccumulatesAndUnknownFinalCallCannotReleaseReservation() {
        try (var execution = new ResearchModelExecution(3)) {
            execution.dispatch(); execution.usage("test", 100, 80);
            execution.dispatch(); execution.usage("test", 200, 20);
            assertThat(execution.inputTokens).isEqualTo(300);
            assertThat(execution.outputTokens).isEqualTo(100);
            assertThat(execution.usageKnown()).isTrue();
            execution.dispatch();
            assertThat(execution.usageKnown()).isFalse();
            assertThatThrownBy(execution::dispatch).isInstanceOf(BusinessException.class);
        }
    }
}
