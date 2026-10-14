package com.stock.system.stock.financial;

/** This is a field allowlist, not a claim that a provider's profit definition is verified. */
public enum FinancialMetric {
    REVENUE("IS", "OperatingRevenue"), NET_PROFIT("IS", "NetProfit"),
    TOTAL_ASSETS("BS", "TotalAssets"), TOTAL_LIABILITIES("BS", "TotalLiability"),
    OPERATING_CASH_FLOW("CF", "NetOperateCashFlow");

    private final String statement;
    private final String sourceField;
    FinancialMetric(String statement, String sourceField) {
        this.statement = statement;
        this.sourceField = sourceField;
    }
    public String statement() { return statement; }
    public String sourceField() { return sourceField; }
}
