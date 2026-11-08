package com.smart.smoke;
import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;
import com.google.gson.JsonParser;
import com.smart.android.tclad.ima.*;
public final class SmokeActivity extends Activity {
    private GoogleImaAdManager manager;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout container = new FrameLayout(this);
        setContentView(container);
        GoogleImaExecutionConfig execution = GoogleImaExecutionConfig.builder(this, "consumer-smoke", "test-channel")
            .auditEnabled(false).build();
        manager = new GoogleImaAdManager(execution, new GoogleImaSessionListener() {});
        manager.showAd(container, JsonParser.parseString("{}"), false);
    }
    @Override public void onDestroy() { if (manager != null) manager.release(); super.onDestroy(); }
}
