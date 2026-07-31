package com.smart.android.adsdk.internal;

import android.app.Activity;
import android.os.Bundle;

public class AdConsentActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SilentConsentFormRunner.prepareHostActivity(this);
        AdConsentManager.runConsentFlow(this);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        AdConsentManager.onHostActivityDestroyed(this);
        super.onDestroy();
    }
}
