package com.alyssa.polaris;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

final class OriginalHomePolicy {
    static final String CHAT_LINK = "polaris-original-home://chat";
    static final Set<String> TOOLS = new HashSet<>(Arrays.asList("home_list", "calendar_save", "mood_save", "ledger_save", "todo_save", "photo_update", "photo_view", "song_save", "health_save", "story_save", "gift_save", "garden_activity_list", "garden_activity_append", "memory_status", "memory_bootstrap", "memory_recall"));
    static String origin(String value) throws Exception {
        URI uri = new URI(value == null ? "" : value.trim()); String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null || !host.toLowerCase(Locale.ROOT).endsWith(".chatgpt.site") || uri.getPort() != -1 || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null || !(uri.getRawPath() == null || uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()) || "/mcp".equals(uri.getRawPath()))) throw new Exception("请填写原私密小屋的 HTTPS 首页地址，不附带密码或参数。");
        return "https://" + host.toLowerCase(Locale.ROOT);
    }
    static String endpoint(String value) throws Exception {
        String origin = origin(value); if (!(origin + "/mcp").equals(value)) throw new Exception("小屋工具只能使用已配置原网页的 /mcp 地址。"); return value;
    }
    static boolean trustedPage(String value, String origin) {
        try { URI uri = new URI(value); return "https".equals(uri.getScheme()) && uri.getPort() == -1 && uri.getRawUserInfo() == null && origin.equals("https://" + uri.getHost()); } catch (Exception ignored) { return false; }
    }
    static String chatHook() {
        // No HTML, styles, page content, database or layout are replaced.
        return "(function(){if(window.__polarisOriginalChat)return;window.__polarisOriginalChat=true;document.addEventListener('click',function(e){var t=e.target&&e.target.closest&&e.target.closest('[data-room=\"chat\"],[data-open=\"chat\"]');if(t){e.preventDefault();e.stopImmediatePropagation();location.href='" + CHAT_LINK + "';}},true);})()";
    }
    static JSONObject rpc(String body) throws Exception {
        if (body == null || body.length() > 256 * 1024) throw new Exception("小屋请求为空或过大。");
        JSONObject rpc = new JSONObject(body); String method = rpc.optString("method");
        if (!"2.0".equals(rpc.optString("jsonrpc"))) throw new Exception("小屋请求协议无效。");
        if ("tools/call".equals(method)) {
            JSONObject params = rpc.getJSONObject("params");
            if (!TOOLS.contains(params.optString("name"))) throw new Exception("本连接仅开放原小屋生活记录及只读记忆，不自动上传聊天或改写主记忆。");
            if (params.optJSONObject("arguments") == null) params.put("arguments", new JSONObject());
            if ("memory_recall".equals(params.optString("name"))) params.getJSONObject("arguments").put("include_sandbox", false).put("confirmed_only", true);
            if ("memory_bootstrap".equals(params.optString("name"))) params.getJSONObject("arguments").put("window_kind", "other");
        } else if (!Arrays.asList("initialize", "notifications/initialized", "tools/list").contains(method)) throw new Exception("小屋连接不支持此操作。");
        return rpc;
    }
    static String filtered(JSONObject request, String body) throws Exception {
        if (!"tools/list".equals(request.optString("method"))) return body;
        JSONObject rpc = new JSONObject(body), result = rpc.optJSONObject("result");
        if (result != null && result.optJSONArray("tools") != null) {
            JSONArray tools = result.getJSONArray("tools"), kept = new JSONArray();
            for (int i = 0; i < tools.length(); i++) if (TOOLS.contains(tools.getJSONObject(i).optString("name"))) kept.put(tools.getJSONObject(i));
            result.put("tools", kept);
        }
        return rpc.toString();
    }
}
