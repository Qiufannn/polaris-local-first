package com.alyssa.polaris;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** One attempt, one loopback port. No callback values ever appear in HTML, logs or redirects. */
final class ChatGptLoopbackListener implements AutoCloseable {
    private final ServerSocket server = new ServerSocket();
    private volatile boolean closed;

    ChatGptLoopbackListener() throws Exception { this(0); }
    ChatGptLoopbackListener(int preferredPort) throws Exception {
        if (preferredPort != 0 && (preferredPort < 1024 || preferredPort > 65535)) throw new Exception("回调端口不正确。");
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), preferredPort), 4);
        server.setSoTimeout(1000);
    }

    String redirect() { return "http://127.0.0.1:" + server.getLocalPort() + "/auth/callback"; }

    private static String readLine(InputStream input) throws Exception {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) != -1 && next != '\n') {
            if (line.size() >= 8192) throw new Exception("回调请求过大。");
            if (next != '\r') line.write(next);
        }
        return line.toString("US-ASCII");
    }

    private static void reply(Socket socket, int status, String text) throws Exception {
        byte[] body = ("<!doctype html><meta charset=utf-8><title>Polaris</title><p>" + text + "</p>").getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Bad Request")
            + "\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\n"
            + "Content-Security-Policy: default-src 'none'\r\nConnection: close\r\nContent-Length: " + body.length + "\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(headers.getBytes(StandardCharsets.US_ASCII)); out.write(body); out.flush();
    }

    Map<String, String> await(String state, String client) throws Exception {
        long deadline = System.nanoTime() + 180000000000L;
        while (!closed && System.nanoTime() < deadline) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(3000);
                try {
                    InputStream input = socket.getInputStream();
                    String[] request = readLine(input).split(" ");
                    int total = 0;
                    String host = "";
                    String line;
                    while (!(line = readLine(input)).isEmpty()) {
                        total += line.length();
                        if (total > 16384) throw new Exception("回调请求过大。");
                        if (line.toLowerCase(java.util.Locale.ROOT).startsWith("host:")) host = line.substring(5).trim();
                    }
                    if (request.length != 3 || !"GET".equals(request[0])
                        || !("127.0.0.1:" + server.getLocalPort()).equals(host)) throw new Exception("回调来源不正确。");
                    Map<String, String> values = ChatGptOAuth.callback(request[1], state, client);
                    reply(socket, 200, "已收到授权回调，请返回 Polaris 查看登录结果。此页面不包含账号凭证。");
                    close();
                    return values;
                } catch (Exception invalid) {
                    try { reply(socket, 400, "此回调未通过校验，请回到 Polaris 继续或重新登录。"); } catch (Exception ignored) {}
                }
            } catch (SocketTimeoutException ignored) { /* Keep waiting within this attempt's deadline. */ }
        }
        close();
        throw new Exception("登录已取消或超时，请重新发起。");
    }

    @Override public void close() {
        closed = true;
        try { server.close(); } catch (Exception ignored) {}
    }
}
