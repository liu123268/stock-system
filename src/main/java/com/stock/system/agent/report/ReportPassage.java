package com.stock.system.agent.report;

/** Positions use normalized Unicode code points, and PDF pages start at one. */
public record ReportPassage(String id, String symbol, String reportVersion, String reportTitle, int reportYear,
                            String sourceUrl, String language, String section, String sectionTitle, int pdfPage,
                            int charStart, int charEnd, String charUnit, String normalization, String text,
                            String textSha256, String status, String corpus) {}
