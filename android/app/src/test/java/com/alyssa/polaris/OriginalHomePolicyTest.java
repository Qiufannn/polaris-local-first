package com.alyssa.polaris;
import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class OriginalHomePolicyTest {
    @Test public void onlyAcceptsPrivateSiteHomeAndExactMcpWithoutCredentialsOrRedirectParameters() throws Exception {
        assertEquals("https://unit.example.chatgpt.site", OriginalHomePolicy.origin("https://unit.example.chatgpt.site/"));
        assertEquals("https://unit.example.chatgpt.site/mcp", OriginalHomePolicy.endpoint("https://unit.example.chatgpt.site/mcp"));
        for (String url : new String[]{"http://unit.chatgpt.site", "https://chatgpt.site.evil.invalid/", "https://user:password@unit.chatgpt.site", "https://unit.chatgpt.site/?token=unit", "https://unit.chatgpt.site/other", "https://127.0.0.1/"}) assertThrows(Exception.class, () -> OriginalHomePolicy.origin(url));
        assertThrows(Exception.class, () -> OriginalHomePolicy.endpoint("https://unit.chatgpt.site/"));
    }
    @Test public void chatHandoffDoesNotReplaceTheOriginalHtmlStylesOrThreePages() {
        String script = OriginalHomePolicy.chatHook();
        assertTrue(script.contains("data-room")); assertTrue(script.contains("data-open")); assertTrue(script.contains(OriginalHomePolicy.CHAT_LINK));
        for (String value : new String[]{"innerHTML", "appendChild", "style.", "removeChild", "Cookie", "Capacitor", "fetch("}) assertFalse(script.contains(value));
        assertTrue(OriginalHomePolicy.trustedPage("https://unit.chatgpt.site/?page=2", "https://unit.chatgpt.site"));
        assertFalse(OriginalHomePolicy.trustedPage("https://auth.openai.com/", "https://unit.chatgpt.site"));
        assertFalse(OriginalHomePolicy.trustedPage("https://unit.chatgpt.site.evil.invalid/", "https://unit.chatgpt.site"));
    }
    @Test public void lifeWritesStayOnTheExistingMcpProtocolAndDoNotExposeRawChatOrMainMemoryWrites() throws Exception {
        JSONObject rpc = new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call").put("params", new JSONObject().put("name", "mood_save").put("arguments", new JSONObject().put("client_request_id", "unit-stable-id").put("date", "2026-11-16")));
        assertEquals("unit-stable-id", OriginalHomePolicy.rpc(rpc.toString()).getJSONObject("params").getJSONObject("arguments").getString("client_request_id"));
        for (String tool : new String[]{"chat_append", "chat_window_register", "memory_capture", "memory_correct", "memory_restore_apply", "home_archive", "diary_publish", "https://elsewhere.invalid/"}) { rpc.getJSONObject("params").put("name", tool); assertThrows(Exception.class, () -> OriginalHomePolicy.rpc(rpc.toString())); }
    }
    @Test public void filtersDiscoveryBeforeModelVisibilityAndPreservesOnlyConfirmedMemoryRecall() throws Exception {
        JSONObject request = new JSONObject().put("jsonrpc", "2.0").put("method", "tools/list");
        JSONObject response = new JSONObject().put("result", new JSONObject().put("tools", new JSONArray().put(new JSONObject().put("name", "home_list")).put(new JSONObject().put("name", "chat_append")).put(new JSONObject().put("name", "memory_capture")).put(new JSONObject().put("name", "todo_save"))));
        JSONArray tools = new JSONObject(OriginalHomePolicy.filtered(request, response.toString())).getJSONObject("result").getJSONArray("tools");
        assertEquals(2, tools.length()); assertEquals("home_list", tools.getJSONObject(0).getString("name"));
        JSONObject recall = new JSONObject().put("jsonrpc", "2.0").put("method", "tools/call").put("params", new JSONObject().put("name", "memory_recall").put("arguments", new JSONObject().put("include_sandbox", true).put("confirmed_only", false)));
        JSONObject args = OriginalHomePolicy.rpc(recall.toString()).getJSONObject("params").getJSONObject("arguments"); assertFalse(args.getBoolean("include_sandbox")); assertTrue(args.getBoolean("confirmed_only"));
    }
}
