package com.stock.system.agent.service;

/** 两种模型输出共用完整 JSON 外层处理；不修补内容、不提取混杂说明中的片段。 */
final class ResearchModelJson {
    private ResearchModelJson() {}

    static String body(String text) {
        if (text == null || text.length() > 32_000) throw new IllegalArgumentException();
        String body = text.strip();
        if (body.startsWith("\uFEFF")) body = body.substring(1).stripLeading();
        if (!body.startsWith("```")) return body;
        int openingEnd = body.indexOf('\n');
        int closingStart = body.length() - 3;
        if (openingEnd < 0 || !body.endsWith("```") || closingStart <= openingEnd
                || body.charAt(closingStart - 1) != '\n') throw new IllegalArgumentException();
        String language = body.substring(3, openingEnd).strip();
        if (!language.isEmpty() && !language.equalsIgnoreCase("json")) throw new IllegalArgumentException();
        return body.substring(openingEnd + 1, closingStart).strip();
    }
}
