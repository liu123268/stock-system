package com.stock.system.agent.report;

import java.io.PrintStream;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** System-maintainer entry only. No web server, business DB, provider or paid model is started. */
public final class ReportMaintenanceCommand {
    public static void main(String[] args) {
        int result = run(args, System.out);
        if (result != 0) System.exit(result);
    }

    static int run(String[] args, PrintStream output) {
        try {
            var options = Options.parse(args);
            String action = options.action();
            var installed = new ReportCatalog();
            var library = new ReportLibraryStore(options.library(), installed);
            if (action.equals("remove")) library.remove(options.operand());
            if (Set.of("import", "retry", "restore").contains(action)) {
                Path source = Path.of(options.operand());
                if (Files.size(source) > 1_000_000) throw new IllegalArgumentException("Reviewed input too large");
                var candidate = new ReportCatalog(Files.readAllBytes(source));
                try (var vectors = new ReportVectorStore(installed, true, options.binary().toString(),
                        options.models().toString(), options.runtime().toString(), options.port())) {
                    if (!library.publish(candidate, vectors::prepare, action.equals("retry"), action.equals("restore")))
                        throw new IllegalStateException("Publication was cancelled or superseded");
                }
            }
            var states = library.statuses(null);
            output.println(JsonMapper.builder().build().writeValueAsString(Map.of("status", "OK", "reports", states)));
            return 0;
        } catch (Exception failure) {
            output.println("{\"status\":\"FAILED\",\"reason\":\"MAINTENANCE_NOT_PUBLISHED\"}");
            return 1;
        }
    }

    /** Runtime settings are maintainer arguments, never model tools or a remote endpoint. */
    record Options(String action, Path library, String operand, Path binary, Path models, Path runtime, int port) {
        static Options parse(String[] args) {
            if (args.length < 2 || !Set.of("status", "import", "retry", "restore", "remove").contains(args[0]))
                throw new IllegalArgumentException("Unsupported maintenance command");
            String action = args[0];
            int positional = action.equals("status") ? 2 : 3;
            if (args.length < positional) throw new IllegalArgumentException("Missing maintenance arguments");
            Path library = path(args[1]);
            if (library.getParent() == null) throw new IllegalArgumentException("A report library directory is required");
            String operand = positional == 3 ? args[2] : null;
            if (operand != null && (operand.isBlank() || operand.startsWith("--")))
                throw new IllegalArgumentException("Missing maintenance operand");
            var values = new HashMap<String, String>();
            for (int index = positional; index < args.length; index++) {
                if (!Set.of("import", "retry", "restore").contains(action))
                    throw new IllegalArgumentException("This command does not start an index");
                String argument = args[index];
                int separator = argument.indexOf('=');
                if (!argument.startsWith("--") || separator < 3)
                    throw new IllegalArgumentException("Unsupported maintenance option");
                String name = argument.substring(2, separator), value = argument.substring(separator + 1);
                if (!Set.of("qdrant-binary", "model-directory", "runtime-directory", "port").contains(name)
                        || value.isBlank() || values.putIfAbsent(name, value) != null)
                    throw new IllegalArgumentException("Unknown, empty or repeated maintenance option");
            }
            String portValue = values.getOrDefault("port", "16336");
            if (!portValue.matches("[0-9]{4,5}")) throw new IllegalArgumentException("Invalid maintenance port");
            int port = Integer.parseInt(portValue);
            if (port < 1024 || port > 65535) throw new IllegalArgumentException("Invalid maintenance port");
            Path runtime = values.containsKey("runtime-directory") ? path(values.get("runtime-directory"))
                    : library.resolveSibling("maintenance-index");
            if (Set.of("import", "retry", "restore").contains(action)
                    && (library.equals(runtime) || library.startsWith(runtime) || runtime.startsWith(library)))
                throw new IllegalArgumentException("The library and temporary index must be separate");
            return new Options(action, library, operand,
                    path(values.getOrDefault("qdrant-binary", ".local/report-rag/qdrant-1.19.2/qdrant.exe")),
                    path(values.getOrDefault("model-directory", ".local/rag-preflight/model")), runtime, port);
        }
        private static Path path(String value) {
            if (value.isBlank() || value.startsWith("--")) throw new IllegalArgumentException("Empty maintenance path");
            return Path.of(value).toAbsolutePath().normalize();
        }
    }
}
