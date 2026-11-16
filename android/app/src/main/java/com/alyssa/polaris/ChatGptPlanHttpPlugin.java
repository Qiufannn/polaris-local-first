package com.alyssa.polaris;

import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

@CapacitorPlugin(name = "ChatGptPlanHttp")
public class ChatGptPlanHttpPlugin extends Plugin {
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Request> requests = new ConcurrentHashMap<>();

    @PluginMethod public void start(PluginCall call) {
        String id = call.getString("requestId");
        String body = call.getString("body");
        if (id == null || id.isEmpty() || body == null || body.length() > 32 * 1024 * 1024) {
            call.reject("订阅请求参数不完整或过大。"); return;
        }
        Request request = new Request(id, body, call);
        Request old = requests.put(id, request);
        if (old != null) old.cancel();
        executor.execute(request);
    }

    @PluginMethod public void cancel(PluginCall call) {
        Request request = requests.remove(call.getString("requestId", ""));
        if (request != null) request.cancel();
        call.resolve();
    }

    @Override protected void handleOnDestroy() {
        for (Request request : requests.values()) request.cancel();
        requests.clear(); executor.shutdownNow();
    }

    private final class Request implements Runnable {
        final String id;
        final String body;
        final PluginCall call;
        volatile boolean cancelled;
        volatile HttpURLConnection http;
        boolean resolved;
        Request(String id, String body, PluginCall call) { this.id = id; this.body = body; this.call = call; }

        JSObject event(String type) { return new JSObject().put("requestId", id).put("type", type); }
        void cancel() { cancelled = true; if (http != null) http.disconnect(); }

        @Override public void run() {
            ChatGptConnectionFailure.Stage stage = ChatGptConnectionFailure.Stage.PREPARE;
            try {
                ChatGptSession session = ChatGptSession.get(getContext());
                long generation = session.generation();
                byte[] bytes = ChatGptOAuth.planBody(new JSONObject(body)).toString().getBytes(StandardCharsets.UTF_8);
                stage = ChatGptConnectionFailure.Stage.AUTH;
                String credential = session.access(false, null);
                for (int attempt = 0; attempt < 2; attempt++) {
                    if (cancelled || !session.current(generation)) throw new Exception("ChatGPT 账号已切换，请重新发送。");
                    stage = ChatGptConnectionFailure.Stage.CONNECT;
                    http = ChatGptSession.connection(ChatGptOAuth.RESOURCE + "/responses", "POST");
                    http.setReadTimeout(300000);
                    http.setRequestProperty("Authorization", "Bearer " + credential);
                    http.setRequestProperty("Content-Type", "application/json");
                    http.setRequestProperty("Accept", "text/event-stream");
                    http.setDoOutput(true); http.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream output = http.getOutputStream()) {
                        stage = ChatGptConnectionFailure.Stage.SEND;
                        output.write(bytes);
                    }
                    stage = ChatGptConnectionFailure.Stage.WAIT;
                    int status = http.getResponseCode();
                    if (status == 401 && attempt == 0) {
                        http.disconnect();
                        stage = ChatGptConnectionFailure.Stage.AUTH;
                        credential = session.access(true, credential);
                        continue;
                    }
                    if (status < 200 || status >= 300) {
                        if (status == 429) throw new Exception("ChatGPT 订阅额度不足或请求过于频繁，请稍后重试；不会自动切换付费线路。");
                        if (status == 401 || status == 403) throw new Exception("ChatGPT 账号资格或工作区权限不足，请重新登录并检查订阅授权。");
                        throw new Exception("ChatGPT 订阅服务暂时不可用，请稍后重试（HTTP " + status + "）。");
                    }
                    String contentType = http.getContentType();
                    stage = ChatGptConnectionFailure.Stage.RESPONSE;
                    if (!ChatGptPlanResponse.hasEventStreamType(contentType)) http.setReadTimeout(20000);
                    try (InputStream raw = http.getInputStream()) {
                        InputStream input = ChatGptPlanResponse.stream(raw, contentType, status);
                        http.setReadTimeout(300000);
                        if (cancelled || !session.current(generation)) throw new Exception("ChatGPT 账号已切换，本次回复已停止。");
                        resolved = true;
                        call.resolve(new JSObject().put("status", status).put("contentType", "text/event-stream"));
                        byte[] buffer = new byte[16384];
                        int count;
                        stage = ChatGptConnectionFailure.Stage.STREAM;
                        while (!cancelled && (count = input.read(buffer)) != -1) {
                            if (!session.current(generation)) throw new Exception("ChatGPT 账号已切换，本次回复已停止。");
                            if (count > 0) notifyListeners("event", event("chunk")
                                .put("data", Base64.encodeToString(buffer, 0, count, Base64.NO_WRAP)));
                        }
                    }
                    if (!cancelled) notifyListeners("event", event("complete"));
                    return;
                }
            } catch (Exception error) {
                if (!cancelled) {
                    String message = ChatGptConnectionFailure.message(error, stage);
                    if (resolved) notifyListeners("event", event("error").put("message", message));
                    else call.reject(message);
                } else if (!resolved) call.reject("订阅请求已取消。");
            } finally {
                requests.remove(id, this);
                if (http != null) http.disconnect();
            }
        }
    }
}
