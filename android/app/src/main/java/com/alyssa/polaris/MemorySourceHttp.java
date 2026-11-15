package com.alyssa.polaris;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;

final class MemorySourceHttp {
    static final int LIMIT = 2 * 1024 * 1024;
    static final class Result {
        final int status; final String body; final Map<String, String> headers;
        Result(int status, String body) { this(status, body, new HashMap<>()); }
        Result(int status, String body, Map<String, String> headers) { this.status = status; this.body = body; this.headers = headers; }
    }
    interface Transport { Result send(String url, String method, String body, String type, String bearer, Map<String, String> headers) throws Exception; }
    static final class Failure extends Exception {
        final int status;
        Failure(int status) { super("授权来源未接受读取（HTTP " + status + "），请检查授权及页面或仓库范围。"); this.status = status; }
    }

    static Result send(String url, String method, String body, String type, String bearer, Map<String, String> extra) throws Exception {
        URI uri = new URI(url);
        String host = uri.getHost(), path = uri.getRawPath();
        boolean allowed = "mcp.notion.com".equals(host) && ("/mcp".equals(path) || "/register".equals(path) || "/token".equals(path));
        boolean garden = "galatea.abysslumina.com".equals(host);
        allowed |= garden && ("/mcp".equals(path) || "/oauth/register".equals(path) || "/oauth/token".equals(path));
        allowed |= "github.com".equals(host) && ("/login/device/code".equals(path) || "/login/oauth/access_token".equals(path));
        boolean githubContent = "api.github.com".equals(host) && path.matches("/repos/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/contents/.+");
        allowed |= "api.github.com".equals(host) && path.startsWith("/repos/") && "GET".equals(method);
        allowed |= githubContent && "PUT".equals(method);
        boolean pinnedRead = githubContent && "GET".equals(method) && uri.getRawQuery() != null && uri.getRawQuery().matches("(?i)ref=(?:[a-f0-9]{40}|[a-f0-9]{64})");
        if (!allowed || !"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1 || (uri.getRawQuery() != null && !pinnedRead) || uri.getFragment() != null) throw new Exception("授权连接地址不受支持。");
        // Provider credentials never cross to another service or an OAuth registration endpoint.
        if (bearer != null && !("api.github.com".equals(host) || (("mcp.notion.com".equals(host) || garden) && "/mcp".equals(path)))) throw new Exception("授权凭证目标不正确。");
        HttpURLConnection http = (HttpURLConnection) uri.toURL().openConnection();
        try {
            http.setInstanceFollowRedirects(false); http.setConnectTimeout(20000); http.setReadTimeout(45000); http.setRequestMethod(method);
            http.setRequestProperty("Accept", ("mcp.notion.com".equals(host) || garden) && "/mcp".equals(path) ? "application/json, text/event-stream" : "application/json");
            http.setRequestProperty("User-Agent", "Polaris-Android-Memory");
            http.setRequestProperty("Cache-Control", "no-cache");
            if (bearer != null) http.setRequestProperty("Authorization", "Bearer " + bearer);
            if ("api.github.com".equals(host)) http.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            for (String key : new String[] {"Mcp-Session-Id", "MCP-Protocol-Version"}) {
                String value = extra.get(key);
                if (value != null) {
                    if (value.length() > 512 || value.contains("\r") || value.contains("\n")) throw new Exception("授权传输会话参数不正确。");
                    http.setRequestProperty(key, value);
                }
            }
            if (body != null) {
                http.setRequestProperty("Content-Type", type); http.setDoOutput(true);
                byte[] data = body.getBytes(StandardCharsets.UTF_8); http.setFixedLengthStreamingMode(data.length);
                try (OutputStream output = http.getOutputStream()) { output.write(data); }
            }
            int status = http.getResponseCode();
            Map<String, String> headers = new HashMap<>();
            String contentType = http.getContentType();
            if (contentType != null) headers.put("content-type", contentType);
            String retryAfter = http.getHeaderField("Retry-After");
            if (retryAfter != null && retryAfter.matches("[0-9]{1,4}")) headers.put("retry-after", retryAfter);
            String session = http.getHeaderField("Mcp-Session-Id");
            if (session != null && session.length() <= 512 && !session.contains("\r") && !session.contains("\n")) headers.put("mcp-session-id", session);
            if (status >= 300 && status < 400) throw new Exception("授权服务返回重定向，本次未转发凭证。");
            InputStream stream = status >= 400 ? http.getErrorStream() : http.getInputStream();
            if (stream == null) return new Result(status, "", headers);
            String text;
            try (InputStream input = stream) {
                if (status >= 200 && status < 300 && contentType != null && contentType.toLowerCase(Locale.ROOT).contains("text/event-stream") && body != null) {
                    text = sse(input, new JSONObject(body).opt("id"));
                    headers.put("content-type", "application/json");
                } else {
                    ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
                    while ((n = input.read(buffer)) != -1) { if (output.size() + n > LIMIT) throw new Exception("授权服务响应过大。"); output.write(buffer, 0, n); }
                    text = output.toString("UTF-8");
                }
            }
            return new Result(status, text, headers);
        } finally { http.disconnect(); }
    }
    static String sse(InputStream input, Object expectedId) throws Exception {
        InputStream limited = new java.io.FilterInputStream(input) {
            int size;
            private void count(int n) throws java.io.IOException { if (n > 0 && (size += n) > LIMIT) throw new java.io.IOException("MCP response limit"); }
            public int read() throws java.io.IOException { int b = super.read(); if (b != -1) count(1); return b; }
            public int read(byte[] b, int off, int len) throws java.io.IOException { int n = super.read(b, off, Math.min(len, LIMIT - size + 1)); count(n); return n; }
        };
        BufferedReader reader = new BufferedReader(new InputStreamReader(limited, StandardCharsets.UTF_8));
        StringBuilder data = new StringBuilder(); String line; int total = 0; long deadline = System.currentTimeMillis() + 60000;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("\ufeff")) line = line.substring(1);
            total += line.length(); if (total > LIMIT || System.currentTimeMillis() > deadline) throw new Exception("授权流响应过大或超时。");
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    try { JSONObject rpc = new JSONObject(data.toString()); if (expectedId != null && expectedId.equals(rpc.opt("id"))) return rpc.toString(); }
                    catch (org.json.JSONException ignored) {}
                    data.setLength(0);
                }
            } else if (line.startsWith("data:")) { if (data.length() > 0) data.append('\n'); data.append(line.substring(5).trim()); }
        }
        throw new Exception("授权流未返回对应的工具结果。");
    }
}
