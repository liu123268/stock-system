package com.stock.system.agent.dto;

import java.util.List;

/** 追问只回答本次问题，不重复首次梳理的四块结构。 */
public record ResearchReply(List<ResearchSummary.Item> paragraphs, List<String> missingInformation) {}
