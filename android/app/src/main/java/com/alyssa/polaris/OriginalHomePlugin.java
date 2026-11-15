package com.alyssa.polaris;

import android.content.Intent;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

@CapacitorPlugin(name = "OriginalHome")
public class OriginalHomePlugin extends Plugin {
    private final List<OriginalHomeBrowserRequest> requests = new ArrayList<>();
    private boolean opening;
    private String approved() { return getContext().getSharedPreferences("polaris_original_home", 0).getString("origin", ""); }
    @PluginMethod public synchronized void open(PluginCall call) {
        if (opening) { call.reject("原小屋正在打开，请先返回当前页面。"); return; }
        opening = true;
        try {
            String origin = OriginalHomePolicy.origin(call.getString("url"));
            getContext().getSharedPreferences("polaris_original_home", 0).edit().putString("origin", origin).apply();
            startActivityForResult(call, new Intent(getContext(), OriginalHomeActivity.class).putExtra("url", origin).putExtra("signIn", call.getBoolean("signIn", false)), "returned");
        } catch (Exception error) { opening = false; call.reject("请填写原小屋的 HTTPS 首页地址。"); }
    }
    @ActivityCallback private synchronized void returned(PluginCall call, ActivityResult result) {
        opening = false;
        if (call != null) call.resolve(new JSObject().put("action", result.getData() == null ? "closed" : result.getData().getStringExtra("action")));
    }
    @PluginMethod public void request(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            try {
                String origin = OriginalHomePolicy.origin(OriginalHomePolicy.endpoint(call.getString("url")));
                if (!origin.equals(approved())) { call.reject("请先在 App 中打开这个原小屋。"); return; }
                if ("DELETE".equals(call.getString("method"))) { call.resolve(new JSObject().put("status", 204).put("data", "")); return; }
                if (!"POST".equals(call.getString("method", "POST"))) { call.reject("原小屋工具仅支持 POST。"); return; }
                JSONObject rpc = OriginalHomePolicy.rpc(call.getString("body"));
                if (requests.size() >= 3) { call.reject("原小屋正在处理请求，请等待当前操作完成。"); return; }
                final OriginalHomeBrowserRequest[] holder = new OriginalHomeBrowserRequest[1];
                holder[0] = new OriginalHomeBrowserRequest(getActivity(), origin, rpc, (status, data, error) -> {
                    requests.remove(holder[0]);
                    if (!origin.equals(approved())) call.reject("小屋地址已改变，请重新核对连接。");
                    else if (error != null) call.reject(error);
                    else call.resolve(new JSObject().put("status", status).put("data", data));
                });
                requests.add(holder[0]); try { holder[0].start(); } catch (Exception e) { holder[0].cancel(); }
            } catch (Exception e) { call.reject("原小屋连接参数不正确，请核对原网址和更新包。"); }
        });
    }
    @Override protected void handleOnDestroy() {
        getActivity().runOnUiThread(() -> { for (OriginalHomeBrowserRequest request : new ArrayList<>(requests)) request.cancel(); });
        super.handleOnDestroy();
    }
}
