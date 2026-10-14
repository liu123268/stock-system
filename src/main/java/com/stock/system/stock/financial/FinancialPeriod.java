package com.stock.system.stock.financial;

/** Only report series observed in the A/HK source preflight are accepted. */
public enum FinancialPeriod {
    ANNUAL("af"), SEMIANNUAL("saf"), QUARTER("qf");

    private final String sourceCode;
    FinancialPeriod(String sourceCode) { this.sourceCode = sourceCode; }
    public String sourceCode() { return sourceCode; }
}
