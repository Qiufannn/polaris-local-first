package com.alyssa.polaris;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

final class MemorySourcePolicy {
    static final String NOTION = "notion-memory-oauth", GITHUB = "github-memory-device";
    static final String NOTION_RESOURCE = "https://mcp.notion.com", NOTION_MCP = NOTION_RESOURCE + "/mcp";
    static final Set<String> NOTION_READ = new HashSet<>(Arrays.asList("notion-search", "notion-fetch"));
    private static final Pattern PAGE = Pattern.compile("(?i)([a-f0-9]{32}|[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})(?:$|/)");

    static String resource(String kind, String value) throws Exception {
        if (NOTION.equals(kind)) {
            if (!NOTION_MCP.equals(value)) throw new Exception("授权地址必须是 Notion 官方 MCP 地址。");
            return NOTION_RESOURCE;
        }
        if (!GITHUB.equals(kind)) throw new Exception("授权来源不支持。");
        URI uri = new URI(value == null ? "" : value);
        if (!"https".equals(uri.getScheme()) || !"api.github.com".equals(uri.getHost()) || uri.getPort() != -1
            || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
            || !uri.getRawPath().matches("/repos/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
            || Arrays.asList(uri.getRawPath().split("/")).contains("..") || Arrays.asList(uri.getRawPath().split("/")).contains(".")) throw new Exception("授权仓库地址应为 https://api.github.com/repos/用户名/仓库名。");
        return value;
    }
    static void publicClient(String value) throws Exception {
        if (value == null || !value.matches("Iv[A-Za-z0-9_.-]{10,118}")) throw new Exception("授权需要 GitHub App 的公开 Client ID（Iv 开头），不是 Client Secret 或 token。");
    }
    static String key(String id, String kind, String url, String clientId) throws Exception {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,128}")) throw new Exception("授权服务请先保存。");
        String resource = resource(kind, url);
        if (GITHUB.equals(kind)) publicClient(clientId);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(("memory-source-v1|" + id + "|" + kind + "|" + resource + "|" + (GITHUB.equals(kind) ? clientId : "")).getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(); for (byte b : hash) result.append(String.format(Locale.ROOT, "%02x", b & 255)); return result.toString();
    }
    static String page(String value) throws Exception {
        String path = value == null ? "" : value.trim();
        boolean link = path.startsWith("https://");
        if (path.startsWith("https://")) {
            URI uri = new URI(path);
            if (!new HashSet<>(Arrays.asList("www.notion.so", "notion.so", "app.notion.com", "www.notion.com", "notion.com")).contains(uri.getHost())
                || uri.getUserInfo() != null || uri.getPort() != -1) throw new Exception("授权页面应为 Notion 页面链接或页面 ID。");
            path = uri.getPath();
        }
        if (!link && !path.matches("(?i)([a-f0-9]{32}|[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})")) throw new Exception("授权页面应为有效的 Notion 页面链接或 ID。");
        Matcher found = PAGE.matcher(path);
        if (!found.find()) throw new Exception("授权页面链接里没有有效的 Notion 页面 ID。");
        return found.group(1).replace("-", "").toLowerCase(Locale.ROOT);
    }
    static void notionIssuer(Map<String, String> values) throws Exception {
        if (!NOTION_RESOURCE.equals(values.get("iss"))) throw new Exception("授权返回的 Notion 签发者不匹配，未保存连接。");
        if (values.containsKey("error")) throw new Exception("授权未批准，原连接未替换。");
    }
    static String file(String path) throws Exception {
        if (path == null || path.length() > 300 || !("README.md".equals(path) || path.startsWith("continuity/"))
            || !path.endsWith(".md") || !path.matches("[\\p{L}\\p{N}._/-]+")) throw new Exception("授权读取仅支持连续性目录里的 Markdown 文件。");
        StringBuilder encoded = new StringBuilder();
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) throw new Exception("授权读取路径不正确。");
            if (encoded.length() > 0) encoded.append('/');
            encoded.append(URLEncoder.encode(part, "UTF-8").replace("+", "%20"));
        }
        return encoded.toString();
    }
    static JSONObject rpc(String kind, String body) throws Exception {
        if (body == null || body.length() > 256 * 1024) throw new Exception("授权请求不完整或过大。");
        JSONObject rpc = new JSONObject(body);
        if (!"2.0".equals(rpc.optString("jsonrpc"))) throw new Exception("授权请求协议不正确。");
        String method = rpc.optString("method");
        if ("tools/call".equals(method)) {
            JSONObject params = rpc.getJSONObject("params"); String name = params.optString("name");
            if (NOTION.equals(kind) ? !NOTION_READ.contains(name) : !"github_memory_read".equals(name)) throw new Exception("授权记忆连接仅开放只读工具。");
            JSONObject args = params.optJSONObject("arguments");
            if (args == null) args = new JSONObject();
            if (GITHUB.equals(kind)) file(args.optString("path"));
            if (NOTION.equals(kind) && "notion-fetch".equals(name)) {
                String field = args.has("id") ? "id" : "url";
                String id = page(args.optString(field));
                args.put(field, "url".equals(field) ? "https://www.notion.so/" + id : id);
            }
            params.put("arguments", args);
        } else if (!new HashSet<>(Arrays.asList("initialize", "notifications/initialized", "tools/list")).contains(method)) throw new Exception("授权连接不支持该操作。");
        return rpc;
    }
    static JSONObject filterNotion(JSONObject rpc) throws Exception {
        JSONObject result = rpc.optJSONObject("result");
        if (result != null && result.optJSONArray("tools") != null) {
            JSONArray filtered = new JSONArray(), tools = result.getJSONArray("tools");
            for (int i = 0; i < tools.length(); i++) if (NOTION_READ.contains(tools.getJSONObject(i).optString("name"))) filtered.put(tools.getJSONObject(i));
            result.put("tools", filtered);
        }
        return rpc;
    }
}
