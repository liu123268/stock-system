package com.stock.system.stock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Browser input stays on loopback. Provider credentials never enter test properties or logs. */
final class LongbridgeLocalAuthorization implements AutoCloseable {
    static final int PORT = 60355;
    private final HttpServer server;
    private final String origin;
    private final String csrf;
    private final CompletableFuture<String> submittedCode = new CompletableFuture<>();
    private final AtomicBoolean submissionClaimed = new AtomicBoolean();

    LongbridgeLocalAuthorization(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        origin = "http://localhost:" + server.getAddress().getPort();
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        csrf = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        server.createContext("/", this::handle);
        server.start();
    }

    static void run() {
        String stage = "PREFLIGHT";
        try {
            requireQuietRuntime();
            stage = "LOCAL_AUTH_PAGE";
            try (LongbridgeLocalAuthorization local = new LongbridgeLocalAuthorization(PORT)) {
                System.out.println("LONGBRIDGE_LOCAL_AUTH_READY url=" + local.origin
                        + "/ pid=" + ProcessHandle.current().pid() + " timeoutSeconds=600");
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    try { Desktop.getDesktop().browse(URI.create(local.origin + "/")); }
                    catch (Exception ignored) { System.out.println("LONGBRIDGE_OPEN_LOCAL_URL_MANUALLY"); }
                }
                stage = "WAITING_FOR_BROWSER_AUTH";
                String code = local.submittedCode.get(600, TimeUnit.SECONDS);
                stage = "AUTH_CODE_EXCHANGE";
                LongbridgeAgentAuth.Token token = LongbridgeAgentAuth.redeem(code);
                code = null;
                System.out.println("LONGBRIDGE_AUTH_OK");
                stage = "SDK_QUOTE";
                System.out.println(LongbridgeSdkQuoteProbe.probe(token.clientId(), token.accessToken(), token.expiresAt()));
            }
        } catch (Exception | LinkageError failure) {
            // Never pass upstream exception/cause/message to JUnit: those can contain URLs or response bodies.
            String reason = failure instanceof SafeFailure safe ? safe.getMessage()
                    : failure instanceof java.util.concurrent.TimeoutException ? "TIMEOUT"
                    : failure instanceof java.net.BindException ? "PORT_IN_USE"
                    : failure instanceof InterruptedException ? "INTERRUPTED" : "LOCAL_OR_NETWORK_FAILURE";
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AssertionError("LONGBRIDGE_PROBE_FAILED stage=" + stage + " reason=" + reason);
        } finally {
            System.out.println("LONGBRIDGE_LOCAL_AUTH_CLOSED");
        }
    }

    private static void requireQuietRuntime() {
        if (!"off".equals(System.getenv("RUST_LOG"))
                || System.getenv("RUST_BACKTRACE") != null
                || System.getenv("LONGBRIDGE_LOG_PATH") != null
                || System.getenv("JAVA_TOOL_OPTIONS") != null
                || System.getenv("JDK_JAVA_OPTIONS") != null
                || hasUnsafeMavenOptions(System.getenv("MAVEN_OPTS"))
                || System.getenv("MAVEN_ARGS") != null
                || System.getProperty("jdk.httpclient.HttpClient.log") != null
                || System.getProperty("javax.net.debug") != null
                || System.getProperties().stringPropertyNames().stream().anyMatch(k -> k.startsWith("stock.longbridge."))) {
            throw new SafeFailure("USE_PROBE_SCRIPT_WITHOUT_DEBUG_OR_CREDENTIAL_PROPERTIES");
        }
    }

    static boolean hasUnsafeMavenOptions(String options) {
        // Maven 3.9.11 can add this supported option itself. It enables JNI,
        // which the Longbridge SDK uses, and does not enable diagnostic output.
        return options != null && !options.isBlank()
                && !"--enable-native-access=ALL-UNNAMED".equals(options.strip());
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!URI.create(origin).getAuthority().equals(exchange.getRequestHeaders().getFirst("Host"))) {
                reply(exchange, 403, rejectedPage());
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod()) && "/".equals(path)
                    && exchange.getRequestURI().getRawQuery() == null) {
                reply(exchange, 200, page());
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod()) || !"/code".equals(path)
                    || !origin.equals(exchange.getRequestHeaders().getFirst("Origin"))
                    || exchange.getRequestURI().getRawQuery() != null
                    || !"application/x-www-form-urlencoded".equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                reply(exchange, 403, rejectedPage());
                return;
            }
            // URL encoding can expand each of the 4096 code characters to three bytes.
            byte[] input = exchange.getRequestBody().readNBytes(12545);
            Map<String, String> form;
            try {
                if (input.length > 12544) throw new SafeFailure("INVALID_LOCAL_FORM");
                form = parseForm(new String(input, StandardCharsets.UTF_8));
                if (!MessageDigest.isEqual(csrf.getBytes(StandardCharsets.UTF_8),
                        form.getOrDefault("csrf", "").getBytes(StandardCharsets.UTF_8))) {
                    throw new SafeFailure("INVALID_LOCAL_FORM");
                }
                validateCode(form.get("code"));
            } catch (RuntimeException ignored) {
                reply(exchange, 400, "Invalid local input. Return to the local page and paste only the one-time code.");
                return;
            }
            if (!submissionClaimed.compareAndSet(false, true)) {
                reply(exchange, 409, "A code has already been received for this run.");
                return;
            }
            reply(exchange, 200, "<!doctype html><meta charset=utf-8><title>本机已接收</title>"
                    + "<p>授权码已在本机接收。请回到运行探测的终端查看报价或失败阶段，可以关闭本页。</p>");
            submittedCode.complete(form.get("code").trim());
        } catch (RuntimeException ignored) {
            // No request URI, headers, body, credentials, or stack trace is logged.
        }
    }

    static Map<String, String> parseForm(String raw) {
        Map<String, String> result = new HashMap<>();
        for (String pair : raw.split("&", -1)) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2) throw new SafeFailure("INVALID_LOCAL_FORM");
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            if (result.putIfAbsent(key, value) != null) throw new SafeFailure("INVALID_LOCAL_FORM");
        }
        if (!result.keySet().equals(java.util.Set.of("csrf", "code"))) throw new SafeFailure("INVALID_LOCAL_FORM");
        return result;
    }

    static void validateCode(String code) {
        if (code == null || !code.trim().matches("[A-Za-z0-9._~+/=-]{8,4096}")) {
            throw new SafeFailure("INVALID_AUTH_CODE_FORMAT");
        }
    }

    private String page() {
        return """
                <!doctype html><html lang="zh-CN"><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1"><title>Longbridge 本机报价探测</title>
                <body><main><h1>只验证 600519.SH 与 NVDA.US 报价</h1>
                <p>这是本机临时授权入口，10 分钟后关闭，不连接项目数据库。/connect 会为这次授权携带自己的临时 client_id；你之前拿到的 public client_id 仍属于标准 OAuth 流程，但该流程的确认页当前会丢失请求。</p>
                <ol><li><a href="https://open.longbridge.com/connect" target="_blank" rel="noreferrer noopener">打开 Longbridge 官方备用授权页</a>，在该页登录；如页面要求 Agent Name，可填 stock-system-probe，只选择行情权限并生成一次性授权码。</li>
                <li>点击官方页上方「授权码」框内的「复制」，粘贴到下面的本机输入框。下方按钮复制的是整段说明，请不要粘贴整段说明。授权码只填此处，不发到聊天。</li></ol>
                <form method="post" action="/code" autocomplete="off">
                <input type="hidden" name="csrf" value="%s">
                <label>一次性授权码 <input name="code" type="password" required maxlength="4096" autocomplete="off" spellcheck="false"></label>
                <button type="submit">在本机验证报价</button></form>
                <p>如果官方页仍回到首页，停止重试；本机等待会自动结束。</p></main></body></html>
                """.formatted(csrf);
    }

    private static String rejectedPage() {
        return "<!doctype html><meta charset=utf-8><title>请重新打开本机表单</title>"
                + "<p>本机页面未接收本次提交，请<a href=\"/\">重新打开本机表单</a>，再粘贴上方授权码框复制的代码。不要刷新 /code 地址。</p>";
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        // Native browser form POSTs send Origin: null under no-referrer,
        // which conflicts with the strict same-origin validation above.
        // Keep the real origin for our local form; external links use noreferrer.
        exchange.getResponseHeaders().set("Referrer-Policy", "same-origin");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    String origin() { return origin; }
    CompletableFuture<String> submittedCode() { return submittedCode; }
    @Override public void close() { server.stop(0); }

    static final class SafeFailure extends RuntimeException {
        SafeFailure(String reason) { super(reason, null, false, false); }
    }
}
