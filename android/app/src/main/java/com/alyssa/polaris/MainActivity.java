package com.alyssa.polaris;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(SystemFilePlugin.class);
        registerPlugin(LocalDataSqlitePlugin.class);
        registerPlugin(NativeProviderHttpPlugin.class);
        registerPlugin(ChatGptAuthPlugin.class);
        registerPlugin(ChatGptPlanHttpPlugin.class);
        registerPlugin(McpSiteAuthPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
