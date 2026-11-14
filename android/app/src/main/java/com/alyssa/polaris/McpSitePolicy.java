package com.alyssa.polaris;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Collections;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** First native MCP OAuth profile: owner-authorized Sites, read-only memory tools. */
final class McpSitePolicy {
    static final Set<String> READ_TOOLS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("memory_status", "memory_bootstrap", "memory_recall")));
    private static final Set<String> READ_METHODS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("initialize", "server/discover", "notifications/initialized", "tools/list")));

    static String resource(String value) throws Exception {
        URI uri = new URI(value == null ? "" : value.trim());
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
            || !host.toLowerCase(Locale.ROOT).endsWith(".chatgpt.site")
            || uri.getRawUserInfo() != null || uri.getPort() != -1
            || !"/mcp".equals(uri.getRawPath()) || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new Exception("授权地址须为原私密小家的 HTTPS /mcp 地址，不支持其他站点或附带密钥的地址。");
        }
        return "https://" + host.toLowerCase(Locale.ROOT) + "/mcp";
    }

    static String profile(String serverId, String url) throws Exception {
        if (serverId == null || !serverId.matches("[a-zA-Z0-9_-]{1,128}")) throw new Exception("授权服务编号不正确，请先保存服务。");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest((serverId + "\n" + resource(url)).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }

    static JSONObject request(String body) throws Exception {
        if (body == null || body.length() > 256 * 1024) throw new Exception("授权请求无效或过大。");
        JSONObject rpc = new JSONObject(body);
        if (!"2.0".equals(rpc.optString("jsonrpc"))) throw new Exception("授权请求协议不正确。");
        String method = rpc.optString("method");
        if ("tools/call".equals(method)) {
            JSONObject params = rpc.getJSONObject("params");
            if (!READ_TOOLS.contains(params.optString("name"))) throw new Exception("授权连接当前仅开放记忆状态、接续包和检索，不能写入或执行其他工具。");
            JSONObject supplied = params.optJSONObject("arguments"), args = new JSONObject();
            String name = params.optString("name");
            if ("memory_recall".equals(name)) {
                for (String key : Arrays.asList("query", "kind", "subject", "memory_scope", "limit")) {
                    if (supplied != null && supplied.has(key)) args.put(key, supplied.get(key));
                }
                args.put("include_sandbox", false).put("confirmed_only", true);
            }
            if ("memory_bootstrap".equals(name)) args.put("window_kind", "other");
            params.put("arguments", args);
        } else if (!READ_METHODS.contains(method)) {
            throw new Exception("授权连接不支持该操作。");
        }
        return rpc;
    }

    static String response(JSONObject request, String body) throws Exception {
        if (!"tools/list".equals(request.optString("method"))) return body;
        JSONObject rpc = new JSONObject(body);
        JSONObject result = rpc.optJSONObject("result");
        if (result != null && result.optJSONArray("tools") != null) {
            JSONArray tools = result.getJSONArray("tools"), filtered = new JSONArray();
            for (int i = 0; i < tools.length(); i++) if (READ_TOOLS.contains(tools.getJSONObject(i).optString("name"))) filtered.put(tools.getJSONObject(i));
            result.put("tools", filtered);
        }
        return rpc.toString();
    }
}
