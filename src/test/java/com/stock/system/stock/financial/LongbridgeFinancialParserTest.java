package com.stock.system.stock.financial;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LongbridgeFinancialParserTest {
    private final ObjectMapper json = new ObjectMapper();
    private final LongbridgeFinancialParser parser = new LongbridgeFinancialParser(json);
    private final Instant retrievedAt = Instant.parse("2026-10-14T04:50:00Z");
    private static final String SAMPLE = """
            {"IS":{"indicators":[{"currency":"CNY","entry":{"url":"ignore"},"periods":["af","qf","cumul"],
            "accounts":[{"field":"OperatingRevenue","name":"营业收入(CNY)","percent":false,
            "values":[{"period":"FY 2025","fp_end":"1767110400","value":"120.30","yoy":"999"},
            {"period":"FY 2024","fp_end":"1735574400","value":"100.25"}]}]}]}}
            """;
    private FinancialFacts parse(String input, FinancialPeriod series, String period) {
        return parser.parse("600519.SH", series, period, List.of(FinancialMetric.REVENUE),
                new FinancialSourceGateway.SourceReport(input, retrievedAt, "LONGBRIDGE_FINANCIAL_REPORT"));
    }

    @Test void preservesProviderValuesWithoutGuessingUnitsPublicationDateOrReportedYoy() {
        var result = parse(SAMPLE, FinancialPeriod.ANNUAL, null);
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.selectedPeriod()).isEqualTo("FY 2025");
        assertThat(result.availablePeriods()).containsExactly("FY 2025", "FY 2024");
        var fact = result.facts().get(0);
        assertThat(fact.current().amount()).isEqualByComparingTo("120.30");
        assertThat(fact.current().unit()).isEqualTo("UNKNOWN");
        assertThat(fact.comparable().period()).isEqualTo("FY 2024");
        assertThat(fact.change().percent()).isNull();
        assertThat(result.disclosedOn()).isNull();
        assertThat(json.writeValueAsString(result)).doesNotContain("999", "ignore", "entry", "yoy");
    }
    @Test void selectsPriorYearSameQuarterRatherThanPreviousRow() {
        var result = parse(SAMPLE.replace("FY 2025", "Q2 2026").replace("1767110400", "1782748800")
                .replace("FY 2024", "Q2 2025").replace("1735574400", "1751212800"), FinancialPeriod.QUARTER, null);
        assertThat(result.selectedPeriod()).isEqualTo("Q2 2026");
        assertThat(result.facts().get(0).comparable().period()).isEqualTo("Q2 2025");
    }
    @Test void requestedMissingPeriodDoesNotFallBackToLatest() {
        var result = parse(SAMPLE, FinancialPeriod.ANNUAL, "FY 2023");
        assertThat(result.status()).isEqualTo("PERIOD_UNAVAILABLE");
        assertThat(result.selectedPeriod()).isEqualTo("FY 2023");
        assertThat(result.facts()).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings = {"--", "NaN", "1e1000", "1,200"})
    void missingOrMalformedValuesDoNotBecomeZeroOrAnOlderPeriod(String value) {
        var result = parse(SAMPLE.replace("120.30", value), FinancialPeriod.ANNUAL, null);
        assertThat(result.status()).isEqualTo("NO_DATA");
        assertThat(result.selectedPeriod()).isEqualTo("FY 2025");
        assertThat(result.missingMetrics()).containsExactly(FinancialMetric.REVENUE);
        assertThat(result.facts()).isEmpty();
    }
    @Test void keepsRealZeroAndNegativeAmounts() {
        assertThat(parse(SAMPLE.replace("120.30", "0"), FinancialPeriod.ANNUAL, null).facts().get(0).current().amount())
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(parse(SAMPLE.replace("120.30", "-20"), FinancialPeriod.ANNUAL, null).facts().get(0).current().amount())
                .isEqualByComparingTo("-20");
    }
    @Test void acceptsSdkStringAndRawCliEnvelopeWithSameResult() {
        var expected = parse(SAMPLE, FinancialPeriod.ANNUAL, null);
        assertThat(parse(json.writeValueAsString(java.util.Map.of("list", SAMPLE)), FinancialPeriod.ANNUAL, null)).isEqualTo(expected);
        assertThat(parse("{\"code\":0,\"data\":{\"list\":" + SAMPLE + "}}", FinancialPeriod.ANNUAL, null)).isEqualTo(expected);
    }
    @ParameterizedTest @ValueSource(strings = {"[]", "{\"list\":[]}", "{\"code\":403001,\"data\":{}}", "{\"symbol\":\"700.HK\"}", "{\"report\":\"saf\"}"})
    void rejectsUnexpectedShapeFailedEnvelopeAndDifferentIdentity(String input) {
        assertThatThrownBy(() -> parse(input, FinancialPeriod.ANNUAL, null))
                .isInstanceOf(FinancialSourceGateway.SourceFailure.class).hasMessageContaining("UNEXPECTED_RESPONSE");
    }
    @Test void rejectsDuplicateFieldPeriodAndPercentMetric() {
        String duplicate = SAMPLE.replace("{\"period\":\"FY 2024\",\"fp_end\":\"1735574400\",\"value\":\"100.25\"}",
                "{\"period\":\"FY 2025\",\"fp_end\":\"1767110400\",\"value\":\"90\"}");
        assertThatThrownBy(() -> parse(duplicate, FinancialPeriod.ANNUAL, null)).isInstanceOf(FinancialSourceGateway.SourceFailure.class);
        assertThatThrownBy(() -> parse(SAMPLE.replace("\"percent\":false", "\"percent\":true"), FinancialPeriod.ANNUAL, null))
                .isInstanceOf(FinancialSourceGateway.SourceFailure.class);
    }
    @Test void excludesUnrecognizedPeriodsAndFutureDatesWithoutGuessingTime() {
        assertThat(parse(SAMPLE.replace("FY 2025", "9M 2025").replace("FY 2024", "9M 2024"), FinancialPeriod.ANNUAL, null).facts()).isEmpty();
        assertThat(parse(SAMPLE.replace("FY 2025", "FY 2027").replace("1767110400", "1830182400"), FinancialPeriod.ANNUAL, "FY 2027").status())
                .isEqualTo("PERIOD_UNAVAILABLE");
    }
    @Test void checksInputBoundsBeforeReadingSource() {
        assertThatThrownBy(() -> LongbridgeFinancialParser.requirePeriod(FinancialPeriod.QUARTER, "FY 2025")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LongbridgeFinancialParser.requireMetrics(List.of(FinancialMetric.REVENUE, FinancialMetric.REVENUE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse(" ".repeat(2_000_001), FinancialPeriod.ANNUAL, null)).isInstanceOf(FinancialSourceGateway.SourceFailure.class);
    }
    @Test void replaysFourSanitizedRealSamplesAsUnverifiedSourceData() throws Exception {
        try (var input = getClass().getResourceAsStream("/financial/2026-10-14-source-samples.json")) {
            assertThat(input).isNotNull();
            var samples = json.readTree(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            assertThat(samples).hasSize(4);
            for (var sample : samples) {
                var series = sample.path("report").asString().equals("af") ? FinancialPeriod.ANNUAL : FinancialPeriod.QUARTER;
                var result = parser.parse(sample.path("symbol").asString(), series, null, List.of(FinancialMetric.values()),
                        new FinancialSourceGateway.SourceReport(json.writeValueAsString(sample), retrievedAt, "LONGBRIDGE_FINANCIAL_REPORT"));
                assertThat(result.status()).isEqualTo("PARTIAL");
                assertThat(result.facts()).hasSize(5);
                assertThat(result.missingMetrics()).isEmpty();
                assertThat(result.disclosedOn()).isNull();
                assertThat(result.facts()).allSatisfy(fact -> {
                    assertThat(fact.current().amount()).isNotNull();
                    assertThat(fact.current().unit()).isEqualTo("UNKNOWN");
                    assertThat(fact.change().percent()).isNull();
                });
            }
        }
    }
}
