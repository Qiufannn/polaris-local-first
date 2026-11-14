package com.alyssa.polaris;

import static org.junit.Assert.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class ChatGptSessionTest {
    static final class MemoryStorage implements ChatGptSession.Storage {
        JSONObject value;
        MemoryStorage() throws Exception {
            JSONObject record = new JSONObject().put("ref", "test-ref").put("client_id", "issued-client")
                .put("subject", "unit-subject").put("email", "unit@example.invalid").put("nonce", "unit-nonce")
                .put("access_token", "old-access-placeholder").put("refresh_token", "old-refresh-placeholder")
                .put("id_token", "identity-placeholder").put("scope", ChatGptOAuth.SCOPES).put("expires_at", 0);
            value = new JSONObject().put("accounts", new JSONArray().put(record)).put("active", "test-ref");
        }
        public synchronized JSONObject load() throws Exception { return new JSONObject(value.toString()); }
        public synchronized void save(JSONObject next) throws Exception { value = new JSONObject(next.toString()); }
        public String hostId() { return "unit-host"; }
        public synchronized void reset() throws Exception { value = new JSONObject().put("accounts", new JSONArray()); }
    }
    private static ChatGptSession.HttpResult refreshed(String scope) throws Exception {
        return new ChatGptSession.HttpResult(200, new JSONObject().put("token_type", "Bearer")
            .put("access_token", "replacement-access-placeholder").put("refresh_token", "replacement-refresh-placeholder")
            .put("expires_in", 3600).put("scope", scope).toString());
    }
    @Test public void simultaneousRefreshesRotateOnceAndPreserveRegistration() throws Exception {
        MemoryStorage store = new MemoryStorage();
        AtomicInteger requests = new AtomicInteger();
        ChatGptSession session = new ChatGptSession(store, (url, method, body, bearer) -> {
            assertEquals(ChatGptOAuth.TOKEN, url); assertEquals("POST", method); assertNull(bearer);
            assertTrue(body.contains("client_id=issued-client")); assertFalse(body.contains("dynamic_agent_client"));
            assertTrue(body.contains("resource=https%3A%2F%2Fapi.openai.com%2Fv1"));
            requests.incrementAndGet(); return refreshed(ChatGptOAuth.SCOPES);
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> a = pool.submit(() -> session.access(false, null));
            Future<String> b = pool.submit(() -> session.access(false, null));
            assertEquals("replacement-access-placeholder", a.get()); assertEquals(a.get(), b.get());
            assertEquals(1, requests.get());
            assertEquals("replacement-refresh-placeholder", store.value.getJSONArray("accounts").getJSONObject(0).getString("refresh_token"));
        } finally { pool.shutdownNow(); }
    }
    @Test public void revokedSessionClearsTokensAndRetainsAccountMapping() throws Exception {
        MemoryStorage store = new MemoryStorage();
        ChatGptSession session = new ChatGptSession(store, (u, m, b, a) -> new ChatGptSession.HttpResult(400, "{}"));
        assertThrows(Exception.class, () -> session.access(false, null));
        JSONObject record = store.value.getJSONArray("accounts").getJSONObject(0);
        assertFalse(record.has("access_token")); assertFalse(record.has("refresh_token"));
        assertEquals("issued-client", record.getString("client_id"));
        assertFalse(session.status().getJSONArray("accounts").getJSONObject(0).getBoolean("connected"));
    }
    @Test public void missingScopeNeverMakesAnInferenceOrRefreshRequest() throws Exception {
        MemoryStorage store = new MemoryStorage();
        store.value.getJSONArray("accounts").getJSONObject(0).put("scope", "openid email");
        ChatGptSession session = new ChatGptSession(store, (u,m,b,a) -> { fail("No credential may be sent without plan permission"); return null; });
        assertThrows(Exception.class, () -> session.access(false, null));
    }
    @Test public void networkFailureRetainsThePreviousCredentialRecord() throws Exception {
        MemoryStorage store = new MemoryStorage();
        String before = store.value.toString();
        ChatGptSession session = new ChatGptSession(store, (u,m,b,a) -> { throw new java.io.IOException("offline"); });
        assertThrows(Exception.class, () -> session.access(false, null));
        assertEquals(before, store.value.toString());
    }
    @Test public void scopeChangesArePersistedBeforeUsageIsRejected() throws Exception {
        MemoryStorage store = new MemoryStorage();
        ChatGptSession session = new ChatGptSession(store, (u,m,b,a) -> refreshed("openid email"));
        assertThrows(Exception.class, () -> session.access(false, null));
        assertFalse(session.status().getJSONArray("accounts").getJSONObject(0).getBoolean("planGranted"));
        assertEquals("replacement-refresh-placeholder", store.value.getJSONArray("accounts").getJSONObject(0).getString("refresh_token"));
    }
    @Test public void accountSummariesNeverExposeCredentialFields() throws Exception {
        MemoryStorage store = new MemoryStorage();
        ChatGptSession session = new ChatGptSession(store, (u,m,b,a) -> { throw new Exception(); });
        String summary = session.status().toString();
        assertFalse(summary.contains("placeholder")); assertFalse(summary.contains("token")); assertFalse(summary.contains("nonce"));
        assertTrue(summary.contains("unit@example.invalid"));
    }
    @Test public void localSignOutReportsUnconfirmedRevocationAndStopsOldGeneration() throws Exception {
        MemoryStorage store = new MemoryStorage();
        ChatGptSession session = new ChatGptSession(store, (u,m,b,a) -> { throw new java.io.IOException("offline"); });
        long generation = session.generation();
        assertFalse(session.disconnect()); assertFalse(session.current(generation));
        assertEquals("", session.status().getString("activeRef"));
        assertFalse(store.value.getJSONArray("accounts").getJSONObject(0).has("refresh_token"));
    }
    @Test public void resetClearsOnlyConnectionsAndInvalidatesPendingLoginGeneration() throws Exception {
        MemoryStorage store = new MemoryStorage();
        ChatGptSession session = new ChatGptSession(store, (u,m,b,a) -> { throw new Exception(); });
        long before = session.generation();
        JSONObject oldRecord = session.saved("test-ref");
        session.reset();
        assertEquals(0, session.status().getJSONArray("accounts").length());
        assertEquals("unit-host", session.hostId());
        assertThrows(Exception.class, () -> session.install(oldRecord, before));
    }
}
