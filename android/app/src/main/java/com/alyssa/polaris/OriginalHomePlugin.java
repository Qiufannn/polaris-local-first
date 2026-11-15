package com.alyssa.polaris;

import android.content.Intent;
import android.webkit.CookieManager;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

@CapacitorPlugin(name = "OriginalHome")
public class OriginalHomePlugin extends Plugin {
    private final ExecutorService requests = Executors.newFixedThreadPool(3);
    private boolean opening;
    private String approved() { return getContext().getSharedPreferences("polaris_original_home", 0).getString("origin", ""); }
    @PluginMethod public synchronized void open(PluginCall call) {
        if (opening) { call.reject("原小屋正在打开，请先返回当前页面。"); return; }
        opening = true;
        try {
            String origin = OriginalHomePolicy.origin(call.getString("url"));
            getContext().getSharedPreferences("polaris_original_home", 0).edit().putString("origin", origin).apply();
            startActivityForResult(call, new Intent(getContext(), OriginalHomeActivity.class).putExtra("url", origin), "returned");
        } catch (Exception error) { opening = false; call.reject("请填写原小屋的 HTTPS 首页地址。"); }
    }
    @ActivityCallback private synchronized void returned(PluginCall call, ActivityResult result) {
        opening = false;
        if (call != null) call.resolve(new JSObject().put("action", result.getData() == null ? "closed" : result.getData().getStringExtra("action")));
    }
    @PluginMethod public void request(PluginCall call) {
        requests.execute(() -> {
            HttpURLConnection http = null;
            try {
                String url = OriginalHomePolicy.endpoint(call.getString("url")), origin = OriginalHomePolicy.origin(url);
                if (!origin.equals(approved())) throw new Exception("请先在 App 中打开并登录这个原小屋。");
                if ("DELETE".equals(call.getString("method"))) { call.resolve(new JSObject().put("status", 204).put("data", "")); return; }
                if (!"POST".equals(call.getString("method", "POST"))) throw new Exception("原小屋工具仅支持 POST。");
                JSONObject rpc = OriginalHomePolicy.rpc(call.getString("body"));
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies == null || cookies.isEmpty()) throw new Exception("请先在原小屋中完成登录，再测试连接。");
                http = (HttpURLConnection) new URL(url).openConnection(); http.setInstanceFollowRedirects(false); http.setRequestMethod("POST"); http.setConnectTimeout(20000); http.setReadTimeout(45000);
                http.setRequestProperty("Cookie", cookies); http.setRequestProperty("Content-Type", "application/json"); http.setRequestProperty("Accept", "application/json");
                JSONObject headers = call.getObject("headers", new JSObject());
                for (String key : new String[]{"Mcp-Session-Id", "MCP-Protocol-Version"}) if (headers.has(key)) { String value = headers.getString(key); if (value.length() < 2000 && !value.contains("\r") && !value.contains("\n")) http.setRequestProperty(key, value); }
                byte[] bytes = rpc.toString().getBytes(StandardCharsets.UTF_8); http.setDoOutput(true); http.setFixedLengthStreamingMode(bytes.length); try (OutputStream output = http.getOutputStream()) { output.write(bytes); }
                int status = http.getResponseCode(); String mime = http.getContentType();
                if (status == 401 || status == 403 || status >= 300 && status < 400 || mime != null && mime.toLowerCase(java.util.Locale.ROOT).contains("text/html")) throw new Exception("原小屋尚未接受此 App 会话，请先在原网页登录后再核对连接。");
                String text = ""; InputStream input = status >= 400 ? http.getErrorStream() : http.getInputStream();
                if (input != null) try (InputStream stream = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) { byte[] buffer = new byte[4096]; int n; while ((n = stream.read(buffer)) != -1) { if (out.size() + n > 8 * 1024 * 1024) throw new Exception("原小屋响应过大。"); out.write(buffer, 0, n); } text = out.toString("UTF-8"); }
                if (!origin.equals(approved()) || !cookies.equals(CookieManager.getInstance().getCookie(url))) throw new Exception("小屋登录状态已改变，请重新核对。");
                if (status >= 400) throw new Exception("原小屋工具请求未完成（HTTP " + status + "）。写入结果不明时先读取核对，不要重复提交。");
                if (mime != null && mime.contains("text/event-stream")) throw new Exception("原小屋工具响应格式尚未支持，未使用其他线路。");
                String safe = text.isEmpty() ? text : OriginalHomePolicy.filtered(rpc, text);
                call.resolve(new JSObject().put("status", status).put("data", safe));
            } catch (Exception error) { String message = error.getMessage(); call.reject(message != null && (message.startsWith("原小屋") || message.startsWith("请先") || message.startsWith("小屋") || message.startsWith("本连接")) ? message : "原小屋连接未完成，请核对登录和网络；未使用其他账号或线路。"); }
            finally { if (http != null) http.disconnect(); }
        });
    }
    @Override protected void handleOnDestroy() { requests.shutdownNow(); super.handleOnDestroy(); }
}
