package com.stock.system.stock.financial;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** All numbers carry their meaning; unknown basis is explicit and prevents arithmetic. */
public record FinancialFacts(
        String status, String symbol, FinancialPeriod requestedSeries, String selectedPeriod,
        List<String> availablePeriods, List<Fact> facts, List<FinancialMetric> missingMetrics,
        String source, Instant retrievedAt, LocalDate disclosedOn, String unavailableReason, String note,
        List<ReportReference> reports
) {
    public FinancialFacts {
        availablePeriods = List.copyOf(availablePeriods);
        facts = List.copyOf(facts);
        missingMetrics = List.copyOf(missingMetrics);
        reports = reports == null ? List.of() : List.copyOf(reports);
    }

    public FinancialFacts(String status, String symbol, FinancialPeriod requestedSeries, String selectedPeriod,
                          List<String> availablePeriods, List<Fact> facts, List<FinancialMetric> missingMetrics,
                          String source, Instant retrievedAt, LocalDate disclosedOn, String unavailableReason, String note) {
        this(status, symbol, requestedSeries, selectedPeriod, availablePeriods, facts, missingMetrics,
                source, retrievedAt, disclosedOn, unavailableReason, note, List.of());
    }

    public record Value(String period, FinancialPeriod series, int year, int part, LocalDate periodEnd,
                        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
                        String currency, String unit, String profitBasis,
                        String currencyBasis) { }

    public record Fact(FinancialMetric metric, String sourceField, String sourceName,
                       Value current, Value comparable, Change change) { }

    public record Change(@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal absoluteDifference,
                         @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal percent, String unavailableReason) { }

    public record ReportReference(FinancialMetric metric, String title, String url, String sha256,
                                  String reportPeriod, List<Integer> pdfPages) {
        public ReportReference { pdfPages = List.copyOf(pdfPages); }
    }

    /** No provider yoy field or model arithmetic can bypass these comparisons. */
    public static Change compare(FinancialMetric metric, Value current, Value comparable) {
        if (current == null || comparable == null) return noChange("COMPARABLE_PERIOD_MISSING");
        if (current.series() != comparable.series() || current.part() != comparable.part()
                || current.year() != comparable.year() + 1 || current.periodEnd() == null
                || comparable.periodEnd() == null
                || !comparable.periodEnd().plusYears(1).equals(current.periodEnd())) {
            return noChange("PERIOD_BASIS_MISMATCH");
        }
        if (current.amount() == null || comparable.amount() == null) return noChange("VALUE_MISSING");
        if (!"ISSUER_ORIGINAL".equals(current.currencyBasis())
                || !"ISSUER_ORIGINAL".equals(comparable.currencyBasis())) {
            return noChange("CURRENCY_CONVERSION_UNVERIFIED");
        }
        if (unknown(current.currency()) || unknown(current.unit())
                || unknown(comparable.currency()) || unknown(comparable.unit())) return noChange("UNIT_OR_CURRENCY_UNVERIFIED");
        if (!Objects.equals(current.currency(), comparable.currency())
                || !Objects.equals(current.unit(), comparable.unit())) return noChange("UNIT_OR_CURRENCY_MISMATCH");
        if (metric == FinancialMetric.NET_PROFIT && (unknown(current.profitBasis())
                || unknown(comparable.profitBasis())
                || !Objects.equals(current.profitBasis(), comparable.profitBasis()))) return noChange("PROFIT_BASIS_UNVERIFIED");
        BigDecimal difference = current.amount().subtract(comparable.amount());
        if (comparable.amount().signum() <= 0) return new Change(difference, null, "NON_POSITIVE_BASE");
        return new Change(difference, difference.multiply(BigDecimal.valueOf(100))
                .divide(comparable.amount(), 4, RoundingMode.HALF_UP), null);
    }

    private static Change noChange(String reason) { return new Change(null, null, reason); }
    private static boolean unknown(String value) { return value == null || value.isBlank() || "UNKNOWN".equals(value); }
}
