package com.stock.system.agent.mcp;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/** 一个本机进程只绑定一个账号和周期；不生成包含 Token 的 toString。 */
public final class ResearchMcpSettings {
    private final URI origin;
    private final String token;
    private final long workspace;
    private final long stock;
    private final long cycle;
    private final Duration timeout;

    private ResearchMcpSettings(URI origin, String token, long workspace, long stock, long cycle, Duration timeout) {
        this.origin = origin; this.token = token; this.workspace = workspace;
        this.stock = stock; this.cycle = cycle; this.timeout = timeout;
    }

    public static ResearchMcpSettings from(Map<String, String> env) {
        try {
            URI origin = URI.create(env.getOrDefault("STOCK_MCP_API_ORIGIN", "http://127.0.0.1:8082"));
            if (!"http".equals(origin.getScheme()) || !"127.0.0.1".equals(origin.getHost())
                    || origin.getPort() < 1 || origin.getPort() > 65535 || origin.getUserInfo() != null
                    || origin.getQuery() != null || origin.getFragment() != null
                    || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))) throw new IllegalArgumentException();
            String token = env.get("STOCK_MCP_ACCESS_TOKEN");
            if (token == null || token.length() < 32 || token.length() > 4096
                    || !token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            long timeout = Long.parseLong(env.getOrDefault("STOCK_MCP_TIMEOUT_MS", "10000"));
            if (timeout < 250 || timeout > 10000) throw new IllegalArgumentException();
            return new ResearchMcpSettings(origin, token, positive(env, "STOCK_MCP_WORKSPACE_ID"),
                    positive(env, "STOCK_MCP_STOCK_ID"), positive(env, "STOCK_MCP_CYCLE_ID"), Duration.ofMillis(timeout));
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("MCP_CONFIGURATION_INVALID"); }
    }

    private static long positive(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
        return Long.parseLong(value);
    }

    URI sources(String query) {
        return origin.resolve("/api/workspaces/" + workspace + "/stocks/" + stock
                + "/research/cycles/" + cycle + "/sources" + query);
    }
    String authorization() { return "Bearer " + token; }
    Duration timeout() { return timeout; }
    long stock() { return stock; }
    long cycle() { return cycle; }
}
