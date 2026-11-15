package com.alyssa.polaris;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** Provider-specific OAuth only; no model credentials, PATs or client secrets. */
final class MemorySourceSession {
    final String kind, endpoint, resource, clientId;
    private final ChatGptSession.Storage store;
    private final MemorySourceHttp.Transport http;
    private final LongSupplier clock;
    private volatile JSONObject pending;
    private long pollAt, pendingUntil;
    private int interval;
    private int responseFailures;
    private final AtomicLong generation = new AtomicLong();
    private final Object authorizationState = new Object();
    final Object writeLock = new Object();
    long generation() { return generation.get(); }

    MemorySourceSession(String kind, String endpoint, String clientId, ChatGptSession.Storage store, MemorySourceHttp.Transport http) throws Exception {
        this(kind, endpoint, clientId, store, http, System::currentTimeMillis);
    }
    MemorySourceSession(String kind, String endpoint, String clientId, ChatGptSession.Storage store, MemorySourceHttp.Transport http, LongSupplier clock) throws Exception {
        this.kind = kind; this.endpoint = endpoint; this.resource = MemorySourcePolicy.resource(kind, endpoint); this.clientId = clientId;
        if (MemorySourcePolicy.GITHUB.equals(kind)) MemorySourcePolicy.publicClient(clientId);
        this.store = store; this.http = http; this.clock = clock;
    }
    private JSONObject connection() throws Exception {
        JSONObject c = store.load().optJSONObject("connection");
        if (c != null && (!resource.equals(c.optString("resource")) || !kind.equals(c.optString("kind")))) throw new Exception("授权记录与当前来源不匹配。");
        return c;
    }
    synchronized JSONObject status() throws Exception {
        JSONObject c = connection();
        boolean connected = c != null && !c.optString("access_token").isEmpty()
            && (c.optLong("expires_at") == 0 || c.optLong("expires_at") > clock.getAsLong() || !c.optString("refresh_token").isEmpty());
        JSONObject status = new JSONObject().put("connected", connected).put("provider", kind).put("pending", pending != null && clock.getAsLong() < pendingUntil)
            .put("writingEnabled", MemorySourcePolicy.GITHUB.equals(kind) && store.load().optBoolean("github_write_enabled", false));
        if (status.getBoolean("pending")) status.put("userCode", pending.optString("user_code")).put("pollAfterSeconds", interval);
        return status;
    }
    private MemorySourceHttp.Result post(String url, Map<String, String> fields) throws Exception {
        return http.send(url, "POST", ChatGptOAuth.form(fields), "application/x-www-form-urlencoded", null, new HashMap<>());
    }
    private void saveToken(JSONObject response, String registeredClient, String redirect, boolean refreshing) throws Exception {
        if (!"bearer".equalsIgnoreCase(response.optString("token_type")) || response.optString("access_token").isEmpty()) throw new Exception("授权服务未返回有效凭证。");
        JSONObject previous = refreshing ? connection() : null, c = previous == null ? new JSONObject() : new JSONObject(previous.toString());
        if (MemorySourcePolicy.GITHUB.equals(kind) && (!response.getString("access_token").startsWith("ghu_") || !response.optString("scope").isEmpty())) throw new Exception("授权返回的不是 GitHub App 用户凭证，未保存连接。");
        if (response.has("resource") && !resource.equals(response.optString("resource"))) throw new Exception("授权服务返回了其他资源的凭证。");
        if (response.has("expires_in") && response.optLong("expires_in") <= 0) throw new Exception("授权凭证有效期不正确。");
        c.put("kind", kind).put("resource", resource).put("client_id", registeredClient).put("access_token", response.getString("access_token"));
        c.put("expires_at", response.has("expires_in") ? clock.getAsLong() + Math.min(response.getLong("expires_in"), 86400) * 1000 : 0);
        if (response.has("refresh_token")) c.put("refresh_token", response.getString("refresh_token"));
        if (redirect != null) c.put("redirect_uri", redirect);
        JSONObject database = store.load(); database.put("connection", c); store.save(database);
    }
    synchronized int notionPort() throws Exception {
        JSONObject c = connection(); if (c == null) return 0;
        try { return new java.net.URI(c.optString("redirect_uri")).getPort(); } catch (Exception ignored) { return 0; }
    }
    synchronized String registerNotion(String redirect) throws Exception {
        JSONObject c = connection();
        if (c != null && redirect.equals(c.optString("redirect_uri")) && !c.optString("client_id").isEmpty()) return c.getString("client_id");
        JSONObject metadata = new JSONObject().put("client_name", "Polaris Android Memory")
            .put("redirect_uris", new JSONArray().put(redirect)).put("grant_types", new JSONArray().put("authorization_code").put("refresh_token"))
            .put("response_types", new JSONArray().put("code")).put("token_endpoint_auth_method", "none");
        MemorySourceHttp.Result result = http.send("https://mcp.notion.com/register", "POST", metadata.toString(), "application/json", null, new HashMap<>());
        if (result.status != 200 && result.status != 201) throw new Exception("授权服务未接受 Notion 公共客户端注册（HTTP " + result.status + "）。");
        JSONObject registered = new JSONObject(result.body);
        String id = registered.optString("client_id");
        if (id.isEmpty() || id.length() > 256 || ChatGptOAuth.DYNAMIC_CLIENT.equals(id) || !"none".equals(registered.optString("token_endpoint_auth_method", "none"))) throw new Exception("授权服务未提供支持的公共客户端。");
        if (registered.has("redirect_uris")) {
            boolean matches = false; JSONArray redirects = registered.getJSONArray("redirect_uris");
            for (int i = 0; i < redirects.length(); i++) if (redirect.equals(redirects.optString(i))) matches = true;
            if (!matches) throw new Exception("授权回调注册不匹配。");
        }
        return id;
    }
    void notionExchange(String code, String registeredClient, String redirect, String verifier, long expectedGeneration) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>(); fields.put("grant_type", "authorization_code"); fields.put("client_id", registeredClient);
        fields.put("code", code); fields.put("redirect_uri", redirect); fields.put("code_verifier", verifier); fields.put("resource", resource);
        MemorySourceHttp.Result result = post("https://mcp.notion.com/token", fields);
        if (result.status != 200) throw new Exception("授权码兑换未完成（Notion HTTP " + result.status + "），请重新连接。");
        synchronized (this) { synchronized (authorizationState) {
            if (generation.get() != expectedGeneration) throw new Exception("授权已取消，未替换原连接。");
            saveToken(new JSONObject(result.body), registeredClient, redirect, false); generation.incrementAndGet();
        } }
    }
    synchronized JSONObject githubStart() throws Exception {
        if (pending != null && clock.getAsLong() < pendingUntil) return githubCode();
        long started = generation.get();
        Map<String, String> fields = new LinkedHashMap<>(); fields.put("client_id", clientId); fields.put("scope", "offline_access");
        MemorySourceHttp.Result result;
        try { result = post("https://github.com/login/device/code", fields); }
        catch (java.io.IOException network) { throw new Exception("GitHub 授权码请求未完成（" + MemorySourceOAuth.category(network) + "），请稍后重试。"); }
        JSONObject data = MemorySourceOAuth.github(result);
        if (result.status != 200 || data.optString("device_code").isEmpty() || !data.optString("user_code").matches("[A-Z0-9-]{6,20}")
            || !"https://github.com/login/device".equals(data.optString("verification_uri")) || data.optLong("expires_in") <= 0) throw new Exception("授权设备登录未启动，请检查公开 Client ID 和 Enable Device Flow。");
        synchronized (authorizationState) {
            if (started != generation.get()) throw new Exception("授权设备登录已取消。");
            pending = data; interval = Math.max(5, Math.min(900, data.optInt("interval", 5))); pollAt = clock.getAsLong() + interval * 1000L;
            pendingUntil = clock.getAsLong() + Math.min(900, data.getLong("expires_in")) * 1000L;
            responseFailures = 0;
        }
        return githubCode();
    }
    private JSONObject githubCode() throws Exception {
        JSONObject data = pending;
        if (data == null) throw new Exception("GitHub 授权码已取消，请重新连接。");
        return new JSONObject().put("userCode", data.getString("user_code")).put("verificationUri", data.getString("verification_uri")).put("pollAfterSeconds", interval);
    }
    private JSONObject retryGithub(long started, JSONObject device, String message, int minimum) throws Exception {
        if (started != generation.get() || pending != device) throw new Exception("授权设备登录已取消。");
        interval = Math.max(interval, Math.max(5, minimum)); pollAt = clock.getAsLong() + interval * 1000L;
        return new JSONObject().put("state", "pending").put("pollAfterSeconds", interval).put("message", message);
    }
    synchronized JSONObject githubPoll() throws Exception {
        long started = generation.get(); JSONObject device = pending;
        if (pending == null || clock.getAsLong() >= pendingUntil) { pending = null; throw new Exception("授权设备码已取消或过期，请重新连接。"); }
        if (clock.getAsLong() < pollAt) return new JSONObject().put("state", "pending").put("pollAfterSeconds", interval);
        pollAt = clock.getAsLong() + interval * 1000L;
        Map<String, String> fields = new LinkedHashMap<>(); fields.put("client_id", clientId); fields.put("device_code", device.getString("device_code")); fields.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
        MemorySourceHttp.Result result;
        try { result = post("https://github.com/login/oauth/access_token", fields); }
        catch (java.io.IOException network) {
            return retryGithub(started, device, "GitHub 授权结果暂未收到（" + MemorySourceOAuth.category(network) + "），正在自动重试；已在网页批准的不用再次授权。", Math.min(60, interval * 2));
        }
        if (started != generation.get() || pending != device) throw new Exception("授权设备登录已取消。");
        if (result.status == 408 || result.status == 429 || result.status >= 500) {
            int after = 0; try { after = Integer.parseInt(result.headers.getOrDefault("retry-after", "0")); } catch (NumberFormatException ignored) {}
            return retryGithub(started, device, "GitHub 授权服务暂未返回结果（HTTP " + result.status + "），正在自动重试。", Math.max(Math.min(900, after), Math.min(60, interval * 2)));
        }
        JSONObject token;
        try { token = MemorySourceOAuth.github(result); responseFailures = 0; }
        catch (Exception malformed) {
            if (++responseFailures <= 3) return retryGithub(started, device, MemorySourceOAuth.safe(malformed) + " 正在重试，不用再次网页授权。", Math.min(60, interval * 2));
            throw malformed;
        }
        String error = token.optString("error");
        if ("authorization_pending".equals(error)) return new JSONObject().put("state", "pending").put("pollAfterSeconds", interval);
        if ("slow_down".equals(error)) { interval = Math.min(900, Math.max(interval + 5, token.optInt("interval"))); pollAt = clock.getAsLong() + interval * 1000L; return new JSONObject().put("state", "pending").put("pollAfterSeconds", interval); }
        if (result.status != 200 || !error.isEmpty()) {
            pending = null;
            if ("expired_token".equals(error)) throw new Exception("GitHub 授权码已过期，请重新连接获取新验证码。");
            if ("access_denied".equals(error)) throw new Exception("GitHub 授权未获批准，请本人确认后重新连接。");
            if ("incorrect_client_credentials".equals(error) || "invalid_client".equals(error)) throw new Exception("GitHub 授权客户端不匹配，请核对公开 Client ID。");
            if ("device_flow_disabled".equals(error)) throw new Exception("GitHub 授权应用尚未开启 Enable Device Flow。");
            throw new Exception("GitHub 授权结果未获接受（HTTP " + result.status + "），请重新连接；不是 ChatGPT 登录失败。");
        }
        synchronized (authorizationState) {
            if (started != generation.get() || pending != device) throw new Exception("授权设备登录已取消。");
            saveToken(token, clientId, null, false); pending = null; generation.incrementAndGet();
        }
        return new JSONObject().put("state", "connected").put("pollAfterSeconds", 0);
    }
    void cancel() { synchronized (authorizationState) { pending = null; generation.incrementAndGet(); } }
    synchronized void disconnect() throws Exception { cancel(); store.reset(); }
    synchronized JSONObject setWritingEnabled(boolean enabled) throws Exception {
        if (!MemorySourcePolicy.GITHUB.equals(kind) || !status().getBoolean("connected")) throw new Exception("GitHub 记忆写回请先连接此仓库。");
        JSONObject database = store.load(); database.put("github_write_enabled", enabled); store.save(database); return status();
    }
    synchronized void requireWritingEnabled() throws Exception {
        if (!MemorySourcePolicy.GITHUB.equals(kind) || !status().getBoolean("connected") || !store.load().optBoolean("github_write_enabled", false)) throw new Exception("GitHub 记忆写回未开启，请先在此服务卡允许新增和更正记忆。");
    }
    synchronized JSONObject writeReceipt(String fingerprint) throws Exception {
        JSONArray receipts = store.load().optJSONArray("github_write_receipts");
        if (receipts != null) for (int i = receipts.length() - 1; i >= 0; i--) if (fingerprint.equals(receipts.getJSONObject(i).optString("fingerprint"))) return new JSONObject(receipts.getJSONObject(i).toString());
        return null;
    }
    synchronized void saveWriteReceipt(JSONObject receipt) throws Exception {
        JSONObject database = store.load(); JSONArray previous = database.optJSONArray("github_write_receipts"), next = new JSONArray();
        if (previous != null) for (int i = Math.max(0, previous.length() - 11); i < previous.length(); i++) if (!receipt.getString("fingerprint").equals(previous.getJSONObject(i).optString("fingerprint"))) next.put(previous.getJSONObject(i));
        next.put(receipt); database.put("github_write_receipts", next); store.save(database);
    }
    private synchronized String access(boolean force, String rejected) throws Exception {
        JSONObject c = connection();
        if (c == null || c.optString("access_token").isEmpty()) throw new Exception("尚未连接这个记忆来源，请先完成登录。");
        String token = c.getString("access_token");
        if ((!force || (rejected != null && !ChatGptOAuth.same(rejected, token))) && (c.optLong("expires_at") == 0 || c.optLong("expires_at") > clock.getAsLong() + 60000)) return token;
        if (c.optString("refresh_token").isEmpty()) throw new Exception("授权已到期，请重新连接此来源。");
        Map<String, String> fields = new LinkedHashMap<>(); fields.put("client_id", c.getString("client_id")); fields.put("grant_type", "refresh_token"); fields.put("refresh_token", c.getString("refresh_token"));
        if (MemorySourcePolicy.NOTION.equals(kind)) fields.put("resource", resource);
        String endpoint = MemorySourcePolicy.NOTION.equals(kind) ? "https://mcp.notion.com/token" : "https://github.com/login/oauth/access_token";
        long refreshingGeneration = generation.get();
        MemorySourceHttp.Result result = post(endpoint, fields);
        JSONObject next = MemorySourcePolicy.GITHUB.equals(kind) ? MemorySourceOAuth.github(result) : new JSONObject(result.body);
        if (refreshingGeneration != generation.get()) throw new Exception("授权状态已改变，未保存刷新结果。");
        if (result.status != 200 || next.has("error")) {
            if (result.status < 500) { c.remove("access_token"); c.remove("refresh_token"); JSONObject db = store.load(); db.put("connection", c); store.save(db); generation.incrementAndGet(); }
            throw new Exception("授权刷新未完成，请重新连接此来源。");
        }
        synchronized (authorizationState) {
            if (refreshingGeneration != generation.get()) throw new Exception("授权状态已改变，未保存刷新结果。");
            saveToken(next, c.getString("client_id"), c.optString("redirect_uri", null), true); return connection().getString("access_token");
        }
    }
    MemorySourceHttp.Result sourceRequest(String url, String method, String body, Map<String, String> headers) throws Exception {
        java.net.URI target = new java.net.URI(url);
        boolean pinnedRead = "GET".equals(method) && target.getRawQuery() != null && target.getRawQuery().matches("(?i)ref=(?:[a-f0-9]{40}|[a-f0-9]{64})");
        if (MemorySourcePolicy.NOTION.equals(kind) ? (!MemorySourcePolicy.NOTION_MCP.equals(url) || !("POST".equals(method) || "DELETE".equals(method)))
            : (!url.startsWith(endpoint + "/contents/") || !("GET".equals(method) || "PUT".equals(method)) || (target.getRawQuery() != null && !pinnedRead) || target.getRawFragment() != null)) throw new Exception("授权凭证不能用于其他来源。");
        if (MemorySourcePolicy.GITHUB.equals(kind)) {
            String prefix = new java.net.URI(endpoint).getPath() + "/contents/";
            if (!target.getPath().startsWith(prefix)) throw new Exception("授权记忆文件路径不正确。");
            String canonical = MemorySourcePolicy.contentUrl(endpoint, target.getPath().substring(prefix.length()), pinnedRead ? target.getRawQuery().substring(4) : null);
            if (!canonical.equals(url)) throw new Exception("授权记忆文件地址不正确。");
        }
        if ("PUT".equals(method)) requireWritingEnabled();
        long captured = generation.get();
        String token = access(false, null);
        for (int attempt = 0; attempt < 2; attempt++) {
            if ("PUT".equals(method)) requireWritingEnabled();
            synchronized (this) { if (captured != generation.get()) throw new Exception("授权状态已改变，请重新读取。"); }
            MemorySourceHttp.Result result = http.send(url, method, body, "application/json", token, headers);
            if (result.status == 401 && attempt == 0) { token = access(true, token); continue; }
            synchronized (this) { if (captured != generation.get()) throw new Exception("授权状态已改变，请重新读取。"); }
            return result;
        }
        throw new Exception("授权读取未完成。");
    }
    MemorySourceHttp.Result authorized(String url, String method, String body, Map<String, String> headers) throws Exception {
        MemorySourceHttp.Result result = sourceRequest(url, method, body, headers);
        if (result.status < 200 || result.status >= 300) throw new MemorySourceHttp.Failure(result.status);
        return result;
    }
    JSONObject githubFile(String path) throws Exception {
        return githubFile(path, null);
    }
    JSONObject githubFile(String path, String commit) throws Exception {
        JSONObject file = new JSONObject(authorized(MemorySourcePolicy.contentUrl(endpoint, path, commit), "GET", null, new HashMap<>()).body);
        if (!"file".equals(file.optString("type")) || !"base64".equals(file.optString("encoding")) || !path.equals(file.optString("path")) || !file.optString("sha").matches("(?:[a-fA-F0-9]{40}|[a-fA-F0-9]{64})") || file.optLong("size") > 512 * 1024) throw new Exception("授权仓库未返回受支持的记忆文件。");
        byte[] content = Base64.getMimeDecoder().decode(file.getString("content"));
        if (content.length > 512 * 1024) throw new Exception("授权记忆文件过大。");
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance(file.getString("sha").length() == 40 ? "SHA-1" : "SHA-256");
        digest.update(("blob " + content.length + "\0").getBytes(StandardCharsets.US_ASCII)); digest.update(content);
        StringBuilder actual = new StringBuilder(); for (byte b : digest.digest()) actual.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        if (!actual.toString().equalsIgnoreCase(file.getString("sha")) || content.length != file.optLong("size")) throw new Exception("授权记忆文件未通过 Git blob 校验。");
        String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(content)).toString();
        return new JSONObject().put("provider", "github").put("path", path).put("blob_sha", file.getString("sha")).put("read_at", clock.getAsLong()).put("text", text);
    }
}
