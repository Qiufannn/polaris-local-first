package com.alyssa.polaris;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.WebView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.UUID;
import org.json.JSONObject;
import org.json.JSONTokener;

final class OriginalHomeDownload {
    interface Callback { void complete(String name, String mime, byte[] bytes, String error); }
    private final WebView browser;
    private final String origin, url, key = "__polaris_export_" + UUID.randomUUID().toString().replace("-", "");
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Callback callback;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private String name, mime;
    private int length, size, offset;
    private boolean done;
    OriginalHomeDownload(WebView browser, String origin, String url, Callback callback) { this.browser=browser;this.origin=origin;this.url=url;this.callback=callback; }
    void start(Activity activity) {
        if(!OriginalHomePolicy.blobUrl(url,origin)||!OriginalHomePolicy.trustedPage(browser.getUrl(),origin)){finish("请从原小屋点击导出链接。");return;}
        try(InputStream input=activity.getAssets().open("original-home-download.js");ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1)out.write(buffer,0,n);
            handler.postDelayed(()->finish("导出读取超时，原数据仍保留，请分批导出。"),30000);
            browser.evaluateJavascript(out.toString("UTF-8")+"\n__polarisHomeDownload("+JSONObject.quote(key)+","+JSONObject.quote(url)+","+JSONObject.quote(origin)+");", ignored->poll());
        }catch(Exception e){finish("导出功能未启动，请检查更新包。");}
    }
    private Object parse(String value)throws Exception{return new JSONTokener(value==null?"null":value).nextValue();}
    private void poll(){
        if(done)return;
        browser.evaluateJavascript("(function(){var r=window["+JSONObject.quote(key)+"];return r&&r.ready?JSON.stringify({error:r.error,name:r.name,mime:r.mime,size:r.size,length:r.length}):'null';})()",encoded->{
            if(done)return;
            try{Object value=parse(encoded);if(value instanceof String)value=parse((String)value);if(!(value instanceof JSONObject)){handler.postDelayed(this::poll,100);return;}JSONObject result=(JSONObject)value;
                if(!result.optString("error").isEmpty()){finish(result.getString("error"));return;}
                mime=result.getString("mime");name=OriginalHomePolicy.downloadName(result.optString("name"),mime);size=result.getInt("size");length=result.getInt("length");
                if(size<0||size>16*1024*1024||length<0||length>24*1024*1024)throw new Exception();chunk();
            }catch(Exception e){finish("导出文件校验未通过，未创建文件。");}
        });
    }
    private void chunk(){
        if(done)return;
        if(!OriginalHomePolicy.trustedPage(browser.getUrl(),origin)){finish("页面已切换，导出已停止。");return;}
        if(offset>=length){if(bytes.size()!=size){finish("导出文件长度不完整，未创建文件。");return;}finish(null);return;}
        int end=Math.min(length,offset+262144);
        browser.evaluateJavascript("window["+JSONObject.quote(key)+"].data.slice("+offset+","+end+")",encoded->{
            if(done)return;
            try{Object part=parse(encoded);if(!(part instanceof String))throw new Exception();byte[] data=Base64.decode((String)part,Base64.NO_WRAP);if(bytes.size()+data.length>16*1024*1024)throw new Exception();bytes.write(data);offset=end;chunk();}catch(Exception e){finish("导出内容未完整读取，未创建文件。");}
        });
    }
    void cancel(){finish("导出已取消，原数据仍保留。");}
    private void finish(String error){if(done)return;done=true;handler.removeCallbacksAndMessages(null);try{browser.evaluateJavascript("delete window["+JSONObject.quote(key)+"]",null);}catch(Exception ignored){}callback.complete(name,mime,error==null?bytes.toByteArray():null,error);}
}
