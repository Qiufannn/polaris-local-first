package com.alyssa.polaris;

import static org.junit.Assert.*;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;

public class MemorySourceRecoveryTest {
    private static final String REPO = "https://api.github.com/repos/unit-owner/unit-memory";
    private static final String CLIENT = "Iv1.0123456789abcdef";
    private static final String DEVICE = "{\"device_code\":\"unit-native-device\",\"user_code\":\"ABCD-EFGH\",\"verification_uri\":\"https://github.com/login/device\",\"interval\":5,\"expires_in\":900}";
    private static final String TOKEN = "{\"token_type\":\"bearer\",\"access_token\":\"ghu_unit_access\",\"refresh_token\":\"ghr_unit_refresh\",\"expires_in\":28800,\"scope\":\"\"}";

    @Test public void resumingUsesTheExistingDeviceApprovalAndExposesOnlyPublicCode() throws Exception {
        AtomicLong now = new AtomicLong(1000); AtomicInteger calls = new AtomicInteger();
        MemorySourceSession s = new MemorySourceSession(MemorySourcePolicy.GITHUB, REPO, CLIENT, new MemorySourceTest.Vault(), (u,m,b,t,a,h) -> {
            calls.incrementAndGet(); return new MemorySourceHttp.Result(200, DEVICE);
        }, now::get);
        s.githubStart(); s.githubStart();
        assertEquals(1, calls.get());
        assertEquals("ABCD-EFGH", s.status().getString("userCode"));
        assertFalse(s.status().toString().contains("unit-native-device"));
        now.addAndGet(901000);
        assertFalse(s.status().getBoolean("pending"));
        s.githubStart(); assertEquals(2, calls.get());
    }

    @Test public void networkFailureDoesNotLoseAnApprovedDeviceAndBacksOff() throws Exception {
        AtomicLong now = new AtomicLong(1000); AtomicInteger polls = new AtomicInteger();
        MemorySourceSession s = new MemorySourceSession(MemorySourcePolicy.GITHUB, REPO, CLIENT, new MemorySourceTest.Vault(), (u,m,b,t,a,h) -> {
            if (u.endsWith("/device/code")) return new MemorySourceHttp.Result(200, DEVICE);
            if (polls.incrementAndGet() == 1) throw new SocketTimeoutException("unit-private-request-data");
            return new MemorySourceHttp.Result(200, TOKEN);
        }, now::get);
        s.githubStart(); now.addAndGet(5000);
        JSONObject retry = s.githubPoll();
        assertEquals("pending", retry.getString("state")); assertEquals(10, retry.getInt("pollAfterSeconds"));
        assertTrue(retry.getString("message").contains("请求超时")); assertFalse(retry.toString().contains("unit-private"));
        s.githubPoll(); assertEquals(1, polls.get());
        now.addAndGet(10000); assertEquals("connected", s.githubPoll().getString("state"));
        assertTrue(s.status().getBoolean("connected"));
    }

    @Test public void temporaryHttpFailuresHonorRetryAfterWithoutDiscardingTheGrant() throws Exception {
        AtomicLong now = new AtomicLong(1000); AtomicInteger polls = new AtomicInteger();
        MemorySourceSession s = new MemorySourceSession(MemorySourcePolicy.GITHUB, REPO, CLIENT, new MemorySourceTest.Vault(), (u,m,b,t,a,h) -> {
            if (u.endsWith("/device/code")) return new MemorySourceHttp.Result(200, DEVICE);
            if (polls.incrementAndGet() == 1) {
                Map<String,String> headers = new HashMap<>(); headers.put("retry-after", "30");
                return new MemorySourceHttp.Result(429, "<html>unit-private-server-body</html>", headers);
            }
            return new MemorySourceHttp.Result(200, TOKEN);
        }, now::get);
        s.githubStart(); now.addAndGet(5000);
        JSONObject result = s.githubPoll(); assertEquals(30, result.getInt("pollAfterSeconds"));
        assertFalse(result.toString().contains("unit-private-server"));
        now.addAndGet(29000); s.githubPoll(); assertEquals(1, polls.get());
        now.addAndGet(1000); assertEquals("connected", s.githubPoll().getString("state"));
    }

    @Test public void parsesBothGitHubOAuthResponseFormatsAndRejectsMalformedOrDuplicateFields() throws Exception {
        assertEquals("ghu_unit_access", MemorySourceOAuth.github(new MemorySourceHttp.Result(200, "\ufeff" + TOKEN)).getString("access_token"));
        JSONObject form = MemorySourceOAuth.github(new MemorySourceHttp.Result(200,
            "access_token=ghu_unit_access&token_type=bearer&scope=&expires_in=28800&refresh_token=ghr_unit_refresh"));
        assertEquals("ghu_unit_access", form.getString("access_token")); assertEquals(28800, form.getLong("expires_in"));
        for (String body : new String[]{"<html>unit-private-body</html>", "access_token=unit-first&access_token=unit-second", "access_token=%XX", "unexpected=unit-private"}) {
            Exception invalid = assertThrows(Exception.class, () -> MemorySourceOAuth.github(new MemorySourceHttp.Result(200, body)));
            assertFalse(MemorySourceOAuth.safe(invalid).contains("unit-private"));
            assertTrue(MemorySourceOAuth.safe(invalid).contains("HTTP 200"));
        }
    }

    @Test public void formEncodedPendingThenGrantCompletesTheNativeFlow() throws Exception {
        AtomicLong now = new AtomicLong(1000); AtomicInteger polls = new AtomicInteger();
        MemorySourceSession s = new MemorySourceSession(MemorySourcePolicy.GITHUB, REPO, CLIENT, new MemorySourceTest.Vault(), (u,m,b,t,a,h) -> {
            if (u.endsWith("/device/code")) return new MemorySourceHttp.Result(200,
                "device_code=unit-native-device&user_code=ABCD-EFGH&verification_uri=https%3A%2F%2Fgithub.com%2Flogin%2Fdevice&expires_in=900&interval=5");
            return new MemorySourceHttp.Result(200, polls.incrementAndGet() == 1 ? "error=authorization_pending&error_description=unit-private-description" : "access_token=ghu_unit_access&token_type=bearer&scope=");
        }, now::get);
        s.githubStart(); now.addAndGet(5000); assertEquals("pending", s.githubPoll().getString("state"));
        now.addAndGet(5000); assertEquals("connected", s.githubPoll().getString("state"));
    }

    @Test public void repeatedMalformedResponsesStopWithSafeDiagnosticsAndAllowResume() throws Exception {
        AtomicLong now = new AtomicLong(1000);
        MemorySourceSession s = new MemorySourceSession(MemorySourcePolicy.GITHUB, REPO, CLIENT, new MemorySourceTest.Vault(), (u,m,b,t,a,h) ->
            new MemorySourceHttp.Result(200, u.endsWith("/device/code") ? DEVICE : "<html>unit-private-body</html>"), now::get);
        s.githubStart(); now.addAndGet(5000);
        for (int i = 0; i < 3; i++) { JSONObject pending = s.githubPoll(); now.addAndGet(pending.getInt("pollAfterSeconds") * 1000L); }
        Exception failure = assertThrows(Exception.class, s::githubPoll);
        assertTrue(MemorySourceOAuth.safe(failure).contains("GitHub 授权响应格式"));
        assertFalse(MemorySourceOAuth.safe(failure).contains("ChatGPT 连接失败"));
        assertTrue(s.status().getBoolean("pending")); assertFalse(s.status().getBoolean("connected"));
    }

    @Test public void expiredApprovalHasAnExplicitErrorAndCannotBeResumed() throws Exception {
        AtomicLong now = new AtomicLong(1000);
        MemorySourceSession s = new MemorySourceSession(MemorySourcePolicy.GITHUB, REPO, CLIENT, new MemorySourceTest.Vault(), (u,m,b,t,a,h) ->
            new MemorySourceHttp.Result(200, u.endsWith("/device/code") ? DEVICE : "{\"error\":\"expired_token\",\"error_description\":\"unit-private-provider-text\"}"), now::get);
        s.githubStart(); now.addAndGet(5000);
        String message = MemorySourceOAuth.safe(assertThrows(Exception.class, s::githubPoll));
        assertTrue(message.contains("授权码已过期")); assertFalse(message.contains("unit-private-provider"));
        assertFalse(s.status().getBoolean("pending"));
    }

    @Test public void unknownErrorsNeverEchoProviderBodiesOrCredentialValues() throws Exception {
        for (Exception error : new Exception[]{new IllegalArgumentException("ghu_unit_private"), new org.json.JSONException("ghr_unit_private"), new java.io.IOException("unit-device-private"), new javax.net.ssl.SSLException("unit-private"), new Exception("授权 ghp_unit_private")}) {
            String safe = MemorySourceOAuth.safe(error);
            assertFalse(safe.contains("unit")); assertFalse(safe.contains("ChatGPT 连接失败"));
        }
    }
}
