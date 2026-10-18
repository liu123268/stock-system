package com.stock.system.agent.service;

import com.stock.system.agent.dto.ResearchReply;
import com.stock.system.agent.dto.ResearchSummary;
import com.stock.system.agent.vo.ResearchAgentResponseVO.Evidence;

import java.util.*;

/** Report facts are delivered as reviewed excerpts so model paraphrases cannot widen their scope. */
final class ResearchReportAnswerSupport {
    private ResearchReportAnswerSupport() {}

    static ResearchReply ground(ResearchReply reply, Map<String, Evidence> evidence) {
        var paragraphs = new ArrayList<ResearchSummary.Item>();
        var quoted = new HashSet<String>();
        for (var paragraph : reply.paragraphs()) {
            var reports = paragraph.sourceIds().stream().map(evidence::get).filter(Objects::nonNull)
                    .filter(source -> source.reportPassage() != null).toList();
            if (reports.isEmpty()) paragraphs.add(paragraph);
            else {
                if (reports.size() != paragraph.sourceIds().size())
                    throw new IllegalArgumentException("Report excerpts must be separate from other evidence types");
                for (var source : reports) {
                    if (!quoted.add(source.sourceId())) continue;
                    var passage = source.reportPassage();
                    String text = "《" + passage.reportTitle() + "》第" + passage.pdfPage() + "页原文：" + passage.text();
                    if (text.codePointCount(0, text.length()) > 2000) throw new IllegalArgumentException("Report excerpt is too long");
                    paragraphs.add(new ResearchSummary.Item(text, List.of(source.sourceId())));
                }
            }
        }
        if (paragraphs.isEmpty() || paragraphs.size() > 8) throw new IllegalArgumentException("Too many grounded paragraphs");
        return new ResearchReply(List.copyOf(paragraphs), reply.missingInformation());
    }
}
