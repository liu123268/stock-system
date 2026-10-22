package com.stock.system.agent.service;

import com.stock.system.agent.dto.ResearchSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchSummaryDraftTest {
    private final ArrayList<ResearchSummary> snapshots = new ArrayList<>();
    private final AtomicInteger rejected = new AtomicInteger();
    private final ResearchSummaryDraft draft = new ResearchSummaryDraft(snapshots::add, rejected::incrementAndGet);
    private static final String OVERVIEW = "\"overview\":{\"text\":\"中文😀和\\\"引号{[]}\\\"\",\"sourceIds\":[\"op-1\"]}";

    @Test void closedRootSectionsSurviveEveryCharacterBoundaryAndIgnoreBracketsInsideText() {
        String body = "{" + OVERVIEW + ",\"changes\":[],\"members\":[{\"authorId\":\"M1\",\"text\":\"观点\",\"sourceIds\":[\"op-1\"]}],\"pendingItems\":[]}";
        body.chars().forEach(value -> draft.accept(Character.toString((char)value)));
        assertThat(rejected).hasValue(0);
        assertThat(snapshots).hasSize(4);
        assertThat(snapshots.get(0).overview().text()).isEqualTo("中文😀和\"引号{[]}\"");
        assertThat(snapshots.get(0).members()).isEmpty();
        assertThat(snapshots.get(3).members()).hasSize(1);
    }

    @ParameterizedTest @ValueSource(strings = {"%s", "\uFEFF%s", "```json\n%s\n```"})
    void aKnownOverviewCanFollowOtherCompleteSections(String envelope) {
        draft.accept(envelope.formatted("{\"members\":[]," + OVERVIEW + ",\"pendingItems\":[],\"changes\":[]}"));
        assertThat(snapshots).hasSize(3);
        assertThat(snapshots.get(0).overview()).isNotNull();
        assertThat(rejected).hasValue(0);
    }

    @Test void anUnclosedMemberArrayDoesNotExposeItsEarlierCompleteItems() {
        draft.accept("{" + OVERVIEW + ",\"members\":[{\"authorId\":\"M1\",\"text\":\"首项\",\"sourceIds\":[]}");
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).members()).isEmpty();
    }

    @Test void unknownNestedFieldsAndRepeatedRootFieldsRetractThePreview() {
        draft.accept("{" + OVERVIEW + ",\"overview\":{}");
        assertThat(snapshots).hasSize(1);
        assertThat(rejected).hasValue(1);
        var another = new ResearchSummaryDraft(snapshots::add, rejected::incrementAndGet);
        another.accept("{\"overview\":{\"text\":\"不能显示\",\"sourceIds\":[],\"extra\":true}}");
        assertThat(snapshots).hasSize(1);
        assertThat(rejected).hasValue(2);
    }
}
