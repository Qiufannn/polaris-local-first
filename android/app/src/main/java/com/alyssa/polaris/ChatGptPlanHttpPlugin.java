package com.alyssa.polaris;

import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.Response;
import okhttp3.ResponseBody;
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
        volatile Call activeCall;
        volatile ChatGptConnectionFailure.Stage stage = ChatGptConnectionFailure.Stage.PREPARE;
        boolean resolved;
        Request(String id, String body, PluginCall call) { this.id = id; this.body = body; this.call = call; }

        JSObject event(String type) { return new JSObject().put("requestId", id).put("type", type); }
        void cancel() { cancelled = true; Call current = activeCall; if (current != null) current.cancel(); }

        final EventListener connectionEvents = new EventListener() {
            @Override public void connectStart(Call call, InetSocketAddress address, Proxy proxy) {
                stage = ChatGptConnectionFailure.Stage.CONNECT;
            }
            @Override public void requestHeadersStart(Call call) { stage = ChatGptConnectionFailure.Stage.SEND; }
            @Override public void requestBodyEnd(Call call, long length) { stage = ChatGptConnectionFailure.Stage.WAIT; }
            @Override public void responseHeadersStart(Call call) { stage = ChatGptConnectionFailure.Stage.WAIT; }
        };

        @Override public void run() {
            try {
                ChatGptSession session = ChatGptSession.get(getContext());
                long generation = session.generation();
                byte[] bytes = ChatGptOAuth.planBody(new JSONObject(body)).toString().getBytes(StandardCharsets.UTF_8);
                stage = ChatGptConnectionFailure.Stage.AUTH;
                String credential = session.access(false, null);
                for (int attempt = 0; attempt < 2; attempt++) {
                    if (cancelled || !session.current(generation)) throw new Exception("ChatGPT 账号已切换，请重新发送。");
                    stage = ChatGptConnectionFailure.Stage.CONNECT;
                    try (ChatGptHttpTransport.Exchange exchange = ChatGptHttpTransport.planCall(bytes, credential, connectionEvents)) {
                        activeCall = exchange.call;
                        if (cancelled) { activeCall.cancel(); throw new Exception("订阅请求已取消。"); }
                        try (Response response = activeCall.execute()) {
                            int status = response.code();
                            if (status == 401 && attempt == 0) {
                                response.close();
                                stage = ChatGptConnectionFailure.Stage.AUTH;
                                credential = session.access(true, credential);
                                continue;
                            }
                            if (status < 200 || status >= 300) {
                                if (status == 429) throw new Exception("ChatGPT 订阅额度不足或请求过于频繁，请稍后重试；不会自动切换付费线路。");
                                if (status == 401 || status == 403) throw new Exception("ChatGPT 账号资格或工作区权限不足，请重新登录并检查订阅授权。");
                                throw new Exception("ChatGPT 订阅服务暂时不可用，请稍后重试（HTTP " + status + "）。");
                            }
                            String contentType = response.header("Content-Type");
                            ResponseBody payload = response.body();
                            if (payload == null) throw new Exception("订阅返回了空响应，请稍后重试。");
                            stage = ChatGptConnectionFailure.Stage.RESPONSE;
                            if (!ChatGptPlanResponse.hasEventStreamType(contentType)) payload.source().timeout().timeout(20, TimeUnit.SECONDS);
                            InputStream input = ChatGptPlanResponse.stream(payload.byteStream(), contentType, status);
                            payload.source().timeout().timeout(300, TimeUnit.SECONDS);
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
                Call current = activeCall;
                if (current != null) current.cancel();
            }
        }
    }
}
