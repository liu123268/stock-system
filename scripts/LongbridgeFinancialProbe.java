import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.longbridge.Config;
import com.longbridge.OAuth;
import com.longbridge.OAuthBuilder;
import com.longbridge.OpenApiException;
import com.longbridge.Language;
import com.longbridge.fundamental.FinancialReportKind;
import com.longbridge.fundamental.FinancialReportOptions;
import com.longbridge.fundamental.FinancialReportPeriod;
import com.longbridge.fundamental.FundamentalContext;

import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Independent source preflight: no Spring, database, model, QuoteContext or raw token handling. */
public class LongbridgeFinancialProbe {
    private static final String[] SYMBOLS = {"600519.SH", "700.HK"};
    private static final FinancialReportPeriod[] PERIODS = {
            FinancialReportPeriod.Annual, FinancialReportPeriod.SemiAnnual
    };

    public static void main(String[] args) {
        try {
            if (args.length == 1 && "--offline".equals(args[0])) {
                verifyParserOffline();
                return;
            }
            if (args.length != 2 || !("--live".equals(args[0]) || "--diagnostic".equals(args[0])
                    || "--verify".equals(args[0]))) {
                throw new IllegalArgumentException("Use --offline or --live/--diagnostic/--verify <public-client-config>");
            }
            readLive(Path.of(args[1]), "--diagnostic".equals(args[0]), "--verify".equals(args[0]));
        } catch (Exception | LinkageError failure) {
            // Provider exception messages, callback URLs and authorization data stay out of output.
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            System.err.println("FINANCIAL_PROBE_FAILED type=" + cause.getClass().getSimpleName());
            System.exit(2);
        }
    }

    private static void readLive(Path publicConfig, boolean diagnostic, boolean verify) throws Exception {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(publicConfig, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        String clientId = properties.getProperty("local.longbridge.client-id", "").trim();
        UUID.fromString(clientId);
        int requestCount = diagnostic ? 1 : 4;
        System.out.println("FINANCIAL_PROBE_START pid=" + ProcessHandle.current().pid()
                + " sdk=4.5.0 requests=" + requestCount + " quoteContext=false model=false database=false");

        try (OAuth oauth = new OAuthBuilder(clientId).build(url -> {
            throw new IllegalStateException("Existing local OAuth authorization required");
        }).get(15, TimeUnit.SECONDS);
             Config config = Config.fromOAuth(oauth).language(Language.ZH_CN).disablePrintQuotePackages();
             FundamentalContext context = FundamentalContext.create(config)) {
            int available = 0;
            int attempts = 0;
            queryLoop:
            for (String symbol : diagnostic ? new String[]{SYMBOLS[0]} : SYMBOLS) {
                for (FinancialReportPeriod period : diagnostic ? new FinancialReportPeriod[]{PERIODS[0]}
                        : verify ? new FinancialReportPeriod[]{FinancialReportPeriod.Annual,
                                FinancialReportPeriod.QuarterlyFull} : PERIODS) {
                    if (attempts > 0) Thread.sleep(1500);
                    attempts++;
                    FinancialReportOptions options = new FinancialReportOptions();
                    options.symbol = symbol;
                    options.kind = FinancialReportKind.All;
                    options.period = period;
                    try {
                        var report = context.getFinancialReport(symbol, options).get(20, TimeUnit.SECONDS);
                        JsonObject summary = inspect(report.list);
                        summary.addProperty("symbol", symbol);
                        summary.addProperty("requestedPeriod", period.name());
                        summary.addProperty("retrievedAt", Instant.now().toString());
                        System.out.println(summary);
                        if ("DATA_RETURNED".equals(summary.get("status").getAsString())) available++;
                    } catch (Exception failure) {
                        Throwable cause = failure;
                        while (cause.getCause() != null) cause = cause.getCause();
                        System.out.println("FINANCIAL_SOURCE_FAILED symbol=" + symbol + " period=" + period
                                + " type=" + cause.getClass().getSimpleName() + safeErrorDetails(cause));
                        if (cause instanceof OpenApiException api && api.getCode() != null
                                && (api.getCode() == 429001L || api.getCode() == 429002L)) break queryLoop;
                    }
                }
            }
            System.out.println("FINANCIAL_PROBE_END availableRequests=" + available
                    + " totalRequests=" + attempts + " plannedRequests=" + requestCount + " businessAcceptance=false");
            if (available != requestCount) throw new IllegalStateException("Financial source preflight incomplete");
        }
    }

    private static String safeErrorDetails(Throwable failure) {
        if (!(failure instanceof OpenApiException api)) return "";
        String message = api.getMessage() == null ? "" : api.getMessage().toLowerCase(Locale.ROOT);
        String category = api.getCode() != null && (api.getCode() == 429001L || api.getCode() == 429002L) ? "RATE_LIMIT"
                : message.contains("dns") || message.contains("resolve") ? "NAME_RESOLUTION"
                : message.contains("connection") || message.contains("connect") ? "CONNECTION"
                : message.contains("timeout") || message.contains("timed out") ? "TIMEOUT"
                : message.contains("permission") || message.contains("scope") ? "PERMISSION"
                : message.contains("unauthorized") || message.contains("token") ? "AUTHORIZATION"
                : message.contains("deserialize") || message.contains("decode") ? "RESPONSE_DECODE"
                : "UNCLASSIFIED";
        return " kind=" + api.getKind() + " code=" + api.getCode() + " category=" + category;
    }

    private static JsonObject inspect(String raw) {
        if (raw == null || raw.isBlank()) return noData();
        if (raw.length() > 2_000_000) throw new IllegalArgumentException("Response exceeds probe bound");
        JsonElement parsed = JsonParser.parseString(raw);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("Unexpected financial root");
        JsonObject root = parsed.getAsJsonObject();
        JsonObject result = new JsonObject();
        JsonArray statements = new JsonArray();
        int accountCount = 0;
        int numericValueCount = 0;
        for (String kind : new String[]{"IS", "BS", "CF"}) {
            JsonElement group = root.get(kind);
            if (group == null || !group.isJsonObject()) continue;
            JsonArray indicators = array(group.getAsJsonObject(), "indicators");
            for (JsonElement indicatorElement : indicators) {
                if (!indicatorElement.isJsonObject()) continue;
                JsonObject indicator = indicatorElement.getAsJsonObject();
                JsonArray accounts = array(indicator, "accounts");
                JsonObject statement = new JsonObject();
                statement.addProperty("kind", kind);
                copy(indicator, statement, "currency", "title", "entry", "unit", "scale");
                statement.add("periods", first(array(indicator, "periods"), 3));
                statement.addProperty("accountCount", accounts.size());
                accountCount += accounts.size();
                JsonArray examples = new JsonArray();
                for (JsonElement accountElement : accounts) {
                    if (!accountElement.isJsonObject()) continue;
                    JsonObject account = accountElement.getAsJsonObject();
                    for (JsonElement valueElement : array(account, "values")) {
                        if (!valueElement.isJsonObject()) continue;
                        String amount = text(valueElement.getAsJsonObject(), "value");
                        try {
                            new BigDecimal(amount);
                            numericValueCount++;
                        } catch (NumberFormatException missingOrFormatted) { /* Not a verified numeric value. */ }
                    }
                    // An exact allowlist includes operating cash flow and excludes profit margins/ratios.
                    if (!java.util.Set.of("OperatingRevenue", "NetProfit", "TotalAssets", "TotalLiability",
                            "NetOperateCashFlow").contains(text(account, "field"))) continue;
                    JsonObject item = new JsonObject();
                    copy(account, item, "field", "name", "percent", "tip", "unit", "scale");
                    JsonArray values = new JsonArray();
                    // Quarter samples need five rows to observe the same quarter in the preceding year.
                    for (JsonElement valueElement : first(array(account, "values"), 5)) {
                        if (!valueElement.isJsonObject()) continue;
                        JsonObject value = new JsonObject();
                        copy(valueElement.getAsJsonObject(), value, "period", "year", "fp_end", "value", "ratio", "yoy");
                        values.add(value);
                    }
                    item.add("values", values);
                    examples.add(item);
                }
                statement.add("candidateFields", examples);
                statements.add(statement);
            }
        }
        result.addProperty("status", numericValueCount > 0 ? "DATA_RETURNED" : accountCount > 0 ? "METADATA_ONLY" : "NO_DATA");
        result.addProperty("numericValueCount", numericValueCount);
        result.addProperty("unitVerified", false);
        result.addProperty("latestDisclosureVerified", false);
        result.add("statements", statements);
        return result;
    }

    private static JsonObject noData() {
        JsonObject result = new JsonObject();
        result.addProperty("status", "NO_DATA");
        return result;
    }

    private static String text(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static JsonArray array(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static JsonArray first(JsonArray source, int limit) {
        JsonArray result = new JsonArray();
        for (int i = 0; i < Math.min(source.size(), limit); i++) result.add(source.get(i));
        return result;
    }

    private static void copy(JsonObject source, JsonObject target, String... names) {
        for (String name : names) {
            JsonElement value = source.get(name);
            // Display hints can be large nested marketing objects; they are not financial facts.
            if (value != null && (value.isJsonPrimitive() || value.isJsonNull())) target.add(name, value);
        }
    }

    private static void verifyParserOffline() {
        String sample = """
                {"IS":{"indicators":[{"currency":"CNY","periods":["FY2025","FY2024"],
                "accounts":[{"field":"net_income","name":"Net income","percent":false,
                "values":[{"period":"FY2025","value":"120"},{"period":"FY2024","value":"100"}]}]}]}}
                """;
        JsonObject valid = inspect(sample);
        if (!"DATA_RETURNED".equals(valid.get("status").getAsString())
                || !"NO_DATA".equals(inspect("{}").get("status").getAsString())
                || !"METADATA_ONLY".equals(inspect(sample.replace("\"120\"", "\"--\"")
                    .replace("\"100\"", "null")).get("status").getAsString())
                || valid.get("unitVerified").getAsBoolean()
                || valid.get("latestDisclosureVerified").getAsBoolean()) {
            throw new IllegalStateException("Financial response boundary failed");
        }
        boolean rejected = false;
        try { inspect("[]"); } catch (IllegalArgumentException expected) { rejected = true; }
        if (!rejected) throw new IllegalStateException("Unexpected financial shape accepted");
        System.out.println("FINANCIAL_PROBE_OFFLINE_OK synthetic=true network=false emptyAndUnexpectedShapeChecked=true");
    }
}
