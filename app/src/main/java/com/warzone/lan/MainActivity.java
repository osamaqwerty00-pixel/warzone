package com.warzone.lan;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends Activity {
    private WebView web;
    private long lastBack = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1); } catch (Exception ignored) { }
        }

        // السيرفر يشتغل فوراً داخل التطبيق (لا يحتاج أي تثبيت إضافي)
        int port = LanService.ensure(this);
        try {
            Intent s = new Intent(this, LanService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(s); else startService(s);
        } catch (Exception ignored) { }

        web = new WebView(this);
        setContentView(web);
        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setMediaPlaybackRequiresUserGesture(false);
        st.setCacheMode(WebSettings.LOAD_DEFAULT);
        st.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());

        if (port == 0) {
            web.loadData("<meta charset='utf-8'><body style='background:#111;color:#fff;font:20px sans-serif;padding:30px;text-align:center'>"
                    + "تعذّر تشغيل السيرفر المحلي (المنافذ مشغولة). أغلق التطبيق وافتحه مجدداً.</body>", "text/html; charset=utf-8", "utf-8");
        } else {
            web.loadUrl("http://localhost:" + port + "/");
        }
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    public void onBackPressed() {
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) {
            super.onBackPressed();
        } else {
            lastBack = now;
            Toast.makeText(this, "اضغط رجوع مرة أخرى للخروج (يوقف السيرفر)", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            stopService(new Intent(this, LanService.class));
            LanService.shutdown();
        }
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
