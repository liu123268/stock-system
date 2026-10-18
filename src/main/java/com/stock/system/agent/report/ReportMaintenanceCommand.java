package com.stock.system.agent.report;

import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** System-maintainer entry only. No web server, business DB, provider or paid model is started. */
public final class ReportMaintenanceCommand {
    public static void main(String[] args) {
        try {
            if (args.length < 2 || !Set.of("status", "import", "retry", "restore", "remove").contains(args[0]))
                throw new IllegalArgumentException("Use action library-directory [reviewed-json or document-key]");
            String action = args[0];
            if (args.length != (action.equals("status") ? 2 : 3)) throw new IllegalArgumentException("Unexpected arguments");
            var installed = new ReportCatalog();
            var library = new ReportLibraryStore(Path.of(args[1]), installed);
            if (action.equals("remove")) library.remove(args[2]);
            if (Set.of("import", "retry", "restore").contains(action)) {
                Path source = Path.of(args[2]);
                if (Files.size(source) > 1_000_000) throw new IllegalArgumentException("Reviewed input too large");
                var candidate = new ReportCatalog(Files.readAllBytes(source));
                Path runtime = Path.of(args[1]).toAbsolutePath().resolveSibling("maintenance-index");
                try (var vectors = new ReportVectorStore(installed, true, ".local/report-rag/qdrant-1.19.2/qdrant.exe",
                        ".local/rag-preflight/model", runtime.toString(), 16336)) {
                    if (!library.publish(candidate, vectors::prepare, action.equals("retry"), action.equals("restore")))
                        throw new IllegalStateException("Publication was cancelled or superseded");
                }
            }
            var states = library.statuses(null);
            System.out.println(JsonMapper.builder().build().writeValueAsString(Map.of("status", "OK", "reports", states)));
        } catch (Exception failure) {
            System.out.println("{\"status\":\"FAILED\",\"reason\":\"MAINTENANCE_NOT_PUBLISHED\"}");
            System.exit(1);
        }
    }
}
