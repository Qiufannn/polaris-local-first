package com.alyssa.polaris;

import static org.junit.Assert.*;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLHandshakeException;
import org.json.JSONException;
import org.junit.Test;

public class ChatGptConnectionFailureTest {
    private static final String PRIVATE = "Bearer private-test-value https://private.invalid account@example.invalid";
    private String message(Exception error, ChatGptConnectionFailure.Stage stage) {
        String message = ChatGptConnectionFailure.message(error, stage);
        assertFalse(message.contains("private-test"));
        assertFalse(message.contains("private.invalid"));
        assertFalse(message.contains("account@example.invalid"));
        assertTrue(message.contains("本地聊天仍保留"));
        return message;
    }

    @Test public void distinguishesDnsConnectAndTlsWithoutReturningExceptionDetails() {
        assertTrue(message(new UnknownHostException(PRIVATE), ChatGptConnectionFailure.Stage.CONNECT).contains("CGP-DNS-CONNECT"));
        assertTrue(message(new ConnectException(PRIVATE), ChatGptConnectionFailure.Stage.CONNECT).contains("CGP-CONNECT-CONNECT"));
        assertTrue(message(new SSLHandshakeException(PRIVATE), ChatGptConnectionFailure.Stage.CONNECT).contains("CGP-TLS-CONNECT"));
    }

    @Test public void certificateCauseTakesPrecedenceOverGenericSslAndIoWrappers() {
        SSLHandshakeException tls = new SSLHandshakeException(PRIVATE);
        tls.initCause(new CertificateException(PRIVATE));
        String text = message(new IOException(PRIVATE, tls), ChatGptConnectionFailure.Stage.CONNECT);
        assertTrue(text.contains("CGP-CERT-CONNECT"));
        assertTrue(text.contains("请勿关闭证书校验"));
    }

    @Test public void timeoutExplainsWhetherConnectingOrAlreadyWaitingForTheService() {
        for (ChatGptConnectionFailure.Stage stage : ChatGptConnectionFailure.Stage.values()) {
            String text = message(new SocketTimeoutException(PRIVATE), stage);
            assertTrue(text.contains("CGP-TIMEOUT-" + stage.code));
            if (stage == ChatGptConnectionFailure.Stage.WAIT || stage == ChatGptConnectionFailure.Stage.STREAM
                || stage == ChatGptConnectionFailure.Stage.RESPONSE) assertTrue(text.contains("等待服务回复超时"));
        }
    }

    @Test public void brokenSocketAndEofDoNotClaimCompletionOrAdviseSigningOut() {
        for (Exception error : new Exception[] {new SocketException(PRIVATE), new EOFException(PRIVATE)}) {
            String text = message(error, ChatGptConnectionFailure.Stage.STREAM);
            assertTrue(text.contains("CGP-INTERRUPTED-STREAM"));
            assertTrue(text.contains("尚不能确认"));
            assertFalse(text.contains("重新登录"));
        }
    }

    @Test public void wrappedNetworkErrorsKeepTheirCategoryDuringAuthorization() {
        String text = message(new Exception(PRIVATE, new UnknownHostException(PRIVATE)), ChatGptConnectionFailure.Stage.AUTH);
        assertTrue(text.contains("CGP-DNS-AUTH"));
        assertFalse(text.contains("授权已过期"));
    }

    @Test public void jsonAndUnknownFailuresDoNotMasqueradeAsNetworkOutages() {
        assertTrue(message(new JSONException(PRIVATE), ChatGptConnectionFailure.Stage.PREPARE).contains("CGP-DATA-PREPARE"));
        assertTrue(message(new IllegalStateException(PRIVATE), ChatGptConnectionFailure.Stage.AUTH).contains("CGP-UNKNOWN-AUTH"));
        assertTrue(message(new IOException(PRIVATE), ChatGptConnectionFailure.Stage.SEND).contains("CGP-IO-SEND"));
    }

    @Test public void retainsExistingControlledMessagesForQuotaAuthAndResponseFormatFailures() {
        for (String text : new String[] {"ChatGPT 订阅额度不足，请稍后重试。", "ChatGPT 授权已过期或撤销，请重新登录；本地聊天仍保留。",
            "订阅响应格式异常（HTTP 200；空响应），未收到有效回复。"}) {
            assertEquals(text, ChatGptConnectionFailure.message(new Exception(text), ChatGptConnectionFailure.Stage.RESPONSE));
        }
    }

    @Test public void cyclicExceptionCausesAreBounded() {
        Exception a = new Exception(PRIVATE), b = new Exception(PRIVATE);
        a.initCause(b); b.initCause(a);
        assertTrue(message(a, ChatGptConnectionFailure.Stage.GENERAL).contains("CGP-UNKNOWN-GENERAL"));
    }
}
