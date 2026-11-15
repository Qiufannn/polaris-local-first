package com.alyssa.polaris;

import java.net.URLDecoder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/** Parse provider OAuth responses without ever displaying response bodies or credentials. */
final class MemorySourceOAuth {
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList(
        "device_code", "user_code", "verification_uri", "expires_in", "interval", "access_token",
        "refresh_token", "refresh_token_expires_in", "token_type", "scope", "error", "error_description", "error_uri"));

    static JSONObject github(MemorySourceHttp.Result response) throws Exception {
        String body = response.body.trim();
        if (body.startsWith("\ufeff")) body = body.substring(1).trim();
        if (body.isEmpty() || body.length() > 65536) throw new Exception("GitHub 授权响应为空或过大（HTTP " + response.status + "）。");
        try {
            if (body.startsWith("{")) return new JSONObject(body);
            // GitHub documents form-encoded OAuth responses as well as Accept: application/json.
            JSONObject data = new JSONObject();
            for (String pair : body.split("&", -1)) {
                int at = pair.indexOf('=');
                if (at <= 0) throw new IllegalArgumentException();
                String key = URLDecoder.decode(pair.substring(0, at), "UTF-8");
                if (!FIELDS.contains(key) || data.has(key)) throw new IllegalArgumentException();
                data.put(key, URLDecoder.decode(pair.substring(at + 1), "UTF-8"));
            }
            return data;
        } catch (Exception invalid) {
            throw new Exception("GitHub 授权响应格式无法识别（HTTP " + response.status + "），未保存不完整凭证。");
        }
    }

    static String category(Exception error) {
        if (error instanceof java.net.SocketTimeoutException) return "请求超时";
        if (error instanceof javax.net.ssl.SSLException) return "TLS 连接中断";
        if (error instanceof java.net.UnknownHostException) return "域名解析失败";
        if (error instanceof java.io.IOException) return "网络连接中断";
        if (error instanceof org.json.JSONException) return "响应解析失败";
        if (error instanceof java.security.GeneralSecurityException) return "本机安全存储失败";
        return "本机处理未完成";
    }

    static String safe(Exception error) {
        String message = error.getMessage();
        if (message != null && message.length() < 240 && !message.matches("(?s).*(gh[upr]_|access_token|refresh_token|device_code).*" )
            && message.matches("^(GitHub 授权|GitHub 记忆|登录|授权|身份|安全|返回|请选择|尚未|暂时|无法|注册).*")) return message;
        return "记忆来源连接未完成（" + category(error) + "），请重试；无需修改 ChatGPT 登录。";
    }
}
