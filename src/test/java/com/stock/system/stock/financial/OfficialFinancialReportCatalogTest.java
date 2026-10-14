package com.stock.system.stock.financial;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OfficialFinancialReportCatalogTest {
    private final OfficialFinancialReportCatalog catalog = new OfficialFinancialReportCatalog();

    @Test void readsOfficialMoutaiAmountsWithOriginalUnitAndParentProfitBasis() {
        var facts = catalog.read("600519.SH", FinancialPeriod.ANNUAL, "FY 2025", List.of(FinancialMetric.values()));
        assertThat(facts.status()).isEqualTo("AVAILABLE");
        assertThat(facts.facts()).hasSize(5);
        var revenue = fact(facts, FinancialMetric.REVENUE);
        assertThat(revenue.current().amount()).isEqualByComparingTo("168838102514.79");
        assertThat(revenue.comparable().amount()).isEqualByComparingTo("170899152276.34");
        assertThat(revenue.current().currency()).isEqualTo("CNY");
        assertThat(revenue.current().unit()).isEqualTo("YUAN");
        assertThat(revenue.change().percent()).isNegative();
        assertThat(fact(facts, FinancialMetric.NET_PROFIT).current().profitBasis()).isEqualTo("PARENT_SHAREHOLDERS");
        assertThat(fact(facts, FinancialMetric.NET_PROFIT).current().amount()).isEqualByComparingTo("82320067101.68");
        assertThat(facts.reports()).allSatisfy(report -> assertThat(report.sha256()).matches("[a-f0-9]{64}"));
        assertThat(facts.disclosedOn()).isNull();
    }

    @Test void keepsTencentOriginalRmbMillionAndShareholderProfitInsteadOfTotalProfitOrHkdDisplay() {
        var facts = catalog.read("00700.HK", FinancialPeriod.ANNUAL, null, List.of(FinancialMetric.values()));
        assertThat(facts.selectedPeriod()).isEqualTo("FY 2025");
        var revenue = fact(facts, FinancialMetric.REVENUE);
        assertThat(revenue.current().amount()).isEqualByComparingTo("751766");
        assertThat(revenue.comparable().amount()).isEqualByComparingTo("660257");
        assertThat(revenue.current().currency()).isEqualTo("CNY");
        assertThat(revenue.current().unit()).isEqualTo("MILLION_YUAN");
        assertThat(fact(facts, FinancialMetric.NET_PROFIT).current().amount()).isEqualByComparingTo("224842");
        var liabilities = fact(facts, FinancialMetric.TOTAL_LIABILITIES);
        assertThat(liabilities.current().amount()).isEqualByComparingTo("797921");
        assertThat(liabilities.comparable().amount()).isEqualByComparingTo("727099");
        assertThat(liabilities.sourceName()).contains("总资产减总权益");
        assertThat(facts.reports().stream().filter(report -> report.metric() == FinancialMetric.TOTAL_LIABILITIES)
                .findFirst().orElseThrow().pdfPages()).containsExactly(132, 133);
    }

    @Test void missingRequestedYearDoesNotSelectAnotherYearOrInventComparableValue() {
        var missing = catalog.read("700.HK", FinancialPeriod.ANNUAL, "FY 2023", List.of(FinancialMetric.REVENUE));
        assertThat(missing.status()).isEqualTo("PERIOD_UNAVAILABLE");
        assertThat(missing.selectedPeriod()).isEqualTo("FY 2023");
        assertThat(missing.availablePeriods()).containsExactly("FY 2025", "FY 2024");
        assertThat(missing.facts()).isEmpty();
        var older = catalog.read("700.HK", FinancialPeriod.ANNUAL, "FY 2024", List.of(FinancialMetric.REVENUE));
        assertThat(older.facts().get(0).comparable()).isNull();
        assertThat(older.facts().get(0).change().percent()).isNull();
        assertThat(older.facts().get(0).change().unavailableReason()).isEqualTo("COMPARABLE_PERIOD_MISSING");
    }

    @Test void doesNotClaimUnverifiedStocksOrQuarterlyPeriodsAreCovered() {
        assertThat(catalog.read("600000.SH", FinancialPeriod.ANNUAL, null, List.of(FinancialMetric.REVENUE))).isNull();
        assertThat(catalog.read("700.HK", FinancialPeriod.QUARTER, null, List.of(FinancialMetric.REVENUE))).isNull();
        assertThat(catalog.read("600519.SH", FinancialPeriod.SEMIANNUAL, null, List.of(FinancialMetric.REVENUE))).isNull();
    }

    @Test void financialSnapshotsRequireTheirCurrentStockAndPinnedReportVersion() {
        var facts = catalog.read("700.HK", FinancialPeriod.ANNUAL, null, List.of(FinancialMetric.REVENUE));
        assertThat(catalog.matches(facts)).isTrue();
        var report = facts.reports().get(0);
        var altered = new FinancialFacts.ReportReference(report.metric(), report.title(), report.url(), "0".repeat(64),
                report.reportPeriod(), report.pdfPages());
        assertThat(catalog.matches(new FinancialFacts(facts.status(), facts.symbol(), facts.requestedSeries(), facts.selectedPeriod(),
                facts.availablePeriods(), facts.facts(), facts.missingMetrics(), facts.source(), facts.retrievedAt(),
                facts.disclosedOn(), facts.unavailableReason(), facts.note(), List.of(altered)))).isFalse();
    }

    @Test void serializesExactDecimalStringsForModelsAndJavascriptWithoutLosingCents() {
        var facts = catalog.read("600519.SH", FinancialPeriod.ANNUAL, null, List.of(FinancialMetric.REVENUE));
        var json = new ObjectMapper();
        var tree = json.readTree(json.writeValueAsString(facts));
        assertThat(tree.path("facts").get(0).path("current").path("amount").isString()).isTrue();
        assertThat(tree.path("facts").get(0).path("current").path("amount").asString()).isEqualTo("168838102514.79");
        var restored = json.readValue(json.writeValueAsString(facts), FinancialFacts.class);
        assertThat(restored).isEqualTo(facts);
        // A matching PDF hash is insufficient if saved amounts or units were changed.
        ((tools.jackson.databind.node.ObjectNode) tree.path("facts").get(0).path("current")).put("amount", "1.00");
        assertThat(catalog.matches(json.treeToValue(tree, FinancialFacts.class))).isFalse();
    }

    private static FinancialFacts.Fact fact(FinancialFacts facts, FinancialMetric metric) {
        return facts.facts().stream().filter(fact -> fact.metric() == metric).findFirst().orElseThrow();
    }
}
