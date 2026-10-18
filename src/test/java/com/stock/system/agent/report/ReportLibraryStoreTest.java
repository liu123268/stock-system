package com.stock.system.agent.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ReportLibraryStoreTest {
    @TempDir Path directory;
    final ReportCatalog installed = new ReportCatalog();
    ReportLibraryStoreTest() throws Exception {}
    ReportLibraryStore store() { return new ReportLibraryStore(directory, installed); }
    ReportCatalog candidate() { return ReportLibraryStore.catalog(installed.forSymbol("600519.SH").subList(0, 1)); }

    @Test void bootstrapAndReopenKeepAnIdenticalPublication() {
        assertThat(store().active().all()).hasSize(14);
        assertThat(store().active().all()).isEqualTo(store().active().all());
        assertThat(store().statuses("600519.SH").get(0).status()).isEqualTo("AVAILABLE");
    }
    @Test void duplicateImportDoesNotRebuildOrCreateVersions() {
        var report = ReportLibraryStore.catalog(installed.forSymbol("600519.SH"));
        assertThat(store().publish(report, ignored -> { throw new AssertionError("Duplicate import must not rebuild"); }, false, false)).isTrue();
        assertThat(store().active().all()).hasSize(14);
    }
    @Test void failedUpdateKeepsTheOldActiveVersionAndExposesTheFailure() {
        var before = store().active().all();
        assertThatThrownBy(() -> store().publish(candidate(), ignored -> { throw new IllegalStateException("private-native-details"); }, false, false))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store().active().all()).isEqualTo(before);
        assertThat(store().statuses("600519.SH").get(0)).satisfies(state -> {
            assertThat(state.status()).isEqualTo("FAILED"); assertThat(state.usableVersion()).isTrue();
            assertThat(state.failure()).isEqualTo("INDEX_BUILD_FAILED");
        });
    }
    @Test void updateChangesCurrentSourcesButPreservesThePublishedHistoricalVersion() {
        var old = installed.forSymbol("600519.SH").get(1);
        assertThat(store().publish(candidate(), ignored -> {}, false, false)).isTrue();
        assertThat(store().active().forSymbol("600519.SH")).hasSize(1);
        assertThat(store().matches(old)).isTrue();
        assertThat(store().active().matches(old)).isFalse();
    }
    @Test void removalFencesALateSuccessAndFailureAndSurvivesRestart() {
        var library = store(); var ticket = library.begin(candidate(), false, false);
        assertThat(library.statuses("600519.SH").get(0).status()).isEqualTo("PROCESSING");
        library.remove(ticket.key());
        assertThat(library.complete(ticket)).isFalse(); library.fail(ticket);
        assertThat(store().active().forSymbol("600519.SH")).isEmpty();
        assertThat(store().matches(installed.forSymbol("600519.SH").get(0))).isFalse();
        assertThat(store().statuses("600519.SH").get(0).status()).isEqualTo("REMOVED");
    }
    @Test void explicitRetryPreventsAnOldWorkerFromPublishingOverItsReplacement() {
        var library = store(); var first = library.begin(candidate(), false, false);
        assertThatIllegalStateException().isThrownBy(() -> library.begin(candidate(), false, false));
        var retry = library.begin(candidate(), true, false);
        assertThat(library.complete(first)).isFalse();
        assertThat(library.complete(retry)).isTrue(); library.fail(first);
        assertThat(library.statuses("600519.SH").get(0).status()).isEqualTo("AVAILABLE");
    }
    @Test void ordinaryImportDoesNotSilentlyRestoreARemovedReport() {
        var library = store(); String key = ReportLibraryStore.documentKey(candidate().all().get(0));
        library.remove(key);
        assertThatIllegalStateException().isThrownBy(() -> library.begin(candidate(), false, false));
        assertThat(library.publish(candidate(), ignored -> {}, false, true)).isTrue();
        assertThat(library.active().forSymbol("600519.SH")).hasSize(1);
    }
    @Test void mixedStockInputIsRejectedWithoutChangingTheJournal() throws Exception {
        store().active(); byte[] previous = Files.readAllBytes(directory.resolve("library.json"));
        assertThatIllegalArgumentException().isThrownBy(() -> store().begin(installed, false, false));
        assertThat(Files.readAllBytes(directory.resolve("library.json"))).isEqualTo(previous);
    }
    @Test void corruptPublishedTextDoesNotFallBackToBuiltInSources() throws Exception {
        store().active(); Path file = directory.resolve("library.json");
        Files.writeString(file, Files.readString(file).replace("公司主要业务是", "伪造报告消息是"));
        assertThatIllegalStateException().isThrownBy(() -> store().active());
    }
    @Test void abandonedTemporaryWritesDoNotBecomeTheCurrentPublication() throws Exception {
        var before = store().active().all(); Files.writeString(directory.resolve("library-abandoned.tmp"), "truncated");
        assertThat(store().active().all()).isEqualTo(before);
    }
}
