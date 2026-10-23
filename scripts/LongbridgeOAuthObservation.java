import com.google.gson.GsonBuilder;
import com.longbridge.Config;
import com.longbridge.OAuth;
import com.longbridge.OAuthBuilder;
import com.longbridge.quote.QuoteContext;
import com.longbridge.quote.SecurityQuote;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** One bounded SDK read. Reads cache metadata only; never parses or edits a token. */
public class LongbridgeOAuthObservation {
    private record Metadata(long createdMillis, long modifiedMillis, long size) {}

    private static Metadata metadata(Path cache) throws Exception {
        BasicFileAttributes attributes = Files.readAttributes(cache, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IllegalArgumentException("CACHE_NOT_REGULAR_FILE");
        return new Metadata(attributes.creationTime().toMillis(), attributes.lastModifiedTime().toMillis(), attributes.size());
    }

    private static String classify(Metadata before, Metadata after, boolean quoteSucceeded, int callbacks) {
        if (callbacks != 0) return "INTERACTIVE_AUTHORIZATION_REQUIRED";
        if (!quoteSucceeded) return "QUOTE_FAILED";
        if (before.createdMillis != after.createdMillis || after.modifiedMillis < before.modifiedMillis)
            return "CACHE_IDENTITY_OR_TIME_CHANGED";
        if (after.modifiedMillis > before.modifiedMillis) return "CACHE_WRITE_DURING_SUCCESSFUL_SDK_REQUEST";
        if (before.size != after.size) return "CACHE_CHANGED_WITHOUT_NEW_TIMESTAMP";
        return "VALID_QUOTE_NO_CACHE_WRITE";
    }

    private static Map<String, Object> describe(Metadata value) {
        if (value == null) return Map.of();
        return Map.of("createdAt", Instant.ofEpochMilli(value.createdMillis).toString(),
                "modifiedAt", Instant.ofEpochMilli(value.modifiedMillis).toString(), "size", value.size);
    }

    private static void offline() {
        Metadata before = new Metadata(1, 10, 100);
        check(classify(before, before, true, 0).equals("VALID_QUOTE_NO_CACHE_WRITE"));
        check(classify(before, new Metadata(1, 11, 101), true, 0).equals("CACHE_WRITE_DURING_SUCCESSFUL_SDK_REQUEST"));
        check(classify(before, new Metadata(1, 11, 101), false, 0).equals("QUOTE_FAILED"));
        check(classify(before, before, true, 1).equals("INTERACTIVE_AUTHORIZATION_REQUIRED"));
        check(classify(before, new Metadata(2, 11, 101), true, 0).equals("CACHE_IDENTITY_OR_TIME_CHANGED"));
        check(classify(before, new Metadata(1, 9, 100), true, 0).equals("CACHE_IDENTITY_OR_TIME_CHANGED"));
        check(classify(before, new Metadata(1, 10, 101), true, 0).equals("CACHE_CHANGED_WITHOUT_NEW_TIMESTAMP"));
        System.out.println("OAUTH_OBSERVATION_OFFLINE_OK checks=7 network=false database=false tokenContentRead=false");
    }

    private static void check(boolean result) { if (!result) throw new AssertionError("OFFLINE_OBSERVATION_CHECK_FAILED"); }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--offline")) { offline(); return; }
        if (args.length != 3 || !args[0].equals("--live")) throw new IllegalArgumentException("USE_EXPLICIT_OFFLINE_OR_LIVE_MODE");
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        Path target = Path.of("target").toAbsolutePath().normalize();
        if (!output.startsWith(target) || !output.getFileName().toString().equals("observation.json"))
            throw new IllegalArgumentException("OUTPUT_MUST_BE_OWNED_TARGET_FILE");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startedAt", Instant.now().toString());
        result.put("sdkVersion", "4.5.0");
        result.put("sourceCommit", "68080b64ee02836e2fec625e9605dc5389abdb17");
        result.put("symbol", "600519.SH");
        result.put("tokenContentRead", false);
        result.put("tokenContentWrittenByProbe", false);
        result.put("database", false);
        result.put("modelRequests", 0);
        result.put("refreshAcceptance", "PENDING_NATURAL_EVENT_OR_SOURCE_REVIEW");
        AtomicInteger callbacks = new AtomicInteger();
        Metadata before = null, after = null;
        boolean success = false;
        int exitCode = 0;
        try {
            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(Path.of(args[1]), StandardCharsets.UTF_8)) { properties.load(reader); }
            String clientId = properties.getProperty("local.longbridge.client-id", "").trim();
            if (!clientId.matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))
                throw new IllegalArgumentException("PUBLIC_CLIENT_CONFIGURATION_REQUIRED");
            int callbackPort = Integer.parseInt(properties.getProperty("local.longbridge.callback-port", "60355").trim());
            if (callbackPort < 1024 || callbackPort > 65535) throw new IllegalArgumentException("INVALID_CALLBACK_PORT");
            Path cache = Path.of(System.getProperty("user.home"), ".longbridge", "openapi", "tokens", clientId);
            // Missing cache fails before OAuthBuilder: this probe must never initiate new consent.
            before = metadata(cache);
            try (OAuth oauth = new OAuthBuilder(clientId).setCallbackPort(callbackPort).build(ignored -> {
                callbacks.incrementAndGet();
                throw new IllegalStateException("INTERACTIVE_AUTHORIZATION_DISABLED");
            }).get(20, TimeUnit.SECONDS);
                 Config config = Config.fromOAuth(oauth).disablePrintQuotePackages();
                 QuoteContext context = QuoteContext.create(config)) {
                SecurityQuote[] quotes = context.getQuote(new String[]{"600519.SH"}).get(20, TimeUnit.SECONDS);
                if (quotes.length != 1 || !"600519.SH".equals(quotes[0].getSymbol())
                        || quotes[0].getLastDone() == null || quotes[0].getLastDone().signum() <= 0 || quotes[0].getTimestamp() == null)
                    throw new IllegalStateException("NO_VALID_QUOTE");
                success = true;
                result.put("quoteTime", quotes[0].getTimestamp().toString());
            }
            after = metadata(cache);
            result.put("observation", classify(before, after, true, callbacks.get()));
        } catch (Throwable failure) {
            result.put("observation", callbacks.get() == 0 ? "OBSERVATION_FAILED" : "INTERACTIVE_AUTHORIZATION_REQUIRED");
            result.put("failureType", failure.getClass().getSimpleName());
            // Do not serialize SDK errors, credentials, cache contents or authorization URLs.
            exitCode = 1;
        } finally {
            result.put("quoteSucceeded", success);
            result.put("authorizationCallbacks", callbacks.get());
            result.put("before", describe(before));
            result.put("after", describe(after));
            result.put("finishedAt", Instant.now().toString());
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(result), StandardCharsets.UTF_8);
        }
        System.out.println("OAUTH_OBSERVATION_RESULT state=" + result.get("observation") + " refreshAccepted=false modelRequests=0");
        if (exitCode != 0) System.exit(exitCode);
    }
}
