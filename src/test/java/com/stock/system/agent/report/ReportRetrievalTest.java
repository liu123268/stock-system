package com.stock.system.agent.report;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportRetrievalTest {
    private final ReportCatalog catalog = new ReportCatalog();
    ReportRetrievalTest() throws Exception {}

    @Test void keywordCanRescueAnExactProductWithoutAVectorCandidate() {
        var result = ReportRetrieval.rank("Supercell", catalog.forSymbol("00700.HK"), List.of(), ReportRetrieval.Mode.HYBRID, false);
        assertThat(result.passages()).extracting(ReportPassage::section).containsExactly("tx-games");
        assertThat(result.hits().get(0).vectorScore()).isNull();
    }
    @Test void weakSharedQuestionFramingDoesNotCreateEvidence() {
        for (String question : List.of("公司报告说明了载人飞船发射时间吗", "公司新增订单总金额和未交付合同余额分别是多少")) {
            assertThat(ReportRetrieval.rank(question, catalog.forSymbol("600519.SH"), List.of(), ReportRetrieval.Mode.HYBRID, true).passages()).isEmpty();
        }
    }
    @Test void rerankingCanPromoteLexicalSupportWithoutInventingOrChangingPassages() {
        var rows = catalog.forSymbol("600519.SH");
        var business = rows.stream().filter(row -> row.section().equals("mt-business")).findFirst().orElseThrow();
        var risk = rows.stream().filter(row -> row.section().equals("mt-risks")).findFirst().orElseThrow();
        var dense = List.of(new ReportVectorStore.Candidate(business, 0.8), new ReportVectorStore.Candidate(risk, 0.7));
        assertThat(ReportRetrieval.rank("宏观经济风险安全风险", rows, dense, ReportRetrieval.Mode.VECTOR, false).passages()).startsWith(business);
        assertThat(ReportRetrieval.rank("宏观经济风险安全风险", rows, dense, ReportRetrieval.Mode.VECTOR, true).passages()).containsExactly(risk, business);
    }
    @Test void foreignOrDuplicateVectorCandidatesAreRejectedBeforeFusion() {
        var rows = catalog.forSymbol("600519.SH");
        var foreign = new ReportVectorStore.Candidate(catalog.forSymbol("00700.HK").get(0), 0.9);
        assertThatIllegalStateException().isThrownBy(() -> ReportRetrieval.rank("主要业务", rows, List.of(foreign), ReportRetrieval.Mode.HYBRID, true));
        var same = new ReportVectorStore.Candidate(rows.get(0), 0.9);
        assertThatIllegalStateException().isThrownBy(() -> ReportRetrieval.rank("主要业务", rows, List.of(same, same), ReportRetrieval.Mode.HYBRID, false));
        for (double score : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 0.44}) {
            assertThatIllegalStateException().isThrownBy(() -> ReportRetrieval.rank("主要业务", rows,
                    List.of(new ReportVectorStore.Candidate(rows.get(0), score)), ReportRetrieval.Mode.HYBRID, false));
        }
    }
    @Test void repeatedTermsAreBoundedAndResultsAreDeterministicAndDeduplicated() {
        var rows = catalog.forSymbol("00700.HK");
        var dense = rows.stream().map(row -> new ReportVectorStore.Candidate(row, 0.7)).toList();
        var first = ReportRetrieval.rank("服务收入增长", rows, dense, ReportRetrieval.Mode.HYBRID, true);
        assertThat(first.hits()).hasSize(3);
        assertThat(first.passages()).extracting(ReportPassage::id).doesNotHaveDuplicates();
        assertThat(ReportRetrieval.rank("服务收入增长", rows, dense, ReportRetrieval.Mode.HYBRID, true)).isEqualTo(first);
        assertThatIllegalArgumentException().isThrownBy(() -> ReportRetrieval.rank("问".repeat(241), rows, dense, ReportRetrieval.Mode.HYBRID, false));
        assertThat(ReportRetrieval.terms("𠮷野家 AI Ｓｕｐｅｒｃｅｌｌ")).containsKeys("𠮷野", "野家", "ai", "supercell");
    }
    @Test void disabledOrInvalidScopeNeverStartsTheLocalStore() {
        var vectors = mock(ReportVectorStore.class);
        var retrieval = new ReportRetriever(catalog, vectors, ReportRetrieval.Mode.HYBRID, true);
        assertThatIllegalStateException().isThrownBy(() -> retrieval.search("主要业务", "600519.SH", null));
        assertThatIllegalArgumentException().isThrownBy(() -> retrieval.search("主要业务", "600519.SH' || symbol != 'x", null));
        verify(vectors, never()).candidates(anyString(), anyString(), any(), anyInt());
    }
    @Test void wrongYearAndStockCannotFallBackToAnotherPublication() {
        var vectors = mock(ReportVectorStore.class); when(vectors.enabled()).thenReturn(true);
        var retrieval = new ReportRetriever(catalog, vectors, ReportRetrieval.Mode.HYBRID, true);
        assertThat(retrieval.search("主要业务", "600519.SH", 2024)).isEmpty();
        assertThat(retrieval.search("主要业务", "000759.SZ", null)).isEmpty();
        verify(vectors, never()).candidates(anyString(), anyString(), any(), anyInt());
    }
    @Test void keywordModeDoesNotSilentlyStartAnEmbeddingProcess() {
        var vectors = mock(ReportVectorStore.class); when(vectors.enabled()).thenReturn(true);
        var retrieval = new ReportRetriever(catalog, vectors, ReportRetrieval.Mode.KEYWORD, false);
        assertThat(retrieval.search("Supercell", "00700.HK", 2025)).extracting(ReportPassage::section).containsExactly("tx-games");
        verify(vectors, never()).candidates(anyString(), anyString(), any(), anyInt());
    }
}
