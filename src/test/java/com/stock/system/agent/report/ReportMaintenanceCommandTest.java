package com.stock.system.agent.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ReportMaintenanceCommandTest {
    @TempDir Path temporary;
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test void legacyCommandsKeepTheirIndependentMaintenanceDefaults() {
        for (String action : List.of("import", "retry", "restore")) {
            var options = ReportMaintenanceCommand.Options.parse(new String[]{action, temporary.resolve("library").toString(), "reviewed.json"});
            assertThat(options.binary()).isEqualTo(Path.of(".local/report-rag/qdrant-1.19.2/qdrant.exe").toAbsolutePath().normalize());
            assertThat(options.models()).isEqualTo(Path.of(".local/rag-preflight/model").toAbsolutePath().normalize());
            assertThat(options.runtime()).isEqualTo(temporary.resolve("maintenance-index"));
            assertThat(options.port()).isEqualTo(16336);
        }
    }

    @Test void explicitPathsPreserveSpacesUnicodeAndEqualsWithoutShellParsing() {
        Path binary = temporary.resolve("独立 工具=qdrant.exe"), models = temporary.resolve("中文 模型"), runtime = temporary.resolve("独立 索引");
        var options = ReportMaintenanceCommand.Options.parse(new String[]{"import", temporary.resolve("library").toString(), "reviewed.json",
                "--qdrant-binary=" + binary, "--model-directory=" + models, "--runtime-directory=" + runtime, "--port=16410"});
        assertThat(options.binary()).isEqualTo(binary);
        assertThat(options.models()).isEqualTo(models);
        assertThat(options.runtime()).isEqualTo(runtime);
        assertThat(options.port()).isEqualTo(16410);
    }

    @Test void unknownRepeatedEmptyAndInvalidPortsFailBeforeCreatingTheLibrary() throws Exception {
        Path library = temporary.resolve("unchanged");
        for (String[] extra : List.of(new String[]{"--endpoint=http://private.invalid"}, new String[]{"--port=16410", "--port=16411"},
                new String[]{"--qdrant-binary="}, new String[]{"--port=1023"}, new String[]{"--port=65536"},
                new String[]{"--port=16.4"}, new String[]{"--port=2147483648"}, new String[]{"--port", "16410"})) {
            var arguments = new java.util.ArrayList<>(List.of("import", library.toString(), "reviewed.json"));
            arguments.addAll(List.of(extra));
            var result = run(arguments.toArray(String[]::new));
            assertThat(result.exit()).isEqualTo(1);
            assertThat(result.output()).isEqualTo("{\"status\":\"FAILED\",\"reason\":\"MAINTENANCE_NOT_PUBLISHED\"}");
            assertThat(library).doesNotExist();
        }
    }

    @Test void missingOrMistypedPositionalArgumentsCannotBecomeFilesystemWrites() throws Exception {
        for (String[] args : List.of(new String[]{}, new String[]{"status"}, new String[]{"other", temporary.toString()},
                new String[]{"import", temporary.resolve("library").toString()}, new String[]{"import", temporary.resolve("library").toString(), "--port=16410"},
                new String[]{"status", "--model-directory=private"}, new String[]{"status", " "})) {
            assertThat(run(args).exit()).isEqualTo(1);
            assertThat(temporary.resolve("library")).doesNotExist();
        }
    }

    @Test void nestedOrIdenticalLibraryAndRuntimeDirectoriesAreRejectedAfterNormalization() {
        Path library = temporary.resolve("library");
        for (Path runtime : List.of(library, library.resolve("child"), temporary, library.resolve("other/.."))) {
            assertThatIllegalArgumentException().isThrownBy(() -> ReportMaintenanceCommand.Options.parse(new String[]{
                    "import", library.toString(), "reviewed.json", "--runtime-directory=" + runtime}));
        }
        assertThat(library).doesNotExist();
    }

    @Test void statusAndRemoveDoNotSilentlyIgnoreRuntimeOptions() throws Exception {
        Path library = temporary.resolve("library");
        assertThat(run("status", library.toString(), "--port=16410").exit()).isEqualTo(1);
        assertThat(run("remove", library.toString(), "document-key", "--port=16410").exit()).isEqualTo(1);
        assertThat(library).doesNotExist();
    }

    @Test void statusNeedsNeitherEmbeddingAssetsNorAnIndexProcess() throws Exception {
        Path library = temporary.resolve("maintenance-index");
        var result = run("status", library.toString());
        assertThat(result.exit()).isZero();
        assertThat(json.readTree(result.output()).path("status").asString()).isEqualTo("OK");
        assertThat(json.readTree(result.output()).path("reports").size()).isEqualTo(2);
        assertThat(library.resolve("owned-process.json")).doesNotExist();
        assertThat(library.resolve("qdrant.yaml")).doesNotExist();
    }

    @Test void anOccupiedCustomPortKeepsTheForeignListenerAndPreviousPublication() throws Exception {
        Path library = temporary.resolve("library"), runtime = temporary.resolve("runtime");
        Path candidate = candidate();
        var original = new ReportLibraryStore(library, new ReportCatalog()).active().all();
        var listener = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        listener.createContext("/", exchange -> {
            byte[] response = "foreign-listener".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        listener.start();
        try {
            var result = run("import", library.toString(), candidate.toString(), "--runtime-directory=" + runtime,
                    "--qdrant-binary=" + temporary.resolve("missing-binary"), "--model-directory=" + temporary.resolve("missing-model"),
                    "--port=" + listener.getAddress().getPort());
            assertThat(result.exit()).isEqualTo(1);
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + listener.getAddress().getPort())).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("foreign-listener");
            var reopened = new ReportLibraryStore(library, new ReportCatalog());
            assertThat(reopened.active().all()).isEqualTo(original);
            assertThat(reopened.statuses("600519.SH").get(0).status()).isEqualTo("FAILED");
            assertThat(reopened.statuses("600519.SH").get(0).usableVersion()).isTrue();
            assertThat(runtime.resolve("owned-process.json")).doesNotExist();
        } finally { listener.stop(0); }
    }

    @Test void customExecutableStillRequiresThePinnedIdentityAndNeverLeaksItsContents() throws Exception {
        Path library = temporary.resolve("library"), runtime = temporary.resolve("runtime"), binary = temporary.resolve("private-tool.exe");
        Files.writeString(binary, "private fake executable");
        int port;
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
        var result = run("import", library.toString(), candidate().toString(), "--runtime-directory=" + runtime,
                "--qdrant-binary=" + binary, "--port=" + port);
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.output()).doesNotContain("private", binary.toString());
        assertThat(runtime.resolve("owned-process.json")).doesNotExist();
        assertThat(new ReportLibraryStore(library, new ReportCatalog()).statuses("600519.SH").get(0).usableVersion()).isTrue();
        try (var socket = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) { assertThat(socket.isBound()).isTrue(); }
    }

    private Path candidate() throws Exception {
        Path candidate = temporary.resolve("reviewed.json");
        Files.write(candidate, json.writeValueAsBytes(java.util.Map.of("version", "maintenance-test", "passages",
                new ReportCatalog().forSymbol("600519.SH").subList(0, 1))));
        return candidate;
    }

    private Result run(String... args) throws Exception {
        var buffer = new ByteArrayOutputStream();
        try (var output = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            return new Result(ReportMaintenanceCommand.run(args, output), buffer.toString(StandardCharsets.UTF_8).strip());
        }
    }
    private record Result(int exit, String output) {}
}
