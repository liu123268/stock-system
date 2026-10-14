package com.stock.system.stock.financial;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.stock.system.stock.financial.FinancialSourceGateway.FailureReason.UNEXPECTED_RESPONSE;
import static com.stock.system.stock.financial.FinancialSourceGateway.SourceFailure;

/** Parser shared by SDK and CLI. Provider display hints and reported yoy never enter facts. */
@Component
public class LongbridgeFinancialParser {
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern PERIOD = Pattern.compile("^(FY|H1|Q[1-4])\\s*(20[0-9]{2})$");
    private static final Pattern AMOUNT = Pattern.compile("^-?[0-9]{1,28}(?:\\.[0-9]{1,12})?$");
    private final ObjectMapper json;

    public LongbridgeFinancialParser(ObjectMapper json) { this.json = json; }

    public FinancialFacts parse(String symbol, FinancialPeriod series, String requestedPeriod,
                                List<FinancialMetric> metrics, FinancialSourceGateway.SourceReport source) {
        FinancialSourceGateway.validateSymbol(symbol);
        requestedPeriod = requirePeriod(series, requestedPeriod);
        metrics = requireMetrics(metrics);
        if (source == null || source.retrievedAt() == null || source.source() == null || source.source().isBlank()
                || source.json() == null || source.json().length() > 2_000_000) throw unexpected();
        JsonNode root;
        try {
            root = json.readTree(source.json());
            if (root == null || !root.isObject()) throw unexpected();
            if (root.has("code")) {
                if (!root.path("code").isIntegralNumber() || root.path("code").asInt(-1) != 0) throw unexpected();
                root = root.path("data");
            }
            if (root.hasNonNull("symbol") && !sameSymbol(symbol, root.path("symbol").asString())) throw unexpected();
            if (root.hasNonNull("report") && !series.sourceCode().equals(root.path("report").asString())) throw unexpected();
            if (root.has("list")) root = root.path("list");
            if (root.isString()) root = json.readTree(root.asString());
            if (root == null || !root.isObject()) throw unexpected();
        } catch (RuntimeException ignored) { throw unexpected(); }

        Map<FinancialMetric, Map<String, Entry>> loaded = new EnumMap<>(FinancialMetric.class);
        for (FinancialMetric metric : metrics) loaded.put(metric, readEntries(root, metric, source.retrievedAt()));
        List<Key> periods = loaded.values().stream().flatMap(values -> values.values().stream())
                .map(Entry::key).filter(key -> key.series() == series).distinct()
                .sorted(Comparator.comparingInt(Key::year).thenComparingInt(Key::part).reversed()).toList();
        List<String> available = periods.stream().map(Key::label).limit(12).toList();
        String target = requestedPeriod == null ? (periods.isEmpty() ? null : periods.get(0).label()) : requestedPeriod;
        if (target == null || periods.stream().noneMatch(key -> key.label().equals(target))) {
            return result(requestedPeriod == null ? "NO_DATA" : "PERIOD_UNAVAILABLE", symbol, series, target,
                    available, List.of(), metrics, source, "REQUESTED_PERIOD_NOT_RETURNED");
        }
        List<FinancialFacts.Fact> facts = new ArrayList<>();
        List<FinancialMetric> missing = new ArrayList<>();
        for (FinancialMetric metric : metrics) {
            Entry entry = loaded.get(metric).get(target);
            if (entry == null || entry.value().amount() == null) { missing.add(metric); continue; }
            Key key = entry.key();
            String comparableLabel = key.prefix() + " " + (key.year() - 1);
            Entry comparable = loaded.get(metric).get(comparableLabel);
            FinancialFacts.Value previous = comparable == null ? null : comparable.value();
            facts.add(new FinancialFacts.Fact(metric, metric.sourceField(), entry.name(), entry.value(), previous,
                    FinancialFacts.compare(metric, entry.value(), previous)));
        }
        // This endpoint provides a display currency, but does not establish original-currency conversion or units.
        return result(facts.isEmpty() ? "NO_DATA" : "PARTIAL", symbol, series, target, available,
                facts, missing, source, facts.isEmpty() ? "VALUES_MISSING" : "SOURCE_BASIS_UNVERIFIED");
    }

    private Map<String, Entry> readEntries(JsonNode root, FinancialMetric metric, Instant retrievedAt) {
        JsonNode group = root.path(metric.statement());
        if (group.isMissingNode()) return Map.of();
        if (!group.isObject()) throw unexpected();
        JsonNode indicators = group.path("indicators");
        if (!indicators.isArray() || indicators.size() > 128) throw unexpected();
        Map<String, Entry> result = new LinkedHashMap<>();
        for (JsonNode indicator : indicators) {
            if (!indicator.isObject()) throw unexpected();
            JsonNode accounts = indicator.path("accounts");
            if (!accounts.isArray() || accounts.size() > 64) throw unexpected();
            String currency = scalar(indicator, "currency", 3);
            if (!currency.matches("[A-Z]{3}")) currency = "UNKNOWN";
            for (JsonNode account : accounts) {
                if (!metric.sourceField().equals(scalar(account, "field", 64))) continue;
                if (!account.path("percent").isBoolean() || account.path("percent").asBoolean()) throw unexpected();
                String name = scalar(account, "name", 128);
                JsonNode values = account.path("values");
                if (!values.isArray() || values.size() > 128) throw unexpected();
                for (JsonNode value : values) {
                    if (!value.isObject()) throw unexpected();
                    Key key = key(scalar(value, "period", 16), scalar(value, "fp_end", 16), retrievedAt);
                    if (key == null) continue;
                    String raw = scalar(value, "value", 42);
                    BigDecimal amount = AMOUNT.matcher(raw).matches() ? new BigDecimal(raw) : null;
                    FinancialFacts.Value factValue = new FinancialFacts.Value(key.label(), key.series(), key.year(),
                            key.part(), key.end(), amount, currency, "UNKNOWN", "UNKNOWN", "PROVIDER_DISPLAY_UNVERIFIED");
                    Entry entry = new Entry(key, name, factValue);
                    if (result.putIfAbsent(key.label(), entry) != null) throw unexpected();
                }
            }
        }
        return result;
    }

    private static Key key(String rawPeriod, String rawEnd, Instant retrievedAt) {
        Matcher match = PERIOD.matcher(rawPeriod);
        if (!match.matches() || !rawEnd.matches("[0-9]{10}")) return null;
        int year = Integer.parseInt(match.group(2));
        LocalDate end = Instant.ofEpochSecond(Long.parseLong(rawEnd)).atZone(REPORT_ZONE).toLocalDate();
        if (end.getYear() != year || end.isAfter(retrievedAt.atZone(REPORT_ZONE).toLocalDate())) return null;
        String prefix = match.group(1);
        FinancialPeriod series = prefix.equals("FY") ? FinancialPeriod.ANNUAL
                : prefix.equals("H1") ? FinancialPeriod.SEMIANNUAL : FinancialPeriod.QUARTER;
        int part = prefix.startsWith("Q") ? Integer.parseInt(prefix.substring(1)) : 1;
        // Prevent single-quarter values from being paired with cumulative/annual labels or mismatched dates.
        if (series == FinancialPeriod.QUARTER && (end.getMonthValue() != part * 3
                || end.getDayOfMonth() != end.lengthOfMonth())) return null;
        if (series == FinancialPeriod.SEMIANNUAL && (end.getMonthValue() != 6 || end.getDayOfMonth() != 30)) return null;
        return new Key(prefix + " " + year, prefix, series, year, part, end);
    }

    public static String requirePeriod(FinancialPeriod series, String requested) {
        if (series == null) throw new IllegalArgumentException("Financial series required");
        if (requested == null) return null;
        Matcher match = PERIOD.matcher(requested);
        if (!match.matches() || !(series == FinancialPeriod.ANNUAL && match.group(1).equals("FY")
                || series == FinancialPeriod.SEMIANNUAL && match.group(1).equals("H1")
                || series == FinancialPeriod.QUARTER && match.group(1).startsWith("Q"))) {
            throw new IllegalArgumentException("Invalid financial period");
        }
        return match.group(1) + " " + match.group(2);
    }

    public static List<FinancialMetric> requireMetrics(List<FinancialMetric> requested) {
        if (requested == null || requested.isEmpty()) return List.of(FinancialMetric.values());
        if (requested.size() > 5 || requested.stream().anyMatch(Objects::isNull)
                || new LinkedHashSet<>(requested).size() != requested.size()) throw new IllegalArgumentException("Invalid financial metrics");
        return List.copyOf(requested);
    }

    private FinancialFacts result(String status, String symbol, FinancialPeriod series, String selected,
                                  List<String> periods, List<FinancialFacts.Fact> facts,
                                  List<FinancialMetric> missing, FinancialSourceGateway.SourceReport source, String reason) {
        return new FinancialFacts(status, symbol, series, selected, periods, facts, missing, source.source(),
                source.retrievedAt(), null, reason,
                "本次仅为所选系列的源端可用数据，非最新完整披露证明。展示币种／金额单位／利润口径尚未完全核实，不能据此确认原报增长；报告期结束日不是公告日。");
    }

    private static String scalar(JsonNode node, String field, int bound) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return "";
        if (!value.isString() && !value.isNumber()) throw unexpected();
        String text = value.asString();
        if (text.length() > bound || text.codePoints().anyMatch(Character::isISOControl)) throw unexpected();
        return text;
    }

    private static boolean sameSymbol(String expected, String returned) {
        if (expected.equals(returned)) return true;
        return expected.endsWith(".HK") && returned.matches("[0-9]{1,5}\\.HK")
                && Integer.parseInt(expected.split("\\.")[0]) == Integer.parseInt(returned.split("\\.")[0]);
    }
    private static SourceFailure unexpected() { return new SourceFailure(UNEXPECTED_RESPONSE); }
    private record Key(String label, String prefix, FinancialPeriod series, int year, int part, LocalDate end) { }
    private record Entry(Key key, String name, FinancialFacts.Value value) { }
}
