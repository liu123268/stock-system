package com.stock.system.agent.report;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class ReportCatalogTest {
    @Test void restoresOnlyTheExactReviewedTextVersionAndPosition() throws Exception {
        var catalog = new ReportCatalog();
        assertThat(catalog.all()).hasSize(14);
        assertThat(catalog.forSymbol("000759.SZ")).isEmpty();
        var row = catalog.forSymbol("600519.SH").get(0);
        assertThat(catalog.matches(row)).isTrue();
        var removed = new ReportCatalog("{\"version\":\"removed\",\"passages\":[]}".getBytes(StandardCharsets.UTF_8));
        assertThat(removed.matches(row)).isFalse();
        assertThat(catalog.all()).allSatisfy(passage -> {
            assertThat(passage.charEnd() - passage.charStart()).isEqualTo(passage.text().codePointCount(0, passage.text().length()));
            assertThat(passage.pdfPage()).isPositive();
        });
    }
    @Test void refusesTamperedTextEvenIfThePdfHashIsUnchanged() throws Exception {
        byte[] content = new org.springframework.core.io.ClassPathResource("reports/reviewed-passages.json").getContentAsByteArray();
        String altered = new String(content, StandardCharsets.UTF_8).replace("公司主要业务是", "伪造财务消息是");
        assertThat(altered).isNotEqualTo(new String(content, StandardCharsets.UTF_8));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReportCatalog(altered.getBytes(StandardCharsets.UTF_8)));
    }
}
