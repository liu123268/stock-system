package com.stock.system.stock.financial;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialFactsTest {
    private FinancialFacts.Value value(int year, String amount, String currency, String unit,
                                       String profit, String currencyBasis) {
        return new FinancialFacts.Value("FY " + year, FinancialPeriod.ANNUAL, year, 1,
                LocalDate.of(year, 12, 31), new BigDecimal(amount), currency, unit, profit, currencyBasis);
    }
    private FinancialFacts.Value verified(int year, String amount) {
        return value(year, amount, "CNY", "BASE_CURRENCY_UNIT", "ATTRIBUTABLE_TO_OWNERS", "ISSUER_ORIGINAL");
    }

    @Test void calculatesChangeWithDecimalPrecisionFromVerifiedSameBasisValues() {
        var result = FinancialFacts.compare(FinancialMetric.REVENUE, verified(2025, "120.30"), verified(2024, "100.25"));
        assertThat(result.absoluteDifference()).isEqualByComparingTo("20.05");
        assertThat(result.percent()).isEqualByComparingTo("20.0000");
        assertThat(result.unavailableReason()).isNull();
    }
    @ParameterizedTest @ValueSource(strings = {"0", "-100"})
    void showsAbsoluteDifferenceWithoutOrdinaryGrowthRateForNonPositiveBase(String base) {
        var result = FinancialFacts.compare(FinancialMetric.NET_PROFIT, verified(2025, "20"), verified(2024, base));
        assertThat(result.absoluteDifference()).isEqualByComparingTo(new BigDecimal("20").subtract(new BigDecimal(base)));
        assertThat(result.percent()).isNull();
        assertThat(result.unavailableReason()).isEqualTo("NON_POSITIVE_BASE");
    }
    @Test void forbidsProviderDisplayCurrencyEvenWhenItsTwoCurrenciesMatch() {
        var result = FinancialFacts.compare(FinancialMetric.REVENUE,
                value(2025, "120", "HKD", "BASE_CURRENCY_UNIT", "UNKNOWN", "PROVIDER_DISPLAY_UNVERIFIED"),
                value(2024, "100", "HKD", "BASE_CURRENCY_UNIT", "UNKNOWN", "PROVIDER_DISPLAY_UNVERIFIED"));
        assertThat(result.absoluteDifference()).isNull();
        assertThat(result.percent()).isNull();
        assertThat(result.unavailableReason()).isEqualTo("CURRENCY_CONVERSION_UNVERIFIED");
    }
    @Test void distinguishesUnknownUnitMismatchedCurrencyAndUnknownProfit() {
        assertThat(FinancialFacts.compare(FinancialMetric.REVENUE,
                value(2025, "120", "CNY", "UNKNOWN", "UNKNOWN", "ISSUER_ORIGINAL"), verified(2024, "100")).unavailableReason())
                .isEqualTo("UNIT_OR_CURRENCY_UNVERIFIED");
        assertThat(FinancialFacts.compare(FinancialMetric.REVENUE,
                value(2025, "120", "HKD", "BASE_CURRENCY_UNIT", "UNKNOWN", "ISSUER_ORIGINAL"), verified(2024, "100")).unavailableReason())
                .isEqualTo("UNIT_OR_CURRENCY_MISMATCH");
        assertThat(FinancialFacts.compare(FinancialMetric.NET_PROFIT,
                value(2025, "120", "CNY", "BASE_CURRENCY_UNIT", "UNKNOWN", "ISSUER_ORIGINAL"), verified(2024, "100")).unavailableReason())
                .isEqualTo("PROFIT_BASIS_UNVERIFIED");
    }
    @Test void neverComparesDifferentSeriesOrNonConsecutiveYears() {
        var quarter = new FinancialFacts.Value("Q4 2025", FinancialPeriod.QUARTER, 2025, 4,
                LocalDate.of(2025, 12, 31), new BigDecimal("120"), "CNY", "BASE_CURRENCY_UNIT", "UNKNOWN", "ISSUER_ORIGINAL");
        assertThat(FinancialFacts.compare(FinancialMetric.REVENUE, quarter, verified(2024, "100")).unavailableReason())
                .isEqualTo("PERIOD_BASIS_MISMATCH");
        assertThat(FinancialFacts.compare(FinancialMetric.REVENUE, verified(2025, "120"), verified(2023, "100")).unavailableReason())
                .isEqualTo("PERIOD_BASIS_MISMATCH");
        assertThat(FinancialFacts.compare(FinancialMetric.REVENUE, verified(2025, "120"), null).unavailableReason())
                .isEqualTo("COMPARABLE_PERIOD_MISSING");
    }
}
