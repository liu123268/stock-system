package com.stock.system.agent.service;

import com.stock.system.agent.vo.ResearchAgentResponseVO.Evidence;
import com.stock.system.stock.financial.FinancialFacts;
import com.stock.system.stock.financial.FinancialPeriod;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Rejects unsupported numeric values and unit conversions in financial paragraphs and their answer's missing information.
 * This checks provenance of literals, not whether the model associated each fact or direction correctly. */
final class ResearchFinancialAnswerNumbers {
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final String NUMBER = "[+-]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?";
    private static final Pattern NUMBERS = Pattern.compile(NUMBER);
    private static final Pattern MONEY = Pattern.compile("(?:(人民币|人民幣|港币|港幣|港元|美元|CNY|HKD|USD)\\s*)?(" + NUMBER
            + ")\\s*(百万元|百萬元|亿元|億元|万元|萬元|千元|港元|美元|人民币|人民幣|元|CNY|HKD|USD)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PERCENT = Pattern.compile("(" + NUMBER + ")\\s*%");
    private static final Pattern SCIENTIFIC = Pattern.compile("(?<![A-Za-z0-9_])\\d+(?:\\.\\d+)?[eE][+-]?\\d+(?![A-Za-z0-9_])");
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(?:FY\\s*(\\d{4})(?!\\d)|(\\d{4})\\s*(?:年|财年|財年))", Pattern.CASE_INSENSITIVE);
    private ResearchFinancialAnswerNumbers() {}
    private record Money(String amount, String unit, String currency) {}
    enum Rule { UNSUPPORTED_LITERAL, PERCENT_UNIT_REQUIRED, MONEY_BASIS_MISMATCH, UNSUPPORTED_PERCENTAGE,
        SCIENTIFIC_LITERAL, CONFLICTING_CURRENCY }
    static final class Rejected extends IllegalArgumentException {
        private final Rule rule;
        Rejected(Rule rule) { super(rule.name()); this.rule = rule; }
        Rule rule() { return rule; }
    }

    static void validate(String text, List<Evidence> cited, List<String> opinionTexts) {
        if (cited.stream().noneMatch(source -> source.financialFacts() != null)) return;
        var numbers = new HashSet<String>();
        var money = new HashSet<Money>();
        var percentages = new HashSet<String>();
        var financialPercentages = new HashSet<String>();
        var nonPercentageNumbers = new HashSet<String>();
        var expectedMissingYears = new HashSet<String>();
        for (var source : cited) {
            // Includes trusted years, dates, PDF pages and stock codes, which are not monetary claims.
            addNumbers(JSON.writeValueAsString(source), numbers);
            var withoutPercent = JSON.valueToTree(source);
            for (var fact : withoutPercent.path("financialFacts").path("facts")) {
                if (fact.path("change") instanceof ObjectNode change) change.remove("percent");
            }
            addNumbers(JSON.writeValueAsString(withoutPercent), nonPercentageNumbers);
            if (source.financialFacts() != null) {
                for (var fact : source.financialFacts().facts()) {
                    addValue(fact.current(), money); addValue(fact.comparable(), money);
                    if (fact.current() != null && fact.current().series() == FinancialPeriod.ANNUAL
                            && fact.current().year() > 1900 && fact.current().year() <= 2100 && fact.comparable() == null
                            && fact.change() != null && "COMPARABLE_PERIOD_MISSING".equals(fact.change().unavailableReason()))
                        expectedMissingYears.add(Integer.toString(fact.current().year() - 1));
                    if (fact.change() != null) {
                        if (fact.change().absoluteDifference() != null && fact.current() != null)
                            money.add(new Money(key(fact.change().absoluteDifference()), fact.current().unit(), fact.current().currency()));
                        if (fact.change().percent() != null) {
                            percentages.add(key(fact.change().percent()));
                            financialPercentages.add(key(fact.change().percent()));
                        }
                    }
                }
            }
            if (source.reportPassage() != null) addOriginalText(source.reportPassage().text(), numbers, money, percentages);
            if (source.quote() != null && source.quote().price() != null)
                money.add(new Money(key(source.quote().price()), "YUAN", source.quote().currency()));
            if (source.dailyPrices() != null) {
                var prices = source.dailyPrices();
                if (prices.prices() != null) prices.prices().stream().filter(Objects::nonNull).forEach(price ->
                        java.util.stream.Stream.of(price.open(), price.high(), price.low(), price.close()).filter(Objects::nonNull)
                                .forEach(value -> money.add(new Money(key(value), "YUAN", prices.currency()))));
                if (prices.nominalPriceDifference() != null) money.add(new Money(key(prices.nominalPriceDifference()), "YUAN", prices.currency()));
                if (prices.nominalPriceChangePercent() != null) percentages.add(key(prices.nominalPriceChangePercent()));
            }
        }
        opinionTexts.forEach(original -> addOriginalText(original, numbers, money, percentages));
        opinionTexts.forEach(original -> addNumbers(original, nonPercentageNumbers));
        String normalized = normalize(text);
        var missingYearPositions = new HashSet<String>();
        var years = YEAR.matcher(normalized);
        while (years.find()) {
            int group = years.group(1) == null ? 2 : 1;
            if (expectedMissingYears.contains(years.group(group))) missingYearPositions.add(years.start(group) + ":" + years.end(group));
        }
        if (SCIENTIFIC.matcher(normalized).find()) throw new Rejected(Rule.SCIENTIFIC_LITERAL);
        var tokens = NUMBERS.matcher(normalized);
        while (tokens.find()) {
            String value = key(tokens.group());
            if (!numbers.contains(value) && !missingYearPositions.contains(tokens.start() + ":" + tokens.end()))
                throw new Rejected(Rule.UNSUPPORTED_LITERAL);
            if (financialPercentages.contains(value) && !nonPercentageNumbers.contains(value)
                    && !normalized.substring(tokens.end()).stripLeading().startsWith("%"))
                throw new Rejected(Rule.PERCENT_UNIT_REQUIRED);
        }
        var amounts = MONEY.matcher(normalized);
        while (amounts.find()) {
            var selected = money(amounts.group(2), amounts.group(3), amounts.group(1));
            if (money.stream().noneMatch(allowed -> allowed.amount().equals(selected.amount()) && allowed.unit().equals(selected.unit())
                    && (selected.currency() == null || selected.currency().equals(allowed.currency()))))
                throw new Rejected(Rule.MONEY_BASIS_MISMATCH);
        }
        var percents = PERCENT.matcher(normalized);
        while (percents.find()) if (!percentages.contains(key(percents.group(1))))
            throw new Rejected(Rule.UNSUPPORTED_PERCENTAGE);
    }
    private static void addValue(FinancialFacts.Value value, Set<Money> money) {
        if (value != null && value.amount() != null) money.add(new Money(key(value.amount()), value.unit(), value.currency()));
    }
    private static void addOriginalText(String text, Set<String> numbers, Set<Money> money, Set<String> percentages) {
        String normalized = normalize(text); addNumbers(normalized, numbers);
        var amounts = MONEY.matcher(normalized);
        while (amounts.find()) money.add(money(amounts.group(2), amounts.group(3), amounts.group(1)));
        var percents = PERCENT.matcher(normalized);
        while (percents.find()) percentages.add(key(percents.group(1)));
    }
    private static Money money(String amount, String label, String prefix) {
        String normalized = label.replace('萬', '万').replace('億', '亿').replace('幣', '币').toUpperCase(Locale.ROOT);
        String unit = normalized.startsWith("百万") ? "MILLION_YUAN" : normalized.startsWith("亿") ? "HUNDRED_MILLION_YUAN"
                : normalized.startsWith("万") ? "TEN_THOUSAND_YUAN" : normalized.startsWith("千") ? "THOUSAND_YUAN" : "YUAN";
        String currency = currency(prefix);
        String suffixCurrency = currency(label);
        if (suffixCurrency != null) {
            if (currency != null && !currency.equals(suffixCurrency)) throw new Rejected(Rule.CONFLICTING_CURRENCY);
            currency = suffixCurrency;
        }
        return new Money(key(amount), unit, currency);
    }
    private static String currency(String label) {
        if (label == null) return null;
        return switch (label.replace('幣', '币').toUpperCase(Locale.ROOT)) {
            case "人民币", "CNY" -> "CNY";
            case "港币", "港元", "HKD" -> "HKD";
            case "美元", "USD" -> "USD";
            default -> null;
        };
    }
    private static void addNumbers(String text, Set<String> numbers) {
        var tokens = NUMBERS.matcher(normalize(text));
        while (tokens.find()) numbers.add(key(tokens.group()));
    }
    private static String normalize(String text) { return Normalizer.normalize(text, Normalizer.Form.NFKC); }
    private static String key(String text) { return key(new BigDecimal(text.replace(",", ""))); }
    private static String key(BigDecimal value) { return value.abs().stripTrailingZeros().toPlainString(); }
}
