package com.stock.system.agent.service;

import com.stock.system.agent.dto.ResearchSummary;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** 仅提取已闭合的段落对象。正文片段不等于业务校验成功，必须经调用方校验后才能推送。 */
final class ResearchReplyDraft {
    private static final Pattern START = Pattern.compile("^\\s*\\{\\s*\"paragraphs\"\\s*:\\s*\\[");
    private final ObjectMapper json;
    private final Consumer<List<ResearchSummary.Item>> complete;
    private final Runnable rejected;
    private final StringBuilder body = new StringBuilder();
    private final List<ResearchSummary.Item> paragraphs = new ArrayList<>();
    private int cursor = -1, start = -1, depth;
    private boolean quoted, escaped, separator, done;

    ResearchReplyDraft(ObjectMapper json, Consumer<List<ResearchSummary.Item>> complete, Runnable rejected) {
        this.json = json; this.complete = complete; this.rejected = rejected;
    }

    void accept(String delta) {
        if (done) return;
        body.append(delta);
        if (body.length() > 32_000) { reject(); return; }
        if (cursor < 0) {
            String prefix = body.toString().stripLeading();
            if (prefix.startsWith("\uFEFF")) prefix = prefix.substring(1).stripLeading();
            if (prefix.startsWith("```")) {
                int end = prefix.indexOf('\n');
                if (end < 0) return;
                String language = prefix.substring(3, end).strip();
                if (!language.isEmpty() && !language.equalsIgnoreCase("json")) return;
                prefix = prefix.substring(end + 1);
            }
            var root = START.matcher(prefix);
            if (!root.find()) return; // 其他合法属性顺序仍在最终完整解析后交付。
            cursor = body.length() - prefix.length() + root.end();
        }
        while (cursor < body.length() && !done) {
            char current = body.charAt(cursor++);
            if (start < 0) {
                if (Character.isWhitespace(current)) continue;
                if (current == ']') { done = true; return; }
                if (separator) {
                    if (current != ',') { reject(); return; }
                    separator = false; continue;
                }
                if (current != '{' || paragraphs.size() >= 8) { reject(); return; }
                start = cursor - 1; depth = 1; quoted = false; escaped = false; continue;
            }
            if (quoted) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') quoted = false;
            } else if (current == '"') quoted = true;
            else if (current == '{') depth++;
            else if (current == '}' && --depth == 0) {
                ResearchSummary.Item item;
                try { item = json.readValue(body.substring(start, cursor), ResearchSummary.Item.class); }
                catch (RuntimeException invalid) { reject(); return; }
                paragraphs.add(item); start = -1; separator = true;
                complete.accept(List.copyOf(paragraphs));
            }
        }
    }
    private void reject() { done = true; rejected.run(); }
}
