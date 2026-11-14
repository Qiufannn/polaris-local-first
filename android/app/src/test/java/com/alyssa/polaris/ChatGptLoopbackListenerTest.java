package com.alyssa.polaris;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class ChatGptLoopbackListenerTest {
    private String get(int port, String host, String query) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET /auth/callback?" + query + " HTTP/1.1\r\nHost: " + host + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024]; int n;
            while ((n = socket.getInputStream().read(buffer)) != -1) output.write(buffer, 0, n);
            return output.toString("UTF-8");
        }
    }
    @Test public void rejectsMismatchedCallbacksWithoutConsumingTheAttemptThenClosesOnSuccess() throws Exception {
        ChatGptLoopbackListener listener = new ChatGptLoopbackListener();
        URL redirect = new URL(listener.redirect());
        assertEquals("127.0.0.1", redirect.getHost()); assertEquals("/auth/callback", redirect.getPath());
        int port = redirect.getPort();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Map<String, String>> result = pool.submit(() -> listener.await("expected-state", "issued-client"));
            assertTrue(get(port, "127.0.0.1:" + port, "state=wrong&code=synthetic-code").startsWith("HTTP/1.1 400"));
            assertFalse(result.isDone());
            assertTrue(get(port, "other.invalid", "state=expected-state&code=synthetic-code").startsWith("HTTP/1.1 400"));
            String response = get(port, "127.0.0.1:" + port, "state=expected-state&code=synthetic-code");
            assertTrue(response.startsWith("HTTP/1.1 200"));
            assertFalse(response.contains("synthetic-code")); assertFalse(response.contains("expected-state"));
            assertEquals("issued-client", result.get(5, TimeUnit.SECONDS).get("client_id"));
            assertThrows(java.io.IOException.class, () -> { try (Socket ignored = new Socket("127.0.0.1", port)) {} });
        } finally { listener.close(); pool.shutdownNow(); }
    }
    @Test public void cancellationClosesTheListeningSocket() throws Exception {
        ChatGptLoopbackListener listener = new ChatGptLoopbackListener();
        int port = new URL(listener.redirect()).getPort();
        listener.close();
        assertThrows(Exception.class, () -> listener.await("state", "issued-client"));
        assertThrows(java.io.IOException.class, () -> { try (Socket ignored = new Socket("127.0.0.1", port)) {} });
    }
}
