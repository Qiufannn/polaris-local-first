package com.alyssa.polaris;

import android.content.Context;
import com.nimbusds.jwt.JWTClaimsSet;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** The only owner of OAuth records. Nothing in this class returns tokens to the WebView. */
final class ChatGptSession {
    private static ChatGptSession instance;
    interface Storage {
        JSONObject load() throws Exception;
        void save(JSONObject value) throws Exception;
        String hostId() throws Exception;
        void reset() throws Exception;
    }
    interface Transport {
        HttpResult request(String endpoint, String method, String body, String bearer) throws Exception;
    }
    private final Storage store;
    private final Transport transport;
    private long generation;
    private final String resource;
    private final String requiredScope;

    static synchronized ChatGptSession get(Context context) {
        if (instance == null) instance = new ChatGptSession(context.getApplicationContext());
        return instance;
    }

    private ChatGptSession(Context context) { this(new ChatGptCredentialStore(context), ChatGptSession::request); }
    ChatGptSession(Storage store, Transport transport) { this(store, transport, ChatGptOAuth.RESOURCE, ChatGptOAuth.PLAN_SCOPE); }
    ChatGptSession(Storage store, Transport transport, String resource, String requiredScope) {
        this.store = store; this.transport = transport; this.resource = resource; this.requiredScope = requiredScope;
    }
    String resource() { return resource; }
    String scopes() { return ChatGptOAuth.PLAN_SCOPE.equals(requiredScope) ? ChatGptOAuth.SCOPES : "openid profile email offline_access resource.invoke"; }
    private boolean granted(String scope) {
        for (String part : scope.trim().split("\\s+")) if (requiredScope.equals(part)) return true;
        return false;
    }
    private void requireResource(JSONObject record) throws Exception {
        if (!ChatGptOAuth.same(resource, record.optString("resource", ChatGptOAuth.RESOURCE))) throw new Exception("授权资源不匹配，请重新连接该服务。");
    }

    static final class HttpResult {
        final int status;
        final String body;
        HttpResult(int status, String body) { this.status = status; this.body = body; }
    }

    static HttpURLConnection connection(String endpoint, String method) throws Exception {
        URL url = new URL(endpoint);
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443)
            || !("auth.openai.com".equals(url.getHost()) || "api.openai.com".equals(url.getHost()))) {
            throw new Exception("订阅连接地址不正确。");
        }
        HttpURLConnection http = (HttpURLConnection) url.openConnection();
        http.setInstanceFollowRedirects(false);
        http.setRequestMethod(method);
        http.setConnectTimeout(20000);
        http.setReadTimeout(45000);
        return http;
    }

    static HttpResult request(String endpoint, String method, String body, String bearer) throws Exception {
        HttpURLConnection http = connection(endpoint, method);
        try {
            if (bearer != null) http.setRequestProperty("Authorization", "Bearer " + bearer);
            http.setRequestProperty("Accept", "application/json");
            if (body != null) {
                http.setDoOutput(true);
                http.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                http.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = http.getOutputStream()) { output.write(bytes); }
            }
            int status = http.getResponseCode();
            InputStream input = status >= 400 ? http.getErrorStream() : http.getInputStream();
            if (input == null) return new HttpResult(status, "");
            try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = stream.read(buffer)) != -1) {
                    if (output.size() + n > 1024 * 1024) throw new Exception("授权服务返回的数据过大。");
                    output.write(buffer, 0, n);
                }
                return new HttpResult(status, output.toString("UTF-8"));
            }
        } finally { http.disconnect(); }
    }

    synchronized String hostId() throws Exception { return store.hostId(); }
    synchronized long generation() { return generation; }
    synchronized boolean current(long captured) { return captured == generation; }

    private JSONObject account(JSONObject database, String ref) throws Exception {
        JSONArray accounts = database.getJSONArray("accounts");
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject item = accounts.getJSONObject(i);
            if (ref.equals(item.optString("ref"))) { requireResource(item); return item; }
        }
        throw new Exception("请选择或重新登录 ChatGPT 账号。");
    }

    synchronized JSONObject saved(String ref) throws Exception {
        if (ref == null || ref.isEmpty()) return null;
        return new JSONObject(account(store.load(), ref).toString());
    }

    synchronized JSONObject status() throws Exception {
        JSONObject database = store.load();
        JSONArray accounts = database.getJSONArray("accounts");
        JSONArray summaries = new JSONArray();
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject record = accounts.getJSONObject(i);
            requireResource(record);
            summaries.put(new JSONObject().put("ref", record.getString("ref"))
                .put("email", record.optString("email")).put("subject", record.getString("subject"))
                // The client registration is bound to the selected workspace. Do not decode opaque access metadata.
                .put("registration", record.getString("client_id"))
                .put("connected", !record.optString("refresh_token").isEmpty())
                .put("scopeGranted", granted(record.optString("scope")))
                .put("planGranted", ChatGptOAuth.hasPlanScope(record.optString("scope"))));
        }
        return new JSONObject().put("accounts", summaries).put("activeRef", database.optString("active"));
    }

    synchronized void select(String ref) throws Exception {
        JSONObject database = store.load();
        account(database, ref);
        database.put("active", ref);
        store.save(database);
        generation++;
    }

    private JSONObject tokenRecord(JSONObject response, String client, String nonce, JSONObject previous, boolean refresh) throws Exception {
        if (!"Bearer".equalsIgnoreCase(response.optString("token_type")) || response.optString("access_token").isEmpty()
            || response.optString("refresh_token").isEmpty() || response.optLong("expires_in") <= 0) {
            throw new Exception("授权凭证不完整，请重新登录。");
        }
        if (response.has("resource") && !ChatGptOAuth.same(resource, response.optString("resource"))) {
            throw new Exception("授权服务返回了其他资源的凭证，未保存连接。");
        }
        if (!ChatGptOAuth.RESOURCE.equals(resource) && ChatGptOAuth.hasPlanScope(response.optString("scope"))) {
            throw new Exception("授权返回了模型订阅权限，未将该凭证用于记忆服务。");
        }
        JSONObject next = previous == null ? new JSONObject() : new JSONObject(previous.toString());
        String identity = response.optString("id_token");
        if (previous == null || !identity.isEmpty()) {
            HttpResult keys = transport.request(ChatGptOAuth.JWKS, "GET", null, null);
            if (keys.status != 200) throw new Exception("身份公钥暂时不可用，请稍后重试。");
            JWTClaimsSet claims = ChatGptOAuth.verifyIdentity(identity, keys.body, client, nonce, System.currentTimeMillis(), !refresh);
            if (previous != null && !ChatGptOAuth.same(previous.getString("subject"), claims.getSubject())) {
                throw new Exception("返回的账号与所选账号不一致，原账号未替换。");
            }
            next.put("subject", claims.getSubject());
            next.put("email", claims.getStringClaim("email") == null ? "" : claims.getStringClaim("email"));
            next.put("id_token", identity);
        }
        next.put("client_id", client).put("nonce", nonce).put("resource", resource);
        next.put("access_token", response.getString("access_token"));
        next.put("refresh_token", response.getString("refresh_token"));
        next.put("expires_at", System.currentTimeMillis() + Math.min(response.getLong("expires_in"), 86400) * 1000L);
        next.put("scope", response.has("scope") ? response.getString("scope") : next.optString("scope"));
        next.put("earliest_refresh_at", response.optLong("earliest_refresh_at", 0) * 1000L);
        if (!next.has("ref")) next.put("ref", ChatGptOAuth.randomValue());
        return next;
    }

    JSONObject exchange(String code, String client, String verifier, String redirect, String nonce, JSONObject previous) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("grant_type", "authorization_code"); fields.put("client_id", client);
        fields.put("code", code); fields.put("code_verifier", verifier);
        fields.put("redirect_uri", redirect); fields.put("resource", resource);
        HttpResult result = transport.request(ChatGptOAuth.TOKEN, "POST", ChatGptOAuth.form(fields), null);
        if (result.status != 200) throw new Exception("授权码兑换失败，请重新发起登录（不要重复使用旧回调）。");
        return tokenRecord(new JSONObject(result.body), client, nonce, previous, false);
    }

    synchronized void install(JSONObject record, long expectedGeneration) throws Exception {
        if (generation != expectedGeneration) throw new Exception("登录期间账号状态已改变，请重新发起。");
        requireResource(record);
        JSONObject database = store.load();
        JSONArray before = database.getJSONArray("accounts");
        JSONArray after = new JSONArray();
        for (int i = 0; i < before.length(); i++) {
            JSONObject old = before.getJSONObject(i);
            if (!(old.getString("client_id").equals(record.getString("client_id"))
                && old.getString("subject").equals(record.getString("subject")))) after.put(old);
        }
        after.put(record);
        database.put("accounts", after).put("active", record.getString("ref"));
        store.save(database);
        generation++;
    }

    /** One synchronized refresh for this process; persisted rotating fields are replaced in one AtomicFile commit. */
    synchronized String access(boolean force, String rejectedAccess) throws Exception {
        JSONObject database = store.load();
        JSONObject record = account(database, database.optString("active"));
        if (!granted(record.optString("scope"))) throw new Exception("尚未授予当前资源的使用权限，请重新登录授权。");
        if (record.optString("refresh_token").isEmpty()) throw new Exception("ChatGPT 连接已断开，请重新登录。");
        String access = record.optString("access_token");
        long now = System.currentTimeMillis();
        if ((!force || (rejectedAccess != null && !ChatGptOAuth.same(access, rejectedAccess)))
            && record.optLong("expires_at") > now + 60000) return access;
        if (now < record.optLong("earliest_refresh_at")) {
            if (!force && record.optLong("expires_at") > now + 5000) return access;
            throw new Exception("授权暂时不能刷新，请稍后重试或重新登录。");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("grant_type", "refresh_token"); fields.put("client_id", record.getString("client_id"));
        fields.put("refresh_token", record.getString("refresh_token")); fields.put("resource", resource);
        HttpResult result = transport.request(ChatGptOAuth.TOKEN, "POST", ChatGptOAuth.form(fields), null);
        if (result.status != 200) {
            if (result.status == 400 || result.status == 401 || result.status == 403) {
                clearTokens(record); store.save(database); generation++;
                throw new Exception("ChatGPT 授权已过期或撤销，请重新登录；本地聊天仍保留。");
            }
            throw new Exception("暂时无法刷新 ChatGPT 授权，请检查网络后重试。");
        }
        JSONObject next = tokenRecord(new JSONObject(result.body), record.getString("client_id"), record.getString("nonce"), record, true);
        JSONArray accounts = database.getJSONArray("accounts");
        for (int i = 0; i < accounts.length(); i++) if (record.getString("ref").equals(accounts.getJSONObject(i).getString("ref"))) accounts.put(i, next);
        store.save(database);
        if (!granted(next.optString("scope"))) throw new Exception("授权使用权限已改变，请重新登录授权。");
        return next.getString("access_token");
    }

    synchronized JSONArray models() throws Exception {
        if (!ChatGptOAuth.RESOURCE.equals(resource)) throw new Exception("授权资源不是模型服务。");
        String credential = access(false, null);
        HttpResult result = transport.request(ChatGptOAuth.RESOURCE + "/models", "GET", null, credential);
        if (result.status == 401) result = transport.request(ChatGptOAuth.RESOURCE + "/models", "GET", null, access(true, credential));
        if (result.status != 200) throw new Exception("无法读取该账号的订阅模型，请检查资格、工作区权限或稍后重试。");
        JSONArray source = new JSONObject(result.body).getJSONArray("models");
        JSONArray models = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject model = source.getJSONObject(i);
            if ("list".equals(model.optString("visibility")) && !model.optString("slug").isEmpty()) {
                models.put(new JSONObject().put("id", model.getString("slug")).put("label", model.optString("display_name", model.getString("slug"))));
            }
        }
        return models;
    }

    private static void clearTokens(JSONObject record) {
        for (String field : new String[] {"access_token", "refresh_token", "id_token", "nonce", "scope", "expires_at", "earliest_refresh_at"}) record.remove(field);
    }

    synchronized void reset() throws Exception { store.reset(); generation++; }

    synchronized boolean disconnect() throws Exception {
        JSONObject database = store.load();
        String active = database.optString("active");
        if (active.isEmpty()) return true;
        JSONObject record = account(database, active);
        boolean revoked = record.optString("refresh_token").isEmpty();
        try {
            if (!revoked) {
                HttpResult discovery = transport.request(ChatGptOAuth.ISSUER + "/.well-known/openid-configuration", "GET", null, null);
                if (discovery.status == 200) {
                    String endpoint = new JSONObject(discovery.body).getString("revocation_endpoint");
                    URL url = new URL(endpoint);
                    if (!"auth.openai.com".equals(url.getHost())) throw new Exception("撤销地址不正确。");
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("token", record.getString("refresh_token")); fields.put("token_type_hint", "refresh_token");
                    fields.put("client_id", record.getString("client_id"));
                    revoked = transport.request(endpoint, "POST", ChatGptOAuth.form(fields), null).status == 200;
                }
            }
        } catch (Exception ignored) { revoked = false; }
        clearTokens(record);
        database.remove("active");
        store.save(database);
        generation++;
        return revoked;
    }
}
