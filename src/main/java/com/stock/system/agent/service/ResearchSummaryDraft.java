package com.stock.system.agent.service;

import com.stock.system.agent.dto.ResearchSummary;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** 只读取已闭合的根字段；未结束的数组不交付，业务校验与来源核对由调用方执行。 */
final class ResearchSummaryDraft {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Pattern ROOT = Pattern.compile("^\\s*\\{");
    private static final Pattern FIELD = Pattern.compile("\\s*\"(overview|changes|members|pendingItems)\"\\s*:\\s*");
    private final Consumer<ResearchSummary> complete;
    private final Runnable rejected;
    private final StringBuilder body = new StringBuilder();
    private final ObjectNode root = JSON.createObjectNode();
    private final Set<String> fields = new HashSet<>();
    private int cursor = -1, start = -1, depth;
    private String field;
    private boolean quoted, escaped, separator, done;

    ResearchSummaryDraft(Consumer<ResearchSummary> complete, Runnable rejected) {
        this.complete = complete; this.rejected = rejected;
        root.putArray("changes"); root.putArray("members"); root.putArray("pendingItems");
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
            var opening = ROOT.matcher(prefix);
            if (!opening.find()) return;
            cursor = body.length() - prefix.length() + opening.end();
        }
        while (cursor < body.length() && !done) {
            if (start < 0) {
                while (cursor < body.length() && Character.isWhitespace(body.charAt(cursor))) cursor++;
                if (cursor == body.length()) return;
                if (field == null) {
                    if (body.charAt(cursor) == '}') { done = true; return; }
                    if (separator) {
                        if (body.charAt(cursor++) != ',') { reject(); return; }
                        separator = false;
                    }
                    var key = FIELD.matcher(body).region(cursor, body.length());
                    if (!key.lookingAt()) return; // 合法但暂不能识别的键写法退回最终完整解析。
                    field = key.group(1); cursor = key.end();
                }
                while (cursor < body.length() && Character.isWhitespace(body.charAt(cursor))) cursor++;
                if (cursor == body.length()) return;
                char opening = body.charAt(cursor);
                if (opening != '{' && opening != '[' || !fields.add(field)) { reject(); return; }
                start = cursor++; depth = 1; quoted = false; escaped = false;
                continue;
            }
            char current = body.charAt(cursor++);
            if (quoted) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') quoted = false;
            } else if (current == '"') quoted = true;
            else if (current == '{' || current == '[') depth++;
            else if ((current == '}' || current == ']') && --depth == 0) {
                ResearchSummary snapshot;
                try {
                    root.set(field, JSON.readTree(body.substring(start, cursor)));
                    snapshot = JSON.treeToValue(root, ResearchSummary.class);
                } catch (RuntimeException invalid) { reject(); return; }
                start = -1; field = null; separator = true;
                if (snapshot.overview() != null) complete.accept(snapshot);
            }
        }
    }

    private void reject() { done = true; rejected.run(); }
}
