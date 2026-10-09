import com.google.gson.*;

import java.nio.file.*;
import java.util.*;

/** Deterministic substitute responses check Java behavior; they cannot establish DeepSeek quality. */
final class ExperimentChecks {
    private static int checks;

    static void run(Path output) throws Exception {
        checks = 0;
        System.out.println("OFFLINE_DEMO synthetic=true realModel=false network=false");
        var summary = ResearchAgentExperiment.samples().get(0);
        JsonObject safeSummary = summaryAnswer();
        JsonObject result = ResearchAgentExperiment.runSample(summary, replies(finalResponse(safeSummary)));
        check(result.getAsJsonObject("quote").get("price").getAsString().equals("42.80"));
        check(!ResearchAgentExperiment.JSON.toJson(result).contains("op4"));
        JsonObject quote = result.getAsJsonObject("quote");
        System.out.println("模拟报价：" + quote.get("price").getAsString() + " " + quote.get("currency").getAsString()
                + "；样本时间 " + quote.get("dataTime").getAsString() + "；来源 SELF_MADE_FIXTURE，不是真实行情。");
        System.out.println("模拟梳理结果：" + result.get("answer"));

        var question = ResearchAgentExperiment.samples().get(1);
        JsonObject answer = ResearchAgentExperiment.parse("{\"paragraphs\":[{\"text\":\"小陈先预期订单增加，后来发现缺少公告确认，因此转为中立。\",\"sourceIds\":[\"op1\",\"op2\"]}]}");
        List<JsonObject> sent = new ArrayList<>();
        var queue = new ArrayDeque<>(List.of(toolResponse("call_demo", "{\"authorId\":\"M1\"}"), finalResponse(answer)));
        result = ResearchAgentExperiment.runSample(question, (request, deadline) -> { sent.add(request.deepCopy()); return queue.remove(); });
        check(result.get("modelCalls").getAsInt() == 2 && result.get("toolCalls").getAsInt() == 1);
        check(!ResearchAgentExperiment.JSON.toJson(sent.get(0)).contains("没有公告确认订单金额"));
        check(sent.get(0).has("tools") && !sent.get(0).has("response_format"));
        check(!sent.get(1).has("tools") && sent.get(1).has("response_format"));
        JsonArray history = sent.get(1).getAsJsonArray("messages");
        check(history.size() == 4 && history.get(2).getAsJsonObject().get("role").getAsString().equals("assistant"));
        check(history.get(3).getAsJsonObject().get("tool_call_id").getAsString().equals("call_demo"));
        check(!ResearchAgentExperiment.JSON.toJson(history).contains("已撤回的示例正文"));
        System.out.println("模拟追问：模型替身请求 read_cycle_opinions(M1) → Java 返回 op1、op2 → 模型替身给出带引用的答案。");

        JsonObject noEvidence = ResearchAgentExperiment.parse("{\"paragraphs\":[{\"text\":\"当前未取得公告，成员预期不能证明订单已经增长。\",\"sourceIds\":[]}]}");
        result = ResearchAgentExperiment.runSample(ResearchAgentExperiment.samples().get(2), replies(finalResponse(noEvidence)));
        check(result.get("toolCalls").getAsInt() == 0);

        JsonObject validTool = toolCall("call_allowed", "{\"authorId\":\"M1\"}");
        var read = ResearchAgentExperiment.executeTool(validTool, ResearchAgentExperiment.baseRecords());
        check(read.records().size() == 2);
        check(ResearchAgentExperiment.executeTool(toolCall("call_forbidden", "{\"authorId\":\"M1\",\"workspaceId\":\"other\"}"),
                ResearchAgentExperiment.baseRecords()).records().isEmpty());
        check(ResearchAgentExperiment.executeTool(toolCall("call_unknown", "{\"authorId\":\"M9\"}"),
                ResearchAgentExperiment.baseRecords()).data().get("status").getAsString().equals("REJECTED"));
        JsonObject unknownTool = validTool.deepCopy();
        unknownTool.getAsJsonObject("function").addProperty("name", "delete_workspace");
        check(ResearchAgentExperiment.executeTool(unknownTool, ResearchAgentExperiment.baseRecords()).records().isEmpty());
        check(ResearchAgentExperiment.executeTool(toolCall("call_badjson", "[]"), ResearchAgentExperiment.baseRecords())
                .data().get("status").getAsString().equals("REJECTED"));
        rejects("UNKNOWN_SOURCE", () -> ResearchAgentExperiment.validateAnswer(
                ResearchAgentExperiment.parse("{\"paragraphs\":[{\"text\":\"错误来源\",\"sourceIds\":[\"op4\"]}]}"), false,
                ResearchAgentExperiment.baseRecords()));
        JsonObject stale = safeSummary.deepCopy();
        stale.getAsJsonArray("members").get(0).getAsJsonObject().add("sourceIds", ResearchAgentExperiment.parse("{\"x\":[\"op1\"]}").get("x"));
        rejects("STALE_MEMBER_SOURCE", () -> ResearchAgentExperiment.validateAnswer(stale, true, ResearchAgentExperiment.baseRecords()));
        rejects("BAD_JSON", () -> ResearchAgentExperiment.parse("not JSON"));
        JsonObject fakeChange = safeSummary.deepCopy();
        fakeChange.getAsJsonArray("changes").get(0).getAsJsonObject().add("sourceIds",
                ResearchAgentExperiment.parse("{\"x\":[\"op3\"]}").get("x"));
        rejects("CHANGE_REQUIRES_COMPARABLE_RECORDS", () -> ResearchAgentExperiment.validateAnswer(fakeChange, true,
                ResearchAgentExperiment.baseRecords()));
        JsonObject priceFromModel = answer.deepCopy();
        priceFromModel.addProperty("price", "999.99");
        rejects("BAD_ANSWER_FIELDS", () -> ResearchAgentExperiment.validateAnswer(priceFromModel, false, ResearchAgentExperiment.baseRecords()));
        JsonObject wrongMember = safeSummary.deepCopy();
        wrongMember.getAsJsonArray("members").get(0).getAsJsonObject().add("sourceIds",
                ResearchAgentExperiment.parse("{\"x\":[\"op3\"]}").get("x"));
        rejects("WRONG_MEMBER_SOURCE", () -> ResearchAgentExperiment.validateAnswer(wrongMember, true, ResearchAgentExperiment.baseRecords()));
        rejects("BAD_TOOL_ID", () -> ResearchAgentExperiment.runSample(question,
                replies(toolResponse("call_duplicate", "{}"), toolResponse("call_duplicate", "{}"))));
        rejects("EXECUTION_LIMIT", () -> ResearchAgentExperiment.runSample(question,
                replies(toolResponse("call_1", "{}"), toolResponse("call_2", "{}"), toolResponse("call_3", "{}"))));
        var oversized = new ResearchAgentExperiment.Sample("oversized", "x".repeat(70_000), false, false,
                ResearchAgentExperiment.baseRecords(), true);
        rejects("INPUT_TOO_LARGE", () -> ResearchAgentExperiment.runSample(oversized,
                (request, deadline) -> { throw new IllegalStateException("Oversized request reached model"); }));
        JsonObject truncated = finalResponse(answer);
        truncated.getAsJsonArray("choices").get(0).getAsJsonObject().addProperty("finish_reason", "length");
        rejects("INCOMPLETE_ANSWER", () -> ResearchAgentExperiment.runSample(question, replies(truncated)));
        JsonObject thinking = finalResponse(answer);
        thinking.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").addProperty("reasoning_content", "synthetic reasoning");
        rejects("UNEXPECTED_THINKING", () -> ResearchAgentExperiment.runSample(question, replies(thinking)));
        var noQuote = new ResearchAgentExperiment.Sample("noquote", "梳理", true, true, ResearchAgentExperiment.baseRecords(), false);
        check(ResearchAgentExperiment.runSample(noQuote, replies(finalResponse(safeSummary))).getAsJsonObject("quote")
                .get("status").getAsString().equals("UNAVAILABLE"));
        check(sent.get(0).getAsJsonObject("thinking").get("type").getAsString().equals("disabled"));
        check(DeepSeekExperimentClient.charge(5000, 1000).compareTo(new java.math.BigDecimal("0.018")) == 0);
        rejects("MISSING_USAGE", () -> DeepSeekExperimentClient.tokenCount(new JsonObject(), "prompt_tokens", 2_000_000));
        rejects("UNEXPECTED_USAGE", () -> DeepSeekExperimentClient.tokenCount(
                ResearchAgentExperiment.object("completion_tokens", -1), "completion_tokens", 2000));
        rejects("UNEXPECTED_USAGE", () -> DeepSeekExperimentClient.tokenCount(
                ResearchAgentExperiment.object("completion_tokens", 2001), "completion_tokens", 2000));

        // A unique scratch ledger verifies settlement and restart behavior without affecting the live budget.
        Files.createDirectories(output);
        Path scratch = output.resolve("check-budget-" + UUID.randomUUID() + ".jsonl");
        try {
            try (var ledger = new DeepSeekExperimentClient.BudgetLedger(scratch)) {
                ledger.reserve("known", 100);
                ledger.settle("known", new java.math.BigDecimal("0.018"));
                ledger.reserve("unknown-1", 100);
                ledger.reserve("unknown-2", 100);
                rejects("EXPERIMENT_BUDGET_EXHAUSTED", () -> ledger.reserve("beyond", 100));
            }
            try (var ledger = new DeepSeekExperimentClient.BudgetLedger(scratch)) {
                check(ledger.total().compareTo(new java.math.BigDecimal("8.050")) == 0);
                rejects("EXPERIMENT_BUDGET_EXHAUSTED", () -> ledger.reserve("after-restart", 100));
            }
        } finally { Files.deleteIfExists(scratch); }
        System.out.println("AGENT_LAB_OFFLINE_OK checks=" + checks
                + " synthetic=true realModel=false network=false scratchLedgerRemaining=" + Files.exists(scratch));
    }

    static JsonObject summaryAnswer() {
        return ResearchAgentExperiment.parse("""
                {"overview":{"text":"小陈由看好转为中立，小林担心利润率；这些是成员判断。","sourceIds":["op1","op2","op3"]},
                 "changes":[{"text":"小陈因缺少公告确认而调整判断。","sourceIds":["op1","op2"]}],
                 "members":[{"authorId":"M1","text":"中立，等待订单证据。","sourceIds":["op2"]},
                            {"authorId":"M2","text":"担心利润率，待财报核实。","sourceIds":["op3"]}],
                 "pendingItems":[{"text":"核对订单公告与利润率，当前未取得这些资料。","sourceIds":["op2","op3"]}]}
                """);
    }
    static JsonObject finalResponse(JsonObject answer) {
        JsonObject choice = ResearchAgentExperiment.object("finish_reason", "stop");
        choice.add("message", ResearchAgentExperiment.message("assistant", ResearchAgentExperiment.JSON.toJson(answer)));
        JsonObject response = ResearchAgentExperiment.object("model", "OFFLINE_SUBSTITUTE");
        JsonArray choices = new JsonArray(); choices.add(choice); response.add("choices", choices);
        return response;
    }
    static JsonObject toolCall(String id, String args) {
        JsonObject call = ResearchAgentExperiment.object("id", id, "type", "function");
        call.add("function", ResearchAgentExperiment.object("name", ResearchAgentExperiment.TOOL, "arguments", args));
        return call;
    }
    static JsonObject toolResponse(String id, String args) {
        JsonObject response = finalResponse(new JsonObject());
        JsonObject choice = response.getAsJsonArray("choices").get(0).getAsJsonObject();
        choice.addProperty("finish_reason", "tool_calls");
        JsonArray calls = new JsonArray(); calls.add(toolCall(id, args));
        choice.getAsJsonObject("message").add("tool_calls", calls);
        return response;
    }
    static ResearchAgentExperiment.ModelClient replies(JsonObject... responses) {
        var queue = new ArrayDeque<>(List.of(responses));
        return (request, deadline) -> queue.remove();
    }
    interface CheckAction { void run() throws Exception; }
    static void rejects(String code, CheckAction action) throws Exception {
        try { action.run(); }
        catch (ResearchAgentExperiment.LabFailure failure) { check(code.equals(failure.code)); return; }
        throw new IllegalStateException("Expected boundary rejection");
    }
    static void check(boolean value) { if (!value) throw new IllegalStateException("Offline behavior check failed"); checks++; }
}
