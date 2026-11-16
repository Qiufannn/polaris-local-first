package com.alyssa.polaris;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The actual private website in an isolated browser, without a Capacitor bridge. */
public class OriginalHomeActivity extends Activity {
    private WebView browser;
    private String origin;
    private TextView address;
    private ValueCallback<Uri[]> fileCallback;
    private OriginalHomeDownload download;
    private byte[] exportBytes;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try { origin = OriginalHomePolicy.origin(getIntent().getStringExtra("url")); } catch (Exception e) { finish(); return; }
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setFitsSystemWindows(true);
        address = new TextView(this); address.setPadding(16, 12, 16, 12); address.setVisibility(android.view.View.GONE); layout.addView(address);
        browser = new WebView(this); layout.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(layout);
        WebSettings settings = browser.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true); settings.setAllowFileAccess(false); settings.setAllowContentAccess(true); settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); settings.setSupportMultipleWindows(true);
        CookieManager.getInstance().setAcceptCookie(true);
        browser.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return navigate(request.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return navigate(Uri.parse(url)); }
            @Override public void onPageFinished(WebView view, String url) {
                boolean own = OriginalHomePolicy.trustedPage(url, origin);
                address.setVisibility(own ? android.view.View.GONE : android.view.View.VISIBLE);
                address.setText(Uri.parse(url).getHost() == null ? "正在打开登录页面" : Uri.parse(url).getHost());
                if (own) view.evaluateJavascript(OriginalHomePolicy.chatHook() + OriginalHomePolicy.downloadHook(), null);
                CookieManager.getInstance().flush();
            }
        });
        browser.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (!OriginalHomePolicy.trustedPage(browser.getUrl(), origin)) return false;
                if (fileCallback != null) fileCallback.onReceiveValue(null); fileCallback = callback;
                try { startActivityForResult(params.createIntent(), 74); return true; } catch (Exception e) { fileCallback = null; return false; }
            }
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, android.os.Message result) {
                if (!gesture) return false;
                WebView popup = new WebView(OriginalHomeActivity.this);
                popup.setWebViewClient(new WebViewClient() { @Override public boolean shouldOverrideUrlLoading(WebView child, String url) { if ("https".equals(Uri.parse(url).getScheme())) browser.loadUrl(url); child.destroy(); return true; } });
                ((WebView.WebViewTransport) result.obj).setWebView(popup); result.sendToTarget(); return true;
            }
        });
        browser.setDownloadListener((url, agent, disposition, type, length) -> downloadFile(url));
        browser.loadUrl(OriginalHomePolicy.initialUrl(origin, getIntent().getBooleanExtra("signIn", false)));
    }
    private void downloadFile(String url) {
            if (download != null || exportBytes != null) { android.widget.Toast.makeText(this,"请先完成当前导出",android.widget.Toast.LENGTH_SHORT).show(); return; }
            download = new OriginalHomeDownload(browser, origin, url, (name,mime,bytes,error) -> {
                download = null;
                if (isFinishing()) return;
                if (error != null) { android.widget.Toast.makeText(this,error,android.widget.Toast.LENGTH_LONG).show(); return; }
                exportBytes = bytes;
                try { startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE,name),75); }
                catch(Exception e) { exportBytes=null;android.widget.Toast.makeText(this,"未打开保存位置，原数据仍保留",android.widget.Toast.LENGTH_LONG).show(); }
            });
            download.start(this);

    }
    private boolean navigate(Uri uri) {
        if (OriginalHomePolicy.blobUrl(uri.toString(), origin) && OriginalHomePolicy.trustedPage(browser.getUrl(), origin)) { downloadFile(uri.toString()); return true; }
        if (OriginalHomePolicy.CHAT_LINK.equals(uri.toString()) && OriginalHomePolicy.trustedPage(browser.getUrl(), origin)) { setResult(RESULT_OK, new Intent().putExtra("action", "chat")); finish(); return true; }
        return !"https".equals(uri.getScheme());
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 75 && exportBytes != null) {
            byte[] bytes = exportBytes; exportBytes = null;
            if (result == RESULT_OK && data != null && data.getData() != null) {
                Uri target = data.getData(); android.content.ContentResolver resolver = getContentResolver();
                new Thread(() -> {
                    String message;
                    try (java.io.OutputStream output = resolver.openOutputStream(target,"w")) { if (output == null) throw new java.io.IOException(); output.write(bytes); output.flush(); message="文件已保存"; }
                    catch(Exception e) { message="文件未保存成功，请保留原数据并重新导出"; }
                    final String notice=message; runOnUiThread(() -> { if(!isFinishing()) android.widget.Toast.makeText(this,notice,android.widget.Toast.LENGTH_LONG).show(); });
                },"home-file-export").start();
            }
        }
        if (request == 74 && fileCallback != null) { fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data)); fileCallback = null; }
    }
    @Override public void onBackPressed() { if (browser != null && browser.canGoBack()) browser.goBack(); else { setResult(RESULT_OK, new Intent().putExtra("action", "closed")); finish(); } }
    @Override protected void onDestroy() { if (download != null) download.cancel(); exportBytes=null; if (fileCallback != null) fileCallback.onReceiveValue(null); if (browser != null) { browser.stopLoading(); browser.destroy(); } super.onDestroy(); }
}
