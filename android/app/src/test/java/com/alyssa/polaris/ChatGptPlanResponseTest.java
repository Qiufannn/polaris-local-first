package com.alyssa.polaris;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class ChatGptPlanResponseTest {
    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private InputStream input(String value) { return new ByteArrayInputStream(bytes(value)); }
    private String reject(String body) {
        return assertThrows(Exception.class, () -> ChatGptPlanResponse.stream(input(body), "application/json", 200)).getMessage();
    }

    @Test public void recognizesStandardMediaTypeWithoutConsumingTheStream() throws Exception {
        InputStream original = input("data: test\n\n");
        assertTrue(ChatGptPlanResponse.hasEventStreamType("TEXT/EVENT-STREAM; charset=UTF-8"));
        assertFalse(ChatGptPlanResponse.hasEventStreamType("application/x-text/event-stream"));
        assertSame(original, ChatGptPlanResponse.stream(original, "Text/Event-Stream; charset=utf-8", 200));
        assertEquals('d', original.read());
    }

    @Test public void acceptsRealSseWithWrongOrMissingHeadersAndReplaysEveryByte() throws Exception {
        String body = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"你好\"}\n\n"
            + "data: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\n";
        for (String header : new String[] {null, "text/plain", "application/json", "application/octet-stream"}) {
            assertArrayEquals(bytes(body), ChatGptPlanResponse.stream(input(body), header, 200).readAllBytes());
        }
    }

    @Test public void handlesBomCommentsAndOtherFirstSseFieldsWithoutDroppingThePrefix() throws Exception {
        for (String prefix : new String[] {"\ufeff\r\n: keepalive\r\n", "event: response.created\n", "id: 1\n", "retry: 1000\n"}) {
            String body = prefix + "data: {\"type\":\"response.completed\"}\n\n";
            assertArrayEquals(bytes(body), ChatGptPlanResponse.stream(input(body), null, 200).readAllBytes());
        }
    }

    @Test public void returnsAsSoonAsTheFirstSseFieldIsRecognized() throws Exception {
        InputStream shortFirstChunk = new InputStream() {
            int at;
            final byte[] start = bytes("data:");
            public int read() {
                if (at == start.length) throw new AssertionError("Do not wait for further chunks or EOF before exposing the stream.");
                return start[at++];
            }
            public int read(byte[] target, int offset, int length) {
                int n = Math.min(length, start.length - at);
                if (n == 0) throw new AssertionError("Do not read beyond the first network chunk.");
                System.arraycopy(start, at, target, offset, n); at += n; return n;
            }
        };
        InputStream prepared = ChatGptPlanResponse.stream(shortFirstChunk, "text/plain", 200);
        for (byte expected : bytes("data:")) assertEquals(expected, prepared.read());
    }

    @Test public void mapsKnownJsonErrorsEvenWithHttpSuccessWithoutReflectingServerText() {
        assertTrue(reject("{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\",\"message\":\"private-test-value\"}}").contains("额度已用尽"));
        assertTrue(reject("{\"error\":{\"code\":\"subscription_sharing_usage_unavailable\"}}").contains("暂不能使用"));
        assertTrue(reject("{\"error\":{\"code\":\"invalid_token\"}}").contains("重新登录"));
        assertTrue(reject("{\"response\":{\"error\":{\"code\":\"insufficient_scope\"}}}").contains("权限不足"));
        assertTrue(reject("\ufeff{\"type\":\"error\",\"code\":\"subscription_sharing_usage_unavailable\"}").contains("暂不能使用"));
        String message = reject("{\"error\":{\"code\":\"private-test-code\",\"message\":\"private-test-value\"}}");
        assertTrue(message.contains("HTTP 200；JSON 错误响应"));
        assertFalse(message.contains("private-test"));
    }

    @Test public void neverAcceptsJsonCompletionOrHtmlAsASuccessfulReply() {
        assertTrue(reject("{\"status\":\"completed\",\"output\":[{\"text\":\"private-test-reply\"}]}").contains("JSON 非流式响应"));
        String html = reject("<!doctype html><p>private-test-value</p>");
        assertTrue(html.contains("网页响应"));
        assertFalse(html.contains("private-test"));
        assertTrue(reject("").contains("空响应"));
        assertTrue(reject("data").contains("未识别的流格式"));
    }

    @Test public void boundsMalformedAndOversizedPrefixInspection() {
        for (String start : new String[] {" ", "{"}) {
            ByteArrayInputStream source = new ByteArrayInputStream(bytes(start.repeat(8192)));
            assertThrows(Exception.class, () -> ChatGptPlanResponse.stream(source, null, 200));
            assertEquals(8192 - ChatGptPlanResponse.PREFIX_LIMIT, source.available());
        }
    }
}
