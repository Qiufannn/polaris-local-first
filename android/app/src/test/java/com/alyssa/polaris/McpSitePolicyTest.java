package com.alyssa.polaris;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class McpSitePolicyTest {
    private final String resource = "https://unit-home.example.chatgpt.site/mcp";
    @Test public void acceptsOnlyExactHttpsSiteResources() throws Exception {
        assertEquals(resource, McpSitePolicy.resource(resource));
        for (String bad : new String[] { "http://unit.chatgpt.site/mcp", "https://chatgpt.site/mcp", "https://unit.chatgpt.site.evil.invalid/mcp",
            "https://127.0.0.1/mcp", "https://user:password@unit.chatgpt.site/mcp", "https://unit.chatgpt.site:443/mcp",
            resource + "?token=placeholder", resource + "#fragment", "https://unit.chatgpt.site/other", "https://unit.chatgpt.site/%6dcp" }) {
            assertThrows(Exception.class, () -> McpSitePolicy.resource(bad));
        }
    }
    @Test public void profileIsStableAndBoundToServerAndResource() throws Exception {
        String key = McpSitePolicy.profile("mcp-unit", resource);
        assertTrue(key.matches("[0-9a-f]{64}"));
        assertEquals(key, McpSitePolicy.profile("mcp-unit", resource));
        assertNotEquals(key, McpSitePolicy.profile("mcp-other", resource));
        assertNotEquals(key, McpSitePolicy.profile("mcp-unit", "https://other.chatgpt.site/mcp"));
        assertThrows(Exception.class, () -> McpSitePolicy.profile("../../unsafe", resource));
    }
    @Test public void bindsReadsToRealMemoryAndSeparateWindowKind() throws Exception {
        JSONObject rpc = McpSitePolicy.request("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"memory_bootstrap\",\"arguments\":{\"namespace\":\"sandbox\",\"window_kind\":\"chat\"}}}");
        JSONObject args = rpc.getJSONObject("params").getJSONObject("arguments");
        assertFalse(args.has("namespace"));
        assertEquals("other", args.getString("window_kind"));
        JSONObject recall = McpSitePolicy.request("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"memory_recall\",\"arguments\":{\"query\":\"unit\",\"include_sandbox\":true,\"confirmed_only\":false,\"unknown\":\"unused\"}}}");
        JSONObject safe = recall.getJSONObject("params").getJSONObject("arguments");
        assertEquals("unit", safe.getString("query"));
        assertFalse(safe.getBoolean("include_sandbox")); assertTrue(safe.getBoolean("confirmed_only")); assertFalse(safe.has("unknown"));
    }
    @Test public void rejectsWritesUnknownMethodsAndOversizedBodies() {
        for (String name : new String[] {"memory_capture", "memory_restore_apply", "ledger_save", "runCode", "other"}) {
            String body = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"" + name + "\"}}";
            assertThrows(Exception.class, () -> McpSitePolicy.request(body));
        }
        assertThrows(Exception.class, () -> McpSitePolicy.request("{\"jsonrpc\":\"2.0\",\"method\":\"resources/read\"}"));
        assertThrows(Exception.class, () -> McpSitePolicy.request(" ".repeat(256 * 1024 + 1)));
    }
    @Test public void discoveredCatalogContainsOnlyReadOnlyTools() throws Exception {
        JSONObject request = McpSitePolicy.request("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        JSONObject response = new JSONObject().put("jsonrpc", "2.0").put("id", 2).put("result", new JSONObject().put("tools", new JSONArray()
            .put(new JSONObject().put("name", "memory_bootstrap")).put(new JSONObject().put("name", "memory_capture"))
            .put(new JSONObject().put("name", "memory_recall"))));
        JSONArray tools = new JSONObject(McpSitePolicy.response(request, response.toString())).getJSONObject("result").getJSONArray("tools");
        assertEquals(2, tools.length()); assertEquals("memory_bootstrap", tools.getJSONObject(0).getString("name"));
        assertEquals("memory_recall", tools.getJSONObject(1).getString("name"));
    }
}
