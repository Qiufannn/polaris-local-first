package com.alyssa.polaris;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONObject;

/** Reads only a bounded prefix when the server's media type does not identify SSE. */
final class ChatGptPlanResponse {
    static final int PREFIX_LIMIT = 4096;
    private static final String[] FIELDS = {":", "data:", "event:", "id:", "retry:"};

    static boolean hasEventStreamType(String contentType) {
        return contentType != null && contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT).equals("text/event-stream");
    }

    static InputStream stream(InputStream raw, String contentType, int status) throws Exception {
        if (hasEventStreamType(contentType)) return raw;
        // Read one byte at a time from this buffer, so a short first SSE field need not
        // wait for a full probe buffer or the end of an ongoing response.
        PushbackInputStream input = new PushbackInputStream(new BufferedInputStream(raw, PREFIX_LIMIT), PREFIX_LIMIT);
        ByteArrayOutputStream prefix = new ByteArrayOutputStream();
        while (prefix.size() < PREFIX_LIMIT) {
            int next = input.read();
            if (next == -1) break;
            prefix.write(next);
            byte[] bytes = prefix.toByteArray();
            // A UTF-8 BOM may itself arrive in separate network reads.
            if (bytes.length < 3 && (bytes[0] & 0xff) == 0xef) continue;
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.startsWith("\ufeff")) text = text.substring(1);
            text = text.trim();
            if (text.isEmpty()) continue;
            for (String field : FIELDS) {
                if (text.startsWith(field)) {
                    input.unread(bytes);
                    return input;
                }
            }
            if (text.startsWith("{") || text.startsWith("[")) {
                throw jsonFailure(input, prefix, status);
            }
            boolean possibleField = false;
            for (String field : FIELDS) if (field.startsWith(text)) possibleField = true;
            if (!possibleField) throw failure(status, text.startsWith("<") ? "网页响应" : "非流式文本或二进制响应");
        }
        throw failure(status, prefix.size() == 0 ? "空响应" : "未识别的流格式");
    }

    private static Exception jsonFailure(InputStream input, ByteArrayOutputStream prefix, int status) throws Exception {
        // Only inspect structured error codes. Never return server messages, request
        // bodies, account data, response text, URLs or arbitrary header values to JS.
        while (prefix.size() <= PREFIX_LIMIT) {
            String text = new String(prefix.toByteArray(), StandardCharsets.UTF_8).trim();
            if (text.startsWith("\ufeff")) text = text.substring(1).trim();
            if (text.endsWith("}")) {
                try {
                    JSONObject value = new JSONObject(text);
                    JSONObject error = value.optJSONObject("error");
                    if (error == null) error = value.optJSONObject("response") == null ? null : value.getJSONObject("response").optJSONObject("error");
                    if (error != null) return knownError(error.optString("code"), status);
                    if ("error".equals(value.optString("type"))) return knownError(value.optString("code"), status);
                    return failure(status, "JSON 非流式响应");
                } catch (org.json.JSONException ignored) { /* The object may still be incomplete. */ }
            }
            if (prefix.size() == PREFIX_LIMIT) break;
            int next = input.read();
            if (next == -1) break;
            prefix.write(next);
        }
        return failure(status, "JSON 非流式响应");
    }

    private static Exception knownError(String code, int status) {
        switch (code) {
            case "subscription_sharing_usage_limit_exceeded":
                return new Exception("ChatGPT 订阅额度已用尽，请等待额度恢复；本地聊天仍保留。");
            case "subscription_sharing_usage_unavailable":
                return new Exception("ChatGPT 当前账号或工作区暂不能使用订阅额度，请检查订阅资格。");
            case "invalid_token":
            case "insufficient_scope":
                return new Exception("ChatGPT 授权已失效或权限不足，请重新登录。");
            default:
                return failure(status, "JSON 错误响应");
        }
    }

    private static Exception failure(int status, String kind) {
        return new Exception("订阅响应格式异常（HTTP " + status + "；" + kind + "），未收到有效回复。请将这条提示反馈给开发者。");
    }
}
