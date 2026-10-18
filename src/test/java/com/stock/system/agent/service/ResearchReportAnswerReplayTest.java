package com.stock.system.agent.service;

import com.stock.system.agent.report.ReportCatalog;
import com.stock.system.agent.vo.ResearchAgentResponseVO.Answer;
import com.stock.system.agent.vo.ResearchAgentResponseVO.Evidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Replays saved, authorized experiment output locally. Never sends a model or database request. */
@EnabledIfEnvironmentVariable(named = "REPORT_ANSWER_REPLAY", matches = ".+")
class ResearchReportAnswerReplayTest {
    @Test void replaysSixRealAnswersThroughTheExcerptAndFinancialGuardsWithoutNewModelCalls() throws Exception {
        Path target = Path.of("target").toAbsolutePath().normalize();
        Path input = Path.of(System.getenv("REPORT_ANSWER_REPLAY")).toAbsolutePath().normalize();
        assertThat(input.startsWith(target)).isTrue();
        assertThat(Files.size(input)).isLessThan(512 * 1024);
        var json = JsonMapper.builder().findAndAddModules().build();
        byte[] bytes = Files.readAllBytes(input);
        var root = (ObjectNode) json.readTree(bytes);
        assertThat(root.path("mode").asString()).isEqualTo("real");
        assertThat(root.path("results").size()).isEqualTo(6);
        var catalog = new ReportCatalog();
        int excerpts = 0;
        for (var result : root.path("results")) {
            assertThat(result.path("run").path("status").asString()).isEqualTo("SUCCEEDED");
            Answer answer = json.treeToValue(result.path("run").path("answer"), Answer.class);
            Map<String, Evidence> evidence = answer.followUp().evidence().stream()
                    .collect(Collectors.toMap(Evidence::sourceId, Function.identity()));
            for (var source : evidence.values()) if (source.reportPassage() != null)
                assertThat(catalog.forSymbol(source.reportPassage().symbol())).contains(source.reportPassage());
            var grounded = ResearchReportAnswerSupport.ground(answer.followUp().reply(), evidence);
            for (var paragraph : grounded.paragraphs()) {
                var cited = paragraph.sourceIds().stream().map(evidence::get).filter(Objects::nonNull).toList();
                ResearchFinancialAnswerNumbers.validate(paragraph.text(), cited, List.of());
                for (var source : cited) if (source.reportPassage() != null) {
                    assertThat(paragraph.sourceIds()).containsExactly(source.sourceId());
                    assertThat(paragraph.text()).endsWith(source.reportPassage().text());
                    excerpts++;
                }
            }
            ((ObjectNode) result.path("run").path("answer").path("followUp")).set("reply", json.valueToTree(grounded));
        }
        assertThat(excerpts).isGreaterThanOrEqualTo(3);
        var replay = root.putObject("replay");
        replay.put("inputPath", input.toString()).put("inputSha256", ReportCatalog.sha256(bytes))
                .put("outputWorkflowVersion", ResearchQuestionService.VERSION).put("newModelCalls", 0)
                .put("newPaidRequests", 0).put("sourceIdentityVerified", true).put("reportExcerpts", excerpts)
                .put("semanticReview", "PENDING_CODEX_SOURCE_COMPARISON")
                .put("newRealModelRun", false).put("modelParaphraseQualityClaimed", false);
        Files.writeString(target.resolve("report-answer-support-replay.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }
}
