package com.stock.system.agent.controller;

import com.stock.system.agent.service.ResearchExperimentBudget;
import com.stock.system.common.api.Result;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;

/** 仅 test 探针，确认真实验收沿旧实验预算；不输出文件路径或模型凭据。 */
@RestController
@Profile("test")
@ConditionalOnProperty(name = "stock-system.agent.experiment-ledger")
public class ResearchAgentExperimentController {
    private final ResearchExperimentBudget budget;
    private final boolean officialEndpoint;
    public ResearchAgentExperimentController(ResearchExperimentBudget budget,
            @Value("${spring.ai.deepseek.base-url:https://api.deepseek.com}") String endpoint) {
        this.budget = budget;
        this.officialEndpoint = endpoint.equals("https://api.deepseek.com") || endpoint.equals("https://api.deepseek.com/");
    }
    @GetMapping("/api/test/agent-budget") public Result<Probe> state() { return Result.success(new Probe(budget.state(), officialEndpoint)); }
    public record Probe(ResearchExperimentBudget.State budget, boolean officialEndpoint) {}
}
