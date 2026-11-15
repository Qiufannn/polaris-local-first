package com.alyssa.polaris;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;
import org.json.JSONObject;
import org.json.JSONTokener;

/** A normal authenticated browser request, without remote-page native interfaces. */
final class OriginalHomeBrowserRequest {
    interface Callback { void complete(int status, String data, String error); }
    private final Activity activity;
    private final String origin;
    private final JSONObject rpc;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String key = "__polaris_reply_" + UUID.randomUUID().toString().replace("-", "");
    private WebView browser;
    private boolean done, started;
    private String cookieSnapshot;
    OriginalHomeBrowserRequest(Activity activity, String origin, JSONObject rpc, Callback callback) {
        this.activity = activity; this.origin = origin; this.rpc = rpc; this.callback = callback;
    }
    void start() {
        browser = new WebView(activity);
        WebSettings settings = browser.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false); settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        browser.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return navigation(request.getUrl().toString()); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return navigation(url); }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse error) {
                if (request.isForMainFrame()) finish(0, "", "原小屋浏览器连接被拒绝（HTTP " + error.getStatusCode() + "）。请核对网页登录与原站后台；不要反复提交写入。");
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (done || started) return;
                if (!OriginalHomePolicy.browserPage(url, origin)) { finish(0, "", "原小屋需要网页登录，请使用“登录原小屋”后再测试。"); return; }
                started = true;
                cookieSnapshot = CookieManager.getInstance().getCookie(origin);
                try {
                    String source;
                    try (InputStream input = activity.getAssets().open("original-home-request.js"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[4096]; int n; while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n); source = out.toString("UTF-8");
                    }
                    view.evaluateJavascript(source + "\n__polarisHomeRequest(" + JSONObject.quote(key) + "," + JSONObject.quote(rpc.toString()) + "," + JSONObject.quote(origin) + ");", ignored -> poll());
                } catch (Exception e) { finish(0, "", "原小屋浏览器连接未启动，请检查更新包。"); }
            }
        });
        handler.postDelayed(() -> finish(0, "", "原小屋浏览器请求超时。写入结果不明时先读回核对，不要重复提交。"), 28000);
        browser.loadUrl(origin + "/api/home/bridge");
    }
    private boolean navigation(String url) {
        if (OriginalHomePolicy.browserPage(url, origin)) return false;
        finish(0, "", "原小屋需要网页登录，请使用“登录原小屋”后再测试。"); return true;
    }
    private void poll() {
        if (done) return;
        browser.evaluateJavascript("JSON.stringify(window[" + JSONObject.quote(key) + "] || null)", encoded -> {
            if (done) return;
            try {
                Object value = new JSONTokener(encoded == null ? "null" : encoded).nextValue();
                if (value instanceof String) value = new JSONTokener((String) value).nextValue();
                if (value instanceof JSONObject && ((JSONObject) value).optBoolean("done")) {
                    JSONObject result = (JSONObject) value;
                    if (!Objects.equals(cookieSnapshot, CookieManager.getInstance().getCookie(origin))) { finish(0, "", "小屋登录状态已改变，请重新核对连接。"); return; }
                    String error = result.optString("error");
                    if (!error.isEmpty()) finish(0, "", error);
                    else finish(result.getInt("status"), OriginalHomePolicy.filtered(rpc, result.getString("data")), null);
                    return;
                }
            } catch (Exception e) { finish(0, "", "原小屋浏览器响应未确认，写入后请先读回核对。"); return; }
            handler.postDelayed(this::poll, 100);
        });
    }
    void cancel() { finish(0, "", "原小屋连接已关闭，写入结果不明时请先读回核对。"); }
    private void finish(int status, String data, String error) {
        if (done) return; done = true; handler.removeCallbacksAndMessages(null);
        if (browser != null) { browser.stopLoading(); browser.destroy(); browser = null; }
        callback.complete(status, data, error);
    }
}
