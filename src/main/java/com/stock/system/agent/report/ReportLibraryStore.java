package com.stock.system.agent.report;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/** Local public-report publication journal. A generation token fences late workers and removals. */
public final class ReportLibraryStore {
    private final Path directory;
    private final ReportCatalog installed;
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    public ReportLibraryStore(Path directory, ReportCatalog installed) {
        this.directory = directory.toAbsolutePath().normalize(); this.installed = installed;
    }
    public static String documentKey(ReportPassage row) {
        return ReportCatalog.sha256((row.symbol() + ":" + row.reportYear() + ":" + row.language()).getBytes(StandardCharsets.UTF_8));
    }
    public ReportCatalog active() {
        var passages = new ArrayList<ReportPassage>();
        for (var doc : state().documents().values()) {
            if (!"REMOVED".equals(doc.status()) && doc.activeVersion() != null)
                passages.addAll(decode(doc.versions().get(doc.activeVersion())).all());
        }
        return catalog(passages);
    }
    public boolean matches(ReportPassage snapshot) {
        if (snapshot == null) return false;
        var doc = state().documents().get(documentKey(snapshot));
        return doc != null && !"REMOVED".equals(doc.status())
                && doc.versions().values().stream().anyMatch(value -> decode(value).matches(snapshot));
    }
    public List<Status> statuses(String symbol) {
        return state().documents().values().stream().filter(doc -> symbol == null || doc.symbol().equals(symbol))
                .map(doc -> new Status(doc.key(), doc.title(), doc.year(), doc.status(),
                        !"REMOVED".equals(doc.status()) && doc.activeVersion() != null, doc.failure())).toList();
    }
    public Ticket begin(ReportCatalog candidate, boolean replacePending, boolean restoreRemoved) {
        var rows = candidate.all();
        if (rows.isEmpty()) throw new IllegalArgumentException("A report must contain reviewed passages");
        var first = rows.get(0); String key = documentKey(first);
        if (rows.stream().anyMatch(row -> !key.equals(documentKey(row)) || !row.reportVersion().equals(first.reportVersion())
                || !row.reportTitle().equals(first.reportTitle()) || !row.sourceUrl().equals(first.sourceUrl())))
            throw new IllegalArgumentException("Import exactly one consistent report version");
        String content = encode(candidate);
        String version = ReportCatalog.sha256(content.getBytes(StandardCharsets.UTF_8));
        return mutate(state -> {
            var previous = state.documents().getOrDefault(key, new Entry(key, first.symbol(), first.reportTitle(), first.reportYear(),
                    "EMPTY", 0, null, null, null, null, Map.of()));
            if ("REMOVED".equals(previous.status()) && !restoreRemoved) throw new IllegalStateException("Explicit restoration is required");
            if (previous.token() != null && !replacePending) throw new IllegalStateException("Report processing is already in progress");
            if ("AVAILABLE".equals(previous.status()) && version.equals(previous.activeVersion()))
                return new Ticket(key, null, previous.generation(), version, content);
            var ticket = new Ticket(key, UUID.randomUUID().toString(), previous.generation() + 1, version, content);
            state.documents().put(key, new Entry(key, first.symbol(), first.reportTitle(), first.reportYear(), "PROCESSING",
                    ticket.generation(), previous.activeVersion(), ticket.token(), version, null, previous.versions()));
            return ticket;
        });
    }
    public boolean complete(Ticket ticket) {
        if (ticket.token() == null) return true;
        var candidate = decode(ticket.catalog());
        if (!ticket.version().equals(ReportCatalog.sha256(ticket.catalog().getBytes(StandardCharsets.UTF_8)))
                || candidate.all().isEmpty() || candidate.all().stream().anyMatch(row -> !documentKey(row).equals(ticket.key())))
            throw new IllegalArgumentException("Publication identity changed");
        return mutate(state -> {
            var previous = state.documents().get(ticket.key());
            if (!current(previous, ticket)) return false;
            var versions = new LinkedHashMap<>(previous.versions()); versions.put(ticket.version(), ticket.catalog());
            if (versions.size() > 20) throw new IllegalStateException("Report version retention limit reached; no version was discarded");
            state.documents().put(ticket.key(), new Entry(previous.key(), previous.symbol(), previous.title(), previous.year(),
                    "AVAILABLE", previous.generation(), ticket.version(), null, null, null, versions));
            return true;
        });
    }
    public void fail(Ticket ticket) {
        if (ticket.token() == null) return;
        mutate(state -> {
            var previous = state.documents().get(ticket.key());
            if (current(previous, ticket)) state.documents().put(ticket.key(), new Entry(previous.key(), previous.symbol(), previous.title(), previous.year(),
                    "FAILED", previous.generation(), previous.activeVersion(), null, null, "INDEX_BUILD_FAILED", previous.versions()));
            return null;
        });
    }
    public boolean publish(ReportCatalog candidate, Consumer<ReportCatalog> buildIndex, boolean replacePending, boolean restoreRemoved) {
        Ticket ticket = begin(candidate, replacePending, restoreRemoved);
        if (ticket.token() == null) return true;
        try { buildIndex.accept(candidate); return complete(ticket); }
        catch (RuntimeException failure) { fail(ticket); throw failure; }
    }
    public void remove(String key) {
        mutate(state -> {
            var previous = state.documents().get(key);
            if (previous == null) throw new IllegalArgumentException("Unknown report");
            state.documents().put(key, new Entry(previous.key(), previous.symbol(), previous.title(), previous.year(), "REMOVED",
                    previous.generation() + 1, previous.activeVersion(), null, null, null, previous.versions()));
            return null;
        });
    }
    private static boolean current(Entry entry, Ticket ticket) {
        return entry != null && "PROCESSING".equals(entry.status()) && entry.generation() == ticket.generation()
                && Objects.equals(entry.token(), ticket.token()) && Objects.equals(entry.pendingVersion(), ticket.version());
    }
    private State state() {
        if (!Files.exists(directory.resolve("library.json"))) mutate(unused -> null);
        try { return read(); } catch (java.io.IOException failure) { throw new IllegalStateException("Report library is unavailable", failure); }
    }
    private State read() throws java.io.IOException {
        Path file = directory.resolve("library.json");
        if (!Files.exists(file)) {
            var entries = new LinkedHashMap<String, Entry>();
            for (var group : installed.all().stream().collect(java.util.stream.Collectors.groupingBy(ReportLibraryStore::documentKey)).entrySet()) {
                var first = group.getValue().get(0); String content = encode(catalog(group.getValue()));
                String version = ReportCatalog.sha256(content.getBytes(StandardCharsets.UTF_8));
                entries.put(group.getKey(), new Entry(group.getKey(), first.symbol(), first.reportTitle(), first.reportYear(),
                        "AVAILABLE", 1, version, null, null, null, Map.of(version, content)));
            }
            return new State(1, 0, entries);
        }
        if (Files.size(file) > MAX_BYTES) throw new IllegalStateException("Report library exceeds the bounded local limit");
        var state = json.readValue(Files.readAllBytes(file), State.class);
        if (state.schema() != 1 || state.documents() == null || state.documents().size() > 50)
            throw new IllegalStateException("Unsupported report library state");
        for (var entry : state.documents().entrySet()) {
            var doc = entry.getValue();
            if (!entry.getKey().equals(doc.key()) || !Set.of("AVAILABLE", "PROCESSING", "FAILED", "REMOVED").contains(doc.status())
                    || doc.versions() == null || doc.versions().size() > 20 || doc.activeVersion() != null && !doc.versions().containsKey(doc.activeVersion())
                    || (doc.token() != null) != "PROCESSING".equals(doc.status())) throw new IllegalStateException("Corrupt report publication state");
            for (var version : doc.versions().entrySet()) {
                if (!version.getKey().equals(ReportCatalog.sha256(version.getValue().getBytes(StandardCharsets.UTF_8)))
                        || decode(version.getValue()).all().stream().anyMatch(row -> !documentKey(row).equals(doc.key())
                        || !row.symbol().equals(doc.symbol()) || row.reportYear() != doc.year()))
                    throw new IllegalStateException("Corrupt published report version");
            }
        }
        return state;
    }
    private synchronized <T> T mutate(Function<State, T> operation) {
        try {
            Files.createDirectories(directory);
            try (var channel = FileChannel.open(directory.resolve("library.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.tryLock()) {
                if (lock == null) throw new IllegalStateException("Another report maintenance operation is running");
                State state = read(); T result = operation.apply(state);
                byte[] bytes = json.writeValueAsBytes(new State(1, state.revision() + 1, state.documents()));
                if (bytes.length > MAX_BYTES) throw new IllegalStateException("Report library capacity reached");
                Path temporary = directory.resolve("library-" + UUID.randomUUID() + ".tmp");
                try {
                    try (var output = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) output.write(buffer); output.force(true);
                    }
                    Files.move(temporary, directory.resolve("library.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(temporary); }
                return result;
            }
        } catch (java.io.IOException failure) { throw new IllegalStateException("Report publication failed; previous publication retained", failure); }
    }
    static ReportCatalog catalog(List<ReportPassage> passages) {
        return new ReportCatalog(JsonMapper.builder().build().writeValueAsBytes(Map.of("version", "reviewed-publication-v1", "passages",
                passages.stream().sorted(Comparator.comparing(ReportPassage::id)).toList())));
    }
    private String encode(ReportCatalog catalog) { return json.writeValueAsString(Map.of("version", "reviewed-publication-v1", "passages",
            catalog.all().stream().sorted(Comparator.comparing(ReportPassage::id)).toList())); }
    private ReportCatalog decode(String value) { return new ReportCatalog(value.getBytes(StandardCharsets.UTF_8)); }
    public record State(int schema, long revision, Map<String, Entry> documents) {}
    public record Entry(String key, String symbol, String title, int year, String status, long generation, String activeVersion,
                        String token, String pendingVersion, String failure, Map<String, String> versions) {}
    public record Ticket(String key, String token, long generation, String version, String catalog) {}
    public record Status(String key, String title, int year, String status, boolean usableVersion, String failure) {}
}
