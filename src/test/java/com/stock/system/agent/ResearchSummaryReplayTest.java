package com.stock.system.agent;

import com.stock.system.agent.dto.ResearchSummary;
import com.stock.system.agent.service.ResearchMarketDataService;
import com.stock.system.agent.service.ResearchSummaryPrompt;
import com.stock.system.agent.service.ResearchSummaryValidator;
import com.stock.system.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/** 真实自造样本坏例子，账号ID已替换成本地别名数字；不连接模型、HTTP或数据库。 */
class ResearchSummaryReplayTest {
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test void rejectsTheCapturedIdentifiersAndPreservesEvidenceOfTheQuoteContradiction() throws Exception {
        try (var stream = getClass().getResourceAsStream("/agent/summary-scope-regression.json")) {
            var payload = json.readTree(stream);
            var summary = json.treeToValue(payload.path("summary"), ResearchSummary.class);
            var sources = List.of(json.treeToValue(payload.path("sources"), ResearchSummaryPrompt.SourceBinding[].class));
            assertThat(payload.path("quote").path("status").asString()).isEqualTo("AVAILABLE");
            assertThat(summary.pendingItems()).anySatisfy(item -> assertThat(item.text()).contains("未提供", "行情资料"));
            assertThat(summary.changes().get(0).text()).contains("M1", "op-8949");
            assertThatThrownBy(() -> new ResearchSummaryValidator().validate(summary, sources)).isInstanceOf(BusinessException.class);
            assertThat(payload.path("sourceArtifactSha256").asString()).matches("[a-f0-9]{64}");
        }
    }

    @Test void revisedModelInputIncludesTheCapturedAvailableQuoteAndHumanDisplayNames() throws Exception {
        try (var stream = getClass().getResourceAsStream("/agent/summary-scope-regression.json")) {
            var payload = json.readTree(stream);
            var quote = json.treeToValue(payload.path("quote"), ResearchMarketDataService.Quote.class);
            var sources = List.of(json.treeToValue(payload.path("sources"), ResearchSummaryPrompt.SourceBinding[].class));
            var material = ResearchSummaryFixtures.sources(sources.stream().map(ResearchSummaryPrompt.SourceBinding::opinion).toList());
            var input = new ResearchSummaryPrompt(json).build(material, quote);
            var user = json.readTree(input.user().substring(input.user().indexOf('{')));
            assertThat(user.path("quote").path("status").asString()).isEqualTo("AVAILABLE");
            assertThat(user.path("quote").path("price").asInt()).isEqualTo(612);
            assertThat(user.path("authorNames").size()).isEqualTo(2);
            assertThat(user.path("authorNames").path("M1").asString()).isEqualTo("自造成员甲");
            assertThat(user.path("authorNames").path("M2").asString()).isEqualTo("自造成员乙");
            assertThat(input.user()).contains("自造成员甲", "自造成员乙");
            assertThat(input.system()).contains("不能因此写", "所有text正文使用authorName显示昵称")
                    .doesNotContain("没有提供公告、财报、新闻或行情资料");
        }
    }
}
