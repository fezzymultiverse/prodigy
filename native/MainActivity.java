package com.fezzy.prodigy;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ProdigyBridgePlugin.class);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onPause() {
        super.onPause();
        // keep web timers + audio events alive with the screen off
        try { getBridge().getWebView().resumeTimers(); } catch (Exception ignored) {}
        // V74: write cookies to disk so social logins survive the app being closed
        try { android.webkit.CookieManager.getInstance().flush(); } catch (Exception ignored) {}
    }

    @Override
    public void onStop() {
        super.onStop();
        // V77: cookies hit the disk whenever the app leaves the screen
        try { android.webkit.CookieManager.getInstance().flush(); } catch (Exception ignored) {}
    }

    // V30: file upload from the in-app browser
    @Override
    public void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        if (WebSheet.onResult(requestCode, resultCode, data)) return;
        super.onActivityResult(requestCode, resultCode, data);
    }
}
