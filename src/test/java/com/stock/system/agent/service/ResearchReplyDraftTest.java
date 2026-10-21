package com.stock.system.agent.service;

import com.stock.system.agent.dto.ResearchReply;
import com.stock.system.agent.dto.ResearchSummary.Item;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class ResearchReplyDraftTest {
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private final List<List<Item>> snapshots = new ArrayList<>();
    private final AtomicInteger rejected = new AtomicInteger();
    private final ResearchReplyDraft draft = new ResearchReplyDraft(json, snapshots::add, rejected::incrementAndGet);

    @Test void escapedQuotesBracesAndSplitUnicodeAreOnlyPublishedAsCompleteObjects() {
        var first = new Item("中文😀，原文说：\"{尚待核对}\"，反斜线\\。", List.of("op-1"));
        var second = new Item("第二段，继续核对。", List.of());
        String body = json.writeValueAsString(new ResearchReply(List.of(first, second), List.of("尚缺原文。")));
        for (int i = 0; i < body.length(); i++) draft.accept(body.substring(i, i + 1));
        assertThat(snapshots).containsExactly(List.of(first), List.of(first, second));
        assertThat(rejected).hasValue(0);
    }

    @ParameterizedTest @ValueSource(strings = {"```json\n%s\n```", "\uFEFF%s", "```\r\n%s\r\n```"})
    void completeJsonEnvelopesDoNotExposeTheirMarkup(String envelope) {
        var item = new Item("完整段落。", List.of());
        String body = envelope.formatted(json.writeValueAsString(new ResearchReply(List.of(item), List.of())));
        for (int i = 0; i < body.length(); i++) draft.accept(body.substring(i, i + 1));
        assertThat(snapshots).containsExactly(List.of(item));
    }

    @Test void truncatedObjectsAndAlternativeFieldOrderFallBackToTheFinalAnswer() {
        draft.accept("{\"paragraphs\":[{\"text\":\"未完成");
        assertThat(snapshots).isEmpty();
        var alternate = new ResearchReplyDraft(json, snapshots::add, rejected::incrementAndGet);
        alternate.accept("{\"missingInformation\":[],\"paragraphs\":[{\"text\":\"合法但不提前交付\",\"sourceIds\":[]}]}");
        assertThat(snapshots).isEmpty();
    }

    @Test void invalidObjectsAndAHiddenNinthParagraphRetractTheDraft() {
        draft.accept("{\"paragraphs\":[{\"text\":\"段落\",\"sourceIds\":[],\"unexpected\":true}");
        assertThat(snapshots).isEmpty();
        assertThat(rejected).hasValue(1);
        var many = new ResearchReplyDraft(json, snapshots::add, rejected::incrementAndGet);
        many.accept(json.writeValueAsString(new ResearchReply(java.util.Collections.nCopies(9, new Item("段落", List.of())), List.of())));
        assertThat(snapshots).hasSize(8);
        assertThat(rejected).hasValue(2);
    }
}
