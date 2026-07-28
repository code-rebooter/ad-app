package com.smart.android.ad_app

import android.app.Activity
import android.os.Bundle

class GoogleUmpConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GoogleUmpSilentConsentFormRunner.prepareHostActivity(this)
        GoogleUmpConsentManager.runConsentFlow(this)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        GoogleUmpConsentManager.onHostActivityDestroyed(this)
        super.onDestroy()
    }
}
