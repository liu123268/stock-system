package com.stock.system.agent.report;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded local BM25, rank fusion and lexical coverage reranking. No model or remote service. */
public final class ReportRetrieval {
    public static final String VERSION = "report-retrieval-20261018-v1";
    public static final int CANDIDATE_LIMIT = 12;
    public static final int RESULT_LIMIT = 3;
    private static final double RRF_K = 60;
    private static final Pattern WORDS = Pattern.compile("\\p{IsHan}+|[a-z0-9]+");
    // General question framing is not a signal that a report contains the requested fact.
    private static final Set<String> STOP = Set.of("公司", "报告", "年报", "年度", "怎样", "如何", "什么", "哪些", "是否", "有没有",
            "分别", "多少", "说明", "介绍", "列出", "提到", "给出", "的收", "的增", "了吗", "是在", "的是");

    private ReportRetrieval() {}
    public enum Mode { VECTOR, KEYWORD, HYBRID }
    public record Hit(ReportPassage passage, double rankScore, Double vectorScore, double keywordScore, double coverage) {}
    public record Result(List<Hit> hits, int vectorCandidates, int keywordCandidates) {
        public List<ReportPassage> passages() { return hits.stream().map(Hit::passage).toList(); }
    }

    public static void validateScope(String query, String symbol, Integer year) {
        if (query == null || query.isBlank() || query.codePointCount(0, query.length()) > 240
                || symbol == null || !symbol.matches("[0-9]{6}\\.(SH|SZ)|[0-9]{1,5}\\.HK")
                || year != null && (year < 2000 || year > 2100))
            throw new IllegalArgumentException("Invalid bounded report query");
    }

    public static Result rank(String query, List<ReportPassage> scoped, List<ReportVectorStore.Candidate> dense,
                              Mode mode, boolean rerank) {
        if (query == null || query.isBlank() || query.codePointCount(0, query.length()) > 240
                || scoped.size() > 200 || dense.size() > CANDIDATE_LIMIT || mode == null)
            throw new IllegalArgumentException("Unsupported report ranking input");
        var canonical = new LinkedHashMap<String, ReportPassage>();
        for (var row : scoped) if (canonical.put(row.id(), row) != null) throw new IllegalArgumentException("Duplicate report identity");
        // Every signal is resolved against the same publication snapshot, never against vector text.
        var vectorScores = new LinkedHashMap<String, Double>();
        for (var hit : dense) {
            if (!hit.passage().equals(canonical.get(hit.passage().id())) || !Double.isFinite(hit.score()) || hit.score() < 0.45
                    || vectorScores.put(hit.passage().id(), hit.score()) != null)
                throw new IllegalStateException("Report candidate does not match the bound publication");
        }
        var lexical = new Lexical(query, scoped);
        var keywords = scoped.stream().filter(row -> lexical.accepts(row.id()))
                .sorted(Comparator.<ReportPassage>comparingDouble(row -> lexical.score(row.id())).reversed().thenComparing(ReportPassage::id))
                .limit(CANDIDATE_LIMIT).map(ReportPassage::id).toList();
        var scores = new LinkedHashMap<String, Double>();
        if (mode == Mode.VECTOR) vectorScores.forEach(scores::put);
        if (mode == Mode.KEYWORD) keywords.forEach(id -> scores.put(id, lexical.score(id)));
        if (mode == Mode.HYBRID) { fuse(scores, List.copyOf(vectorScores.keySet())); fuse(scores, keywords); }
        var hits = scores.entrySet().stream().map(entry -> new Hit(canonical.get(entry.getKey()), entry.getValue(),
                vectorScores.get(entry.getKey()), lexical.score(entry.getKey()), lexical.coverage(entry.getKey())));
        Comparator<Hit> order = Comparator.comparingDouble(Hit::rankScore).reversed().thenComparing(hit -> hit.passage().id());
        if (rerank) order = Comparator.comparingDouble(Hit::coverage).reversed().thenComparing(order);
        return new Result(hits.sorted(order).limit(RESULT_LIMIT).toList(), vectorScores.size(), keywords.size());
    }

    private static void fuse(Map<String, Double> scores, List<String> ranked) {
        for (int i = 0; i < ranked.size(); i++) scores.merge(ranked.get(i), 1.0 / (RRF_K + i + 1), Double::sum);
    }

    /** CJK bigrams and whole Latin identifiers; no dictionary or simplified/traditional rewriting. */
    static Map<String, Integer> terms(String value) {
        var counts = new HashMap<String, Integer>();
        var matcher = WORDS.matcher(Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group(); int[] points = word.codePoints().toArray();
            if (Character.UnicodeScript.of(points[0]) == Character.UnicodeScript.HAN) {
                for (int i = 0; i + 1 < points.length; i++) {
                    String term = new String(points, i, 2);
                    if (!STOP.contains(term)) counts.merge(term, 1, Integer::sum);
                }
            } else if (word.length() >= 2) counts.merge(word, 1, Integer::sum);
        }
        return counts;
    }

    private static final class Lexical {
        private final Map<String, Double> scores = new HashMap<>(), coverage = new HashMap<>();
        private final Map<String, Integer> matches = new HashMap<>();
        private final Set<String> query;
        Lexical(String text, List<ReportPassage> rows) {
            query = terms(text).keySet();
            var counts = new LinkedHashMap<String, Map<String, Integer>>();
            rows.forEach(row -> counts.put(row.id(), terms(row.sectionTitle() + " " + row.text())));
            double average = Math.max(1, counts.values().stream().mapToInt(Lexical::length).average().orElse(1));
            for (var row : rows) {
                var document = counts.get(row.id()); double score = 0, covered = 0, total = 0; int matched = 0;
                for (String term : query) {
                    long frequency = counts.values().stream().filter(item -> item.containsKey(term)).count();
                    double idf = Math.log(1 + (rows.size() - frequency + 0.5) / (frequency + 0.5));
                    total += idf;
                    int tf = document.getOrDefault(term, 0);
                    if (tf > 0) {
                        matched++; covered += idf;
                        score += idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * length(document) / average));
                    }
                }
                scores.put(row.id(), score); matches.put(row.id(), matched);
                coverage.put(row.id(), total == 0 ? 0 : covered / total);
            }
        }
        private static int length(Map<String, Integer> counts) { return counts.values().stream().mapToInt(Integer::intValue).sum(); }
        double score(String id) { return scores.getOrDefault(id, 0.0); }
        double coverage(String id) { return coverage.getOrDefault(id, 0.0); }
        boolean accepts(String id) {
            // A single English product name is useful, a single generic Chinese bigram is insufficient.
            boolean identifier = query.size() == 1 && query.iterator().next().matches("[a-z][a-z0-9]{2,}");
            return matches.getOrDefault(id, 0) >= (identifier ? 1 : 2) && coverage(id) >= 0.20;
        }
    }
}
