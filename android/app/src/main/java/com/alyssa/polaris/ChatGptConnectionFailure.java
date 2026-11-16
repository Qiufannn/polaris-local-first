package com.alyssa.polaris;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.json.JSONException;

/** Only fixed categories and stages leave native code, never raw network exceptions. */
final class ChatGptConnectionFailure {
    enum Stage {
        GENERAL("GENERAL", "连接"), PREPARE("PREPARE", "准备请求"),
        AUTH("AUTH", "更新授权"), CONNECT("CONNECT", "建立连接"),
        SEND("SEND", "发送请求"), WAIT("WAIT", "等待回复"),
        RESPONSE("RESPONSE", "识别回复"), STREAM("STREAM", "接收回复");
        final String code;
        final String label;
        Stage(String code, String label) { this.code = code; this.label = label; }
    }

    static String message(Exception error, Stage stage) {
        boolean certificate = false, tls = false, dns = false, timeout = false;
        boolean refused = false, interrupted = false, io = false, json = false;
        // Bound traversal even if a library supplies a cyclic cause chain.
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            certificate |= cause instanceof CertificateException || cause instanceof SSLPeerUnverifiedException;
            tls |= cause instanceof SSLException;
            dns |= cause instanceof UnknownHostException;
            timeout |= cause instanceof SocketTimeoutException;
            refused |= cause instanceof ConnectException;
            interrupted |= cause instanceof EOFException || cause instanceof SocketException;
            io |= cause instanceof IOException;
            json |= cause instanceof JSONException;
        }
        String kind, detail;
        if (certificate) {
            kind = "CERT"; detail = "安全证书校验未通过，请检查手机日期和网络代理；请勿关闭证书校验。";
        } else if (tls) {
            kind = "TLS"; detail = "安全连接未能完成，可检查北极星的代理设置或更换网络节点。";
        } else if (dns) {
            kind = "DNS"; detail = "服务域名解析失败，可检查北极星是否经过可用的网络代理。";
        } else if (timeout) {
            kind = "TIMEOUT"; detail = stage == Stage.WAIT || stage == Stage.STREAM || stage == Stage.RESPONSE
                ? "等待服务回复超时，请稍后重试。" : "网络连接超时，可检查北极星的代理设置或更换网络节点。";
        } else if (refused) {
            kind = "CONNECT"; detail = "未能连上服务，可检查北极星的代理设置或更换网络节点。";
        } else if (interrupted) {
            kind = "INTERRUPTED"; detail = "网络连接中断，尚不能确认本轮回复完成，请稍后重试。";
        } else if (io) {
            kind = "IO"; detail = "网络或本机读写失败，请检查连接后重试。";
        } else if (json) {
            kind = "DATA"; detail = stage == Stage.PREPARE
                ? "本机请求数据无法解析，请把这条提示反馈给开发者。"
                : "返回数据无法解析，请把这条提示反馈给开发者。";
        } else {
            String text = error.getMessage();
            if (text != null && text.length() < 180
                && text.matches("^(登录|授权|身份|安全|返回|请选择|尚未|暂时|订阅|无法|注册|ChatGPT).*")) return text;
            kind = "UNKNOWN"; detail = "连接未完成，请把这条提示反馈给开发者。";
        }
        return "ChatGPT 在" + stage.label + "时失败：" + detail
            + "本地聊天仍保留。（CGP-" + kind + "-" + stage.code + "）";
    }
}
