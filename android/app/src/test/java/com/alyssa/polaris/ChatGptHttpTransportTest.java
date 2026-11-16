package com.alyssa.polaris;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import okhttp3.Call;
import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Real loopback TLS exchanges with synthetic credentials; no external model/account requests. */
public class ChatGptHttpTransportTest {
    private MockWebServer server;
    private HandshakeCertificates certificates;
    private OkHttpClient client;
    private final List<ChatGptHttpTransport.Exchange> exchanges = new ArrayList<>();

    @Before public void setup() throws Exception {
        HeldCertificate certificate = new HeldCertificate.Builder()
            .addSubjectAlternativeName("api.openai.com").addSubjectAlternativeName("auth.openai.com").build();
        certificates = new HandshakeCertificates.Builder().heldCertificate(certificate).build();
        server = new MockWebServer();
        server.useHttps(certificates.sslSocketFactory(), false);
        server.start(InetAddress.getByName("127.0.0.1"), 0);
        HandshakeCertificates trust = new HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate()).build();
        client = localBuilder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager()).build();
    }

    private OkHttpClient.Builder localBuilder() {
        // Test-only port rewrite and DNS. Production keeps the OS resolver, proxy and trust store.
        return ChatGptHttpTransport.builder(1500).proxy(Proxy.NO_PROXY)
            .dns(host -> {
                assertTrue(host.equals("api.openai.com") || host.equals("auth.openai.com"));
                return Collections.singletonList(InetAddress.getByName("127.0.0.1"));
            }).addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                .url(chain.request().url().newBuilder().port(server.getPort()).build()).build()));
    }

    @After public void cleanup() throws Exception {
        for (ChatGptHttpTransport.Exchange exchange : exchanges) exchange.close();
        if (client != null) { client.connectionPool().evictAll(); client.dispatcher().executorService().shutdownNow(); }
        if (server != null) server.shutdown();
    }

    private Request plan() throws Exception {
        return ChatGptHttpTransport.planRequest("{\"model\":\"synthetic\",\"input\":[\"你好\"],\"stream\":true}".getBytes(StandardCharsets.UTF_8), "synthetic-bearer");
    }

    private Call call(Request request) {
        ChatGptHttpTransport.Exchange exchange = ChatGptHttpTransport.freshCall(client, request);
        exchanges.add(exchange);
        return exchange.call;
    }

    @Test public void productionPolicyUsesModernTlsAndH2WithoutRetriesRedirectsOrCustomTrust() throws Exception {
        OkHttpClient production = ChatGptHttpTransport.builder(300000).build();
        assertEquals(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1), production.protocols());
        assertEquals(Collections.singletonList(ConnectionSpec.MODERN_TLS), production.connectionSpecs());
        assertFalse(production.retryOnConnectionFailure());
        assertFalse(production.followRedirects()); assertFalse(production.followSslRedirects());
        assertEquals(0, production.connectionPool().idleConnectionCount());
        assertEquals(20000, production.connectTimeoutMillis()); assertEquals(45000, production.writeTimeoutMillis());
        assertEquals(300000, production.readTimeoutMillis());
        assertTrue(plan().body().isOneShot());
        assertTrue(ChatGptHttpTransport.formRequest(ChatGptOAuth.TOKEN, "POST", "grant_type=synthetic", null).body().isOneShot());
    }

    @Test public void endpointBoundaryRejectsOtherHostsSchemesPortsFragmentsAndEmbeddedCredentials() {
        for (String url : new String[] {"http://api.openai.com/v1/models", "https://api.openai.com.evil.invalid/", "https://api.openai.com:444/",
            "https://name:password@api.openai.com/", "https://api.openai.com/v1/models#fragment", "https://other.invalid/", "not a url"}) {
            assertThrows(Exception.class, () -> ChatGptHttpTransport.formRequest(url, "GET", null, "synthetic"));
        }
        assertThrows(Exception.class, () -> ChatGptHttpTransport.formRequest(ChatGptOAuth.TOKEN, "DELETE", null, null));
        assertThrows(Exception.class, () -> ChatGptHttpTransport.formRequest(ChatGptOAuth.TOKEN, "GET", "unexpected body", null));
    }

    @Test public void negotiatesH2AndPreservesStreamingBytesAndSubscriptionRequest() throws Exception {
        String sse = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"你好🌙\"}\n\n"
            + "data: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\n";
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(sse).throttleBody(3, 1, TimeUnit.MILLISECONDS));
        try (Response response = call(plan()).execute()) {
            assertEquals(Protocol.HTTP_2, response.protocol());
            assertArrayEquals(sse.getBytes(StandardCharsets.UTF_8), ChatGptPlanResponse.stream(response.body().byteStream(), response.header("Content-Type"), response.code()).readAllBytes());
        }
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("POST", request.getMethod()); assertEquals("/v1/responses", request.getPath());
        assertEquals("Bearer synthetic-bearer", request.getHeader("Authorization"));
        assertEquals("text/event-stream", request.getHeader("Accept"));
        assertTrue(request.getBody().readUtf8().contains("你好"));
    }

    @Test public void interoperatesWithHttp11ServersAndFormAuthorization() throws Exception {
        server.setProtocols(Collections.singletonList(Protocol.HTTP_1_1));
        server.enqueue(new MockResponse().setBody("{\"synthetic\":true}"));
        Request form = ChatGptHttpTransport.formRequest(ChatGptOAuth.TOKEN, "POST", "grant_type=synthetic&code=test", null);
        try (Response response = call(form).execute()) {
            assertEquals(Protocol.HTTP_1_1, response.protocol());
            assertEquals("{\"synthetic\":true}", ChatGptHttpTransport.boundedText(response.body().byteStream()));
        }
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("application/x-www-form-urlencoded; charset=utf-8", request.getHeader("Content-Type"));
        assertEquals("grant_type=synthetic&code=test", request.getBody().readUtf8());
        assertNull(request.getHeader("Authorization"));
    }

    @Test public void readsChunkedHttp11SseWithoutExposingTransferFraming() throws Exception {
        server.setProtocols(Collections.singletonList(Protocol.HTTP_1_1));
        String sse = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"你好🌙\"}\n\n";
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setChunkedBody(sse, 3));
        try (Response response = call(plan()).execute()) {
            assertEquals(Protocol.HTTP_1_1, response.protocol());
            assertArrayEquals(sse.getBytes(StandardCharsets.UTF_8), ChatGptPlanResponse.stream(response.body().byteStream(), response.header("Content-Type"), response.code()).readAllBytes());
        }
    }

    @Test public void untrustedServerCertificateStillFailsBeforeSendingCredentials() throws Exception {
        OkHttpClient untrusted = localBuilder().build();
        try {
            assertThrows(SSLException.class, () -> { try (Response ignored = untrusted.newCall(plan()).execute()) {} });
            assertEquals(0, server.getRequestCount());
        } finally { untrusted.connectionPool().evictAll(); untrusted.dispatcher().executorService().shutdownNow(); }
    }

    @Test public void hostnameValidationIsNotReplacedByTheTestTrustStore() throws Exception {
        HeldCertificate wrong = new HeldCertificate.Builder().addSubjectAlternativeName("wrong.invalid").build();
        HandshakeCertificates serverKeys = new HandshakeCertificates.Builder().heldCertificate(wrong).build();
        HandshakeCertificates trust = new HandshakeCertificates.Builder().addTrustedCertificate(wrong.certificate()).build();
        server.useHttps(serverKeys.sslSocketFactory(), false);
        OkHttpClient mismatched = localBuilder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager()).build();
        try {
            assertThrows(SSLException.class, () -> { try (Response ignored = mismatched.newCall(plan()).execute()) {} });
            assertEquals(0, server.getRequestCount());
        } finally { mismatched.connectionPool().evictAll(); mismatched.dispatcher().executorService().shutdownNow(); }
    }

    @Test public void redirectsNeverForwardTheBearerOrFormBody() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(307).setHeader("Location", "https://other.invalid/stolen"));
        try (Response response = call(plan()).execute()) { assertEquals(307, response.code()); }
        assertEquals(1, server.getRequestCount());
    }

    @Test public void disconnectAfterPostDoesNotAutomaticallyReplayTheRequest() throws Exception {
        server.setProtocols(Collections.singletonList(Protocol.HTTP_1_1));
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        assertThrows(IOException.class, () -> { try (Response ignored = call(plan()).execute()) {} });
        assertEquals(1, server.getRequestCount());
    }

    @Test public void retryAfterOn408Or503DoesNotReplayInferenceOrRotatingAuthorizationPosts() throws Exception {
        for (int status : new int[] {408, 503}) for (Request request : new Request[] {plan(),
            ChatGptHttpTransport.formRequest(ChatGptOAuth.TOKEN, "POST", "grant_type=synthetic", null)}) {
            int before = server.getRequestCount();
            server.enqueue(new MockResponse().setResponseCode(status).setHeader("Retry-After", "0"));
            try (Response response = call(request).execute()) { assertEquals(status, response.code()); }
            assertEquals(before + 1, server.getRequestCount());
        }
    }

    @Test public void nextManualCallOpensAFreshConnectionInsteadOfReusingAnIdleSocket() throws Exception {
        server.setProtocols(Collections.singletonList(Protocol.HTTP_1_1));
        for (int i = 0; i < 2; i++) {
            server.enqueue(new MockResponse().setBody("synthetic"));
            try (Response response = call(plan()).execute()) { assertEquals("synthetic", response.body().string()); }
            assertEquals(0, server.takeRequest(1, TimeUnit.SECONDS).getSequenceNumber());
        }
    }

    @Test public void cancellingAWaitingCallClosesItWithoutAReplay() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        Call call = call(plan());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> ended = worker.submit(() -> {
                try (Response ignored = call.execute()) { return false; } catch (IOException expected) { return true; }
            });
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS));
            call.cancel();
            assertTrue(ended.get(2, TimeUnit.SECONDS)); assertTrue(call.isCanceled());
            assertEquals(1, server.getRequestCount());
        } finally { call.cancel(); worker.shutdownNow(); }
    }

    @Test public void boundedAuthorizationReadsStillRejectOversizedReplies() throws Exception {
        byte[] body = new byte[1024 * 1024 + 1];
        assertThrows(Exception.class, () -> ChatGptHttpTransport.boundedText(new ByteArrayInputStream(body)));
        assertEquals("你好", ChatGptHttpTransport.boundedText(new ByteArrayInputStream("你好".getBytes(StandardCharsets.UTF_8))));
    }
}
