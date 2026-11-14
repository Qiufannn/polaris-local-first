package com.alyssa.polaris;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

@CapacitorPlugin(name = "McpSiteAuth")
public class McpSiteAuthPlugin extends ChatGptAuthPlugin {
    private final Map<String, ChatGptSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService requests = Executors.newCachedThreadPool();

    @Override protected synchronized ChatGptSession session(PluginCall call) throws Exception {
        String url = McpSitePolicy.resource(call.getString("url"));
        String key = McpSitePolicy.profile(call.getString("serverId"), url);
        ChatGptSession existing = sessions.get(key);
        if (existing != null) return existing;
        if (sessions.size() >= 16) throw new Exception("授权连接过多，请重启应用后重试。");
        ChatGptSession created = new ChatGptSession(new ChatGptCredentialStore(getContext(), key), ChatGptSession::request, url, "resource.invoke");
        sessions.put(key, created);
        return created;
    }

    @PluginMethod public void request(PluginCall call) {
        requests.execute(() -> {
            HttpURLConnection http = null;
            try {
                if (!"POST".equals(call.getString("method", "POST"))) throw new Exception("授权记忆连接仅支持 POST 请求。");
                JSONObject rpc = McpSitePolicy.request(call.getString("body"));
                ChatGptSession session = session(call);
                long generation = session.generation();
                String bearer = session.access(false, null);
                for (int attempt = 0; attempt < 2; attempt++) {
                    if (!session.current(generation)) throw new Exception("授权账号状态已改变，请重新读取。");
                    http = (HttpURLConnection) new URL(session.resource()).openConnection();
                    http.setInstanceFollowRedirects(false);
                    http.setRequestMethod("POST"); http.setConnectTimeout(20000); http.setReadTimeout(45000);
                    http.setRequestProperty("Authorization", "Bearer " + bearer);
                    http.setRequestProperty("Accept", "application/json");
                    http.setRequestProperty("Content-Type", "application/json");
                    // This first profile is stateless and uses only JSON-RPC on the exact resource.
                    byte[] data = rpc.toString().getBytes(StandardCharsets.UTF_8);
                    http.setDoOutput(true); http.setFixedLengthStreamingMode(data.length);
                    try (OutputStream out = http.getOutputStream()) { out.write(data); }
                    int status = http.getResponseCode();
                    if (status == 401 && attempt == 0) { http.disconnect(); bearer = session.access(true, bearer); continue; }
                    if (status < 200 || status >= 300) throw new Exception("授权记忆连接未获服务接受（HTTP " + status + "），请检查账号及该站点的独立客户端访问支持。");
                    String text;
                    try (InputStream input = http.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[4096]; int n;
                        while ((n = input.read(buffer)) != -1) {
                            if (out.size() + n > 2 * 1024 * 1024) throw new Exception("授权记忆响应过大，本次读取已停止。");
                            out.write(buffer, 0, n);
                        }
                        text = out.toString("UTF-8");
                    }
                    if (!session.current(generation)) throw new Exception("授权账号状态已改变，请重新读取。");
                    String safe = text.isEmpty() ? "" : McpSitePolicy.response(rpc, text);
                    call.resolve(new JSObject().put("status", status).put("data", safe));
                    return;
                }
            } catch (Exception error) { call.reject(ChatGptAuthPlugin.safeMessage(error)); }
            finally { if (http != null) http.disconnect(); }
        });
    }

    @Override protected void handleOnDestroy() { requests.shutdownNow(); sessions.clear(); super.handleOnDestroy(); }
}
