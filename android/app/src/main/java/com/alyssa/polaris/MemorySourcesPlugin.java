package com.alyssa.polaris;

import android.content.Intent;
import android.net.Uri;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

@CapacitorPlugin(name = "MemorySources")
public class MemorySourcesPlugin extends Plugin {
    private final Map<String, MemorySourceSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private volatile ChatGptLoopbackListener listener;
    private volatile MemorySourceSession loginSession;

    private synchronized MemorySourceSession session(PluginCall call) throws Exception {
        String kind = call.getString("authKind"), url = call.getString("url"), client = call.getString("publicClientId", "");
        String key = MemorySourcePolicy.key(call.getString("serverId"), kind, url, client);
        MemorySourceSession value = sessions.get(key);
        if (value == null) {
            if (sessions.size() >= 16) throw new Exception("授权来源过多，请重启后重试。");
            value = new MemorySourceSession(kind, url, client, new ChatGptCredentialStore(getContext(), key), MemorySourceHttp::send);
            sessions.put(key, value);
        }
        return value;
    }
    @PluginMethod public void openGithub(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/login/device"));
            try { getActivity().startActivity(new Intent(intent).setPackage("mark.via")); call.resolve(); }
            catch (Exception viaUnavailable) {
                try { getActivity().startActivity(intent); call.resolve(); }
                catch (Exception unavailable) { call.reject("无法打开 GitHub，请在浏览器手动打开 github.com/login/device。"); }
            }
        });
    }
    @PluginMethod public void status(PluginCall call) {
        executor.execute(() -> { try { call.resolve(JSObject.fromJSONObject(session(call).status())); } catch (Exception e) { call.reject(MemorySourceOAuth.safe(e)); } });
    }
    @PluginMethod public void setWritingEnabled(PluginCall call) {
        executor.execute(() -> { try { call.resolve(JSObject.fromJSONObject(session(call).setWritingEnabled(call.getBoolean("enabled", false)))); }
            catch (Exception e) { call.reject(MemorySourceOAuth.safe(e)); } });
    }
    @PluginMethod public void setDiaryWriting(PluginCall call) {
        executor.execute(() -> { try { call.resolve(JSObject.fromJSONObject(session(call).setDiaryWriting(call.getBoolean("enabled", false), call.getString("parentUrl", ""), call.getString("templateUrl", "")))); }
            catch (Exception e) { call.reject(MemorySourceOAuth.safe(e)); } });
    }
    @PluginMethod public void githubStart(PluginCall call) {
        executor.execute(() -> { try {
            MemorySourceSession s = session(call); if (!MemorySourcePolicy.GITHUB.equals(s.kind)) throw new Exception("授权来源不是 GitHub。");
            call.resolve(JSObject.fromJSONObject(s.githubStart()));
        } catch (Exception e) { call.reject(MemorySourceOAuth.safe(e)); } });
    }
    @PluginMethod public void githubPoll(PluginCall call) {
        executor.execute(() -> { try { call.resolve(JSObject.fromJSONObject(session(call).githubPoll())); } catch (Exception e) { call.reject(MemorySourceOAuth.safe(e)); } });
    }
    @PluginMethod public void cancel(PluginCall call) {
        try { MemorySourceSession selected = session(call); selected.cancel(); if (selected == loginSession && listener != null) listener.close(); } catch (Exception ignored) {}
        call.resolve();
    }
    @PluginMethod public void disconnect(PluginCall call) {
        try { session(call).cancel(); } catch (Exception ignored) {}
        executor.execute(() -> { try { session(call).disconnect(); call.resolve(new JSObject().put("revocationConfirmed", false)); } catch (Exception e) { call.reject(MemorySourceOAuth.safe(e)); } });
    }
    @PluginMethod public void notionLogin(PluginCall call) {
        synchronized (this) {
            if (loginSession != null) { call.reject("授权正在进行，请先完成或取消。"); return; }
            try { loginSession = session(call); if (!MemorySourcePolicy.NOTION.equals(loginSession.kind)) { loginSession = null; throw new Exception("授权来源不是 Notion。"); } }
            catch (Exception e) { call.reject(MemorySourceOAuth.safe(e)); return; }
        }
        final MemorySourceSession selected = loginSession;
        final long generation = selected.generation();
        executor.execute(() -> {
            ChatGptLoopbackListener callback = null;
            try {
                int port = selected.notionPort();
                try { callback = new ChatGptLoopbackListener(port); } catch (Exception occupied) { callback = new ChatGptLoopbackListener(); }
                listener = callback;
                String redirect = callback.redirect(), client = selected.registerNotion(redirect);
                String state = ChatGptOAuth.randomValue(), verifier = ChatGptOAuth.randomValue();
                if (generation != selected.generation()) throw new Exception("授权已取消。");
                Map<String, String> fields = new LinkedHashMap<>(); fields.put("client_id", client); fields.put("redirect_uri", redirect);
                fields.put("response_type", "code"); fields.put("scope", "default"); fields.put("state", state);
                fields.put("code_challenge_method", "S256"); fields.put("code_challenge", ChatGptOAuth.challenge(verifier)); fields.put("resource", MemorySourcePolicy.NOTION_RESOURCE);
                Uri uri = Uri.parse("https://mcp.notion.com/authorize?" + ChatGptOAuth.form(fields));
                getActivity().runOnUiThread(() -> { try { if (generation == selected.generation()) getActivity().startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception unavailable) { selected.cancel(); } });
                Map<String, String> values = callback.await(state, client);
                MemorySourcePolicy.notionIssuer(values);
                selected.notionExchange(values.get("code"), client, redirect, verifier, generation);
                call.resolve(JSObject.fromJSONObject(selected.status()));
            } catch (Exception e) { call.reject(MemorySourceOAuth.safe(e)); }
            finally { if (callback != null) callback.close(); listener = null; loginSession = null; }
        });
    }
    @PluginMethod public void request(PluginCall call) {
        executor.execute(() -> {
            try {
                MemorySourceSession s = session(call);
                String method = call.getString("method", "POST");
                Map<String, String> headers = new HashMap<>(); JSONObject supplied = call.getObject("headers", new JSObject());
                for (String key : new String[] {"Mcp-Session-Id", "MCP-Protocol-Version"}) if (supplied.has(key)) headers.put(key, supplied.getString(key));
                if ("DELETE".equals(method) && MemorySourcePolicy.NOTION.equals(s.kind)) {
                    MemorySourceHttp.Result result = s.authorized(s.endpoint, method, null, headers);
                    call.resolve(new JSObject().put("status", result.status).put("data", "").put("headers", new JSObject())); return;
                }
                if (!"POST".equals(method)) throw new Exception("授权工具请求方法不正确。");
                JSONObject rpc = MemorySourcePolicy.rpc(s.kind, call.getString("body"));
                if (MemorySourcePolicy.NOTION.equals(s.kind)) {
                    if ("tools/call".equals(rpc.optString("method")) && MemorySourcePolicy.NOTION_DIARY.equals(rpc.getJSONObject("params").optString("name"))) {
                        JSONObject receipt = new NotionDiaryWriter(s, headers).write(rpc.getJSONObject("params").getJSONObject("arguments"), call.getBoolean("userWriteRequested", false));
                        JSONObject result = new JSONObject().put("isError", !receipt.optBoolean("verified")).put("structuredContent", receipt).put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", receipt.toString())));
                        JSObject replyHeaders = new JSObject(); for (Map.Entry<String, String> h : headers.entrySet()) replyHeaders.put(h.getKey(), h.getValue());
                        call.resolve(new JSObject().put("status", 200).put("data", new JSONObject().put("jsonrpc", "2.0").put("id", rpc.opt("id")).put("result", result).toString()).put("headers", replyHeaders));
                        return;
                    }
                    MemorySourceHttp.Result result = s.authorized(s.endpoint, "POST", rpc.toString(), headers);
                    JSONObject envelope = result.body.isEmpty() ? null : MemorySourcePolicy.filterNotion(new JSONObject(result.body));
                    if (envelope != null && "tools/list".equals(rpc.optString("method")) && envelope.optJSONObject("result") != null && envelope.getJSONObject("result").optJSONArray("tools") != null && s.status().optBoolean("writingEnabled"))
                        envelope.getJSONObject("result").getJSONArray("tools").put(NotionDiaryWriter.tool());
                    String data = envelope == null ? "" : envelope.toString();
                    JSObject replyHeaders = new JSObject(); for (Map.Entry<String, String> h : result.headers.entrySet()) replyHeaders.put(h.getKey(), h.getValue());
                    call.resolve(new JSObject().put("status", result.status).put("data", data).put("headers", replyHeaders));
                } else {
                    if (!s.status().getBoolean("connected")) throw new Exception("尚未连接 GitHub，请先完成设备登录，再验证真实记忆内容。");
                    JSONObject result;
                    String operation = rpc.optString("method");
                    if ("initialize".equals(operation)) result = new JSONObject().put("protocolVersion", "2025-03-26").put("capabilities", new JSONObject().put("tools", new JSONObject())).put("serverInfo", new JSONObject().put("name", "Polaris GitHub memory adapter").put("version", "1"));
                    else if ("tools/list".equals(operation)) result = GitHubMemoryTools.catalog(s.status().optBoolean("writingEnabled"));
                    else if ("notifications/initialized".equals(operation)) { call.resolve(new JSObject().put("status", 202).put("data", "").put("headers", new JSObject())); return; }
                    else {
                        JSONObject params = rpc.getJSONObject("params"), args = params.getJSONObject("arguments");
                        boolean writing = "github_memory_write".equals(params.getString("name"));
                        JSONObject file = writing ? new GitHubMemoryWriter(s).write(args, call.getBoolean("userWriteRequested", false)) : s.githubFile(args.getString("path"));
                        result = new JSONObject().put("isError", writing && !file.optBoolean("verified")).put("structuredContent", file).put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", file.toString())));
                    }
                    call.resolve(new JSObject().put("status", 200).put("data", new JSONObject().put("jsonrpc", "2.0").put("id", rpc.opt("id")).put("result", result).toString()).put("headers", new JSObject()));
                }
            } catch (Exception e) { call.reject(MemorySourceOAuth.safe(e)); }
        });
    }
    @Override protected void handleOnDestroy() {
        ChatGptLoopbackListener active = listener; if (active != null) active.close();
        for (MemorySourceSession session : sessions.values()) session.cancel();
        executor.shutdownNow(); super.handleOnDestroy();
    }
}
