package com.alyssa.polaris;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.ConnectionSpec;
import okhttp3.EventListener;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;

/** Dedicated OpenAI transport; credentials and requests never pass through a WebView. */
final class ChatGptHttpTransport {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final MediaType FORM = MediaType.get("application/x-www-form-urlencoded; charset=utf-8");
    private static final OkHttpClient AUTH = builder(45000).build();
    private static final OkHttpClient MODEL = builder(300000).build();

    static OkHttpClient.Builder builder(int readTimeoutMs) {
        return new OkHttpClient.Builder()
            .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionSpecs(Collections.singletonList(ConnectionSpec.MODERN_TLS))
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            // No automatic retry of POSTs or redirects. Keep active streams alive;
            // freshCall owns a separate pool for each request instead of
            // using a zero-idle pool that can close H2 before its final END_STREAM.
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES));
    }

    private static HttpUrl endpoint(String endpoint) throws Exception {
        HttpUrl url = HttpUrl.parse(endpoint);
        if (url == null || !url.isHttps() || url.port() != 443 || !url.username().isEmpty()
            || !url.password().isEmpty() || url.fragment() != null
            || !("auth.openai.com".equals(url.host()) || "api.openai.com".equals(url.host()))) {
            throw new Exception("订阅连接地址不正确。");
        }
        return url;
    }

    static Request formRequest(String endpoint, String method, String body, String bearer) throws Exception {
        if (!("GET".equals(method) && body == null) && !("POST".equals(method) && body != null)) {
            throw new Exception("授权请求方式不正确。");
        }
        Request.Builder request = new Request.Builder().url(endpoint(endpoint)).header("Accept", "application/json");
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (body != null) request.post(oneShot(body.getBytes(StandardCharsets.UTF_8), FORM));
        return request.build();
    }

    static Request planRequest(byte[] body, String bearer) throws Exception {
        return new Request.Builder().url(endpoint(ChatGptOAuth.RESOURCE + "/responses"))
            .header("Authorization", "Bearer " + bearer).header("Accept", "text/event-stream")
            .post(oneShot(body, JSON)).build();
    }

    private static RequestBody oneShot(byte[] bytes, MediaType mediaType) {
        return new RequestBody() {
            @Override public MediaType contentType() { return mediaType; }
            @Override public long contentLength() { return bytes.length; }
            // Also prevent automatic 408/503 follow-ups after a server reply.
            @Override public boolean isOneShot() { return true; }
            @Override public void writeTo(BufferedSink sink) throws IOException { sink.write(bytes); }
        };
    }

    static Exchange formCall(String endpoint, String method, String body, String bearer) throws Exception {
        return freshCall(AUTH, formRequest(endpoint, method, body, bearer));
    }

    static Exchange planCall(byte[] body, String bearer, EventListener listener) throws Exception {
        return freshCall(MODEL.newBuilder().eventListener(listener).build(), planRequest(body, bearer));
    }

    static Exchange freshCall(OkHttpClient client, Request request) {
        ConnectionPool pool = new ConnectionPool(1, 5, TimeUnit.MINUTES);
        return new Exchange(client.newBuilder().connectionPool(pool).build().newCall(request), pool);
    }

    static final class Exchange implements AutoCloseable {
        final Call call;
        private final ConnectionPool pool;
        Exchange(Call call, ConnectionPool pool) { this.call = call; this.pool = pool; }
        @Override public void close() {
            call.cancel();
            pool.evictAll();
        }
    }

    static String boundedText(InputStream input) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 1024 * 1024) throw new Exception("授权服务返回的数据过大。");
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        }
    }
}
