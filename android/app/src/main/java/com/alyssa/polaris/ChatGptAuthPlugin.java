package com.alyssa.polaris;

import android.content.Intent;
import android.net.Uri;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

@CapacitorPlugin(name = "ChatGptAuth")
public class ChatGptAuthPlugin extends Plugin {
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Object attemptLock = new Object();
    private Attempt pending;

    private static final class Attempt {
        final ChatGptLoopbackListener listener;
        volatile boolean cancelled;
        Attempt() throws Exception { listener = new ChatGptLoopbackListener(); }
    }

    static String safeMessage(Exception error) {
        return ChatGptConnectionFailure.message(error, ChatGptConnectionFailure.Stage.GENERAL);
    }

    protected ChatGptSession session(PluginCall call) throws Exception { return ChatGptSession.get(getContext()); }

    @PluginMethod public void status(PluginCall call) {
        executor.execute(() -> {
            try { call.resolve(JSObject.fromJSONObject(session(call).status())); }
            catch (Exception error) { call.reject(safeMessage(error)); }
        });
    }

    @PluginMethod public void login(PluginCall call) {
        final Attempt attempt;
        synchronized (attemptLock) {
            if (pending != null) { call.reject("登录正在进行，请先完成或取消。"); return; }
            try { attempt = new Attempt(); pending = attempt; }
            catch (Exception error) { call.reject("无法启动本机授权回调，请稍后重试。"); return; }
        }
        executor.execute(() -> {
            try {
                ChatGptSession session = session(call);
                long generation = session.generation();
                JSONObject saved = session.saved(call.getString("accountRef"));
                String client = saved == null ? ChatGptOAuth.DYNAMIC_CLIENT : saved.getString("client_id");
                String state = ChatGptOAuth.randomValue();
                String nonce = ChatGptOAuth.randomValue();
                String verifier = ChatGptOAuth.randomValue();
                String redirect = attempt.listener.redirect();
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("client_id", client); fields.put("ext_agent_host_id", session.hostId());
                fields.put("response_type", "code"); fields.put("redirect_uri", redirect);
                fields.put("scope", session.scopes()); fields.put("resource", session.resource());
                fields.put("state", state); fields.put("nonce", nonce);
                fields.put("code_challenge_method", "S256"); fields.put("code_challenge", ChatGptOAuth.challenge(verifier));
                if (saved == null) fields.put("agent_name_hint", "Polaris Android");
                else if (!saved.optString("id_token").isEmpty()) fields.put("id_token_hint", saved.getString("id_token"));
                Uri authorization = Uri.parse(ChatGptOAuth.AUTHORIZE + "?" + ChatGptOAuth.form(fields));
                getActivity().runOnUiThread(() -> {
                    try {
                        if (!attempt.cancelled) getActivity().startActivity(new Intent(Intent.ACTION_VIEW, authorization));
                    } catch (Exception unavailable) { attempt.cancelled = true; attempt.listener.close(); }
                });
                Map<String, String> callback = attempt.listener.await(state, client);
                if (callback.containsKey("error")) throw new Exception("授权已取消，原账号和本地聊天未改变。");
                JSONObject record = session.exchange(callback.get("code"), callback.get("client_id"), verifier, redirect, nonce, saved);
                synchronized (attemptLock) {
                    if (attempt.cancelled || pending != attempt) throw new Exception("登录已取消，未保存新账号。");
                    session.install(record, generation);
                }
                call.resolve(JSObject.fromJSONObject(session.status()));
                getActivity().runOnUiThread(() -> {
                    try { getActivity().startActivity(new Intent(getContext(), MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)); }
                    catch (Exception ignored) { /* Browser page also tells the user to return manually. */ }
                });
            } catch (Exception error) { call.reject(attempt.cancelled ? "登录已取消。" : safeMessage(error)); }
            finally {
                attempt.listener.close();
                synchronized (attemptLock) { if (pending == attempt) pending = null; }
            }
        });
    }

    private void cancelAttempt() {
        synchronized (attemptLock) {
            if (pending != null) { pending.cancelled = true; pending.listener.close(); }
        }
    }

    @PluginMethod public void cancelLogin(PluginCall call) { cancelAttempt(); call.resolve(); }

    @PluginMethod public void select(PluginCall call) {
        cancelAttempt();
        executor.execute(() -> {
            try {
                session(call).select(call.getString("accountRef", ""));
                call.resolve(JSObject.fromJSONObject(session(call).status()));
            } catch (Exception error) { call.reject(safeMessage(error)); }
        });
    }

    @PluginMethod public void models(PluginCall call) {
        executor.execute(() -> {
            try { call.resolve(new JSObject().put("models", new JSArray(session(call).models().toString()))); }
            catch (Exception error) { call.reject(safeMessage(error)); }
        });
    }

    @PluginMethod public void disconnect(PluginCall call) {
        cancelAttempt();
        executor.execute(() -> {
            try {
                boolean revoked = session(call).disconnect();
                call.resolve(new JSObject().put("revocationConfirmed", revoked));
            } catch (Exception error) { call.reject(safeMessage(error)); }
        });
    }

    @PluginMethod public void resetLocalConnections(PluginCall call) {
        cancelAttempt();
        executor.execute(() -> {
            try { session(call).reset(); call.resolve(JSObject.fromJSONObject(session(call).status())); }
            catch (Exception error) { call.reject(safeMessage(error)); }
        });
    }

    @Override protected void handleOnDestroy() { cancelAttempt(); executor.shutdownNow(); }
}
