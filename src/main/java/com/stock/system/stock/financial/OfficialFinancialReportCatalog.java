package com.stock.system.stock.financial;

import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Reviewed official-report data; the model cannot supply files, URLs, symbols or source definitions. */
@Component
public class OfficialFinancialReportCatalog {
    private final List<Report> reports;

    public OfficialFinancialReportCatalog() {
        var json = JsonMapper.builder().findAndAddModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        try (var input = getClass().getResourceAsStream("/financial/official-annual-reports.json")) {
            if (input == null) throw new IllegalStateException("Official financial catalog is missing");
            byte[] bytes = input.readNBytes(256 * 1024 + 1);
            if (bytes.length > 256 * 1024) throw new IllegalStateException("Official financial catalog is too large");
            var catalog = json.readValue(bytes, Catalog.class);
            if (catalog.version() != 1 || catalog.reports() == null || catalog.reports().isEmpty()
                    || catalog.reports().size() > 16) throw new IllegalArgumentException();
            catalog.reports().forEach(OfficialFinancialReportCatalog::validate);
            if (catalog.reports().stream().map(report -> report.symbol() + ":" + report.year()).distinct().count()
                    != catalog.reports().size()) throw new IllegalArgumentException();
            reports = List.copyOf(catalog.reports());
        } catch (IOException | RuntimeException ignored) {
            throw new IllegalStateException("Official financial catalog failed validation");
        }
    }

    /** Null means this stock/series is not covered; a missing requested year never falls back silently. */
    public FinancialFacts read(String symbol, FinancialPeriod series, String period, List<FinancialMetric> metrics) {
        FinancialSourceGateway.validateSymbol(symbol);
        if (series != FinancialPeriod.ANNUAL) return null;
        List<Report> selected = reports.stream().filter(report -> sameSymbol(symbol, report.symbol()))
                .sorted(Comparator.comparingInt(Report::year).reversed()).toList();
        if (selected.isEmpty()) return null;
        List<String> available = selected.stream().flatMap(report -> report.values().stream())
                .map(value -> "FY " + value.year()).distinct().sorted(Comparator.reverseOrder()).limit(12).toList();
        String target = period == null ? available.get(0) : period;
        Report report = selected.stream().filter(item -> item.values().stream()
                .anyMatch(value -> target.equals("FY " + value.year()))).findFirst().orElse(null);
        if (report == null) return new FinancialFacts("PERIOD_UNAVAILABLE", symbol, series, target, available,
                List.of(), metrics, "OFFICIAL_REPORT_CATALOG", null, null, "REQUESTED_PERIOD_NOT_IN_CATALOG",
                "系统已核实的年度报告库未覆盖这个期间，未改用其他期间。");
        int year = Integer.parseInt(target.substring(3));
        var facts = new ArrayList<FinancialFacts.Fact>();
        var missing = new ArrayList<FinancialMetric>();
        var references = new ArrayList<FinancialFacts.ReportReference>();
        for (FinancialMetric metric : metrics) {
            Row current = row(report, metric, year);
            if (current == null) { missing.add(metric); continue; }
            Row previous = row(report, metric, year - 1);
            FinancialFacts.Value currentValue = value(report, current);
            FinancialFacts.Value previousValue = previous == null ? null : value(report, previous);
            facts.add(new FinancialFacts.Fact(metric, current.sourceField(), current.sourceName(), currentValue,
                    previousValue, FinancialFacts.compare(metric, currentValue, previousValue)));
            references.add(new FinancialFacts.ReportReference(metric, report.title(), report.url(), report.sha256(),
                    "FY " + report.year(), current.pdfPages()));
        }
        return new FinancialFacts(missing.isEmpty() ? "AVAILABLE" : "PARTIAL", symbol, series, target, available,
                facts, missing, "OFFICIAL_REPORT_CATALOG", report.retrievedAt(), report.disclosedOn(), null,
                "本次读取系统已核实年度报告库，非全部最新披露证明；对比值按同一报告的比较列保留，"
                        + "净利润为归母／股东应占口径。数字变化不证明订单或业务增长原因。", references);
    }

    public boolean matches(FinancialFacts snapshot) {
        if (snapshot == null || snapshot.reports().isEmpty() || snapshot.facts().isEmpty()
                || snapshot.requestedSeries() != FinancialPeriod.ANNUAL || snapshot.selectedPeriod() == null) return false;
        List<FinancialMetric> metrics = new ArrayList<>(snapshot.facts().stream().map(FinancialFacts.Fact::metric).toList());
        metrics.addAll(snapshot.missingMetrics());
        if (metrics.size() > 5 || metrics.stream().anyMatch(Objects::isNull) || metrics.stream().distinct().count() != metrics.size()) return false;
        FinancialFacts current = read(snapshot.symbol(), snapshot.requestedSeries(), snapshot.selectedPeriod(), metrics);
        return current != null && Objects.equals(current.selectedPeriod(), snapshot.selectedPeriod())
                && current.facts().equals(snapshot.facts()) && current.reports().equals(snapshot.reports())
                && current.missingMetrics().equals(snapshot.missingMetrics());
    }

    private static Row row(Report report, FinancialMetric metric, int year) {
        return report.values().stream().filter(value -> value.metric() == metric && value.year() == year).findFirst().orElse(null);
    }
    private static FinancialFacts.Value value(Report report, Row row) {
        return new FinancialFacts.Value("FY " + row.year(), FinancialPeriod.ANNUAL, row.year(), 1,
                LocalDate.of(row.year(), 12, 31), row.amount(), report.currency(), report.unit(),
                row.metric() == FinancialMetric.NET_PROFIT ? "PARENT_SHAREHOLDERS" : "NOT_APPLICABLE", "ISSUER_ORIGINAL");
    }
    private static boolean sameSymbol(String first, String second) {
        return first != null && (first.equals(second) || first.matches("[0-9]{1,5}\\.HK")
                && second.endsWith(".HK") && Integer.parseInt(first.split("\\.")[0]) == Integer.parseInt(second.split("\\.")[0]));
    }
    private static void validate(Report report) {
        Objects.requireNonNull(report);
        FinancialSourceGateway.validateSymbol(report.symbol());
        var uri = URI.create(report.url());
        if (!"https".equals(uri.getScheme()) || !Set.of("static.cninfo.com.cn", "static.www.tencent.com").contains(uri.getHost())
                || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !uri.getPath().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")
                || !report.sha256().matches("[a-f0-9]{64}") || report.title() == null || report.title().isBlank()
                || report.title().length() > 128 || !"CNY".equals(report.currency())
                || !Set.of("YUAN", "MILLION_YUAN").contains(report.unit())
                || report.year() < 2020 || report.year() >= LocalDate.now().getYear()
                || report.retrievedAt() == null || report.retrievedAt().isAfter(Instant.now().plusSeconds(60))
                || report.values() == null || report.values().isEmpty() || report.values().size() > 10) throw new IllegalArgumentException();
        for (Row row : report.values()) {
            if (row == null || row.metric() == null || row.year() > report.year() || row.year() < report.year() - 1
                    || row.amount() == null || row.amount().precision() > 28 || row.amount().scale() > 2
                    || row.sourceField() == null || row.sourceField().isBlank() || row.sourceField().length() > 96
                    || row.sourceName() == null || row.sourceName().isBlank() || row.sourceName().length() > 128
                    || row.pdfPages() == null || row.pdfPages().isEmpty() || row.pdfPages().size() > 3
                    || row.pdfPages().stream().anyMatch(page -> page == null || page < 1 || page > 500)) throw new IllegalArgumentException();
        }
        if (report.values().stream().map(row -> row.metric() + ":" + row.year()).distinct().count()
                != report.values().size()) throw new IllegalArgumentException();
    }
    public record Catalog(int version, List<Report> reports) { }
    public record Report(String symbol, String title, String url, String sha256, int year, String currency,
                         String unit, Instant retrievedAt, LocalDate disclosedOn, List<Row> values) { }
    public record Row(FinancialMetric metric, int year, java.math.BigDecimal amount,
                      String sourceField, String sourceName, List<Integer> pdfPages) { }
}
