package com.smart.android.ad_app

import android.app.Activity
import android.os.Bundle

class GoogleUmpConsentActivity : Activity() {
    private var consentSessionId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consentSessionId = intent.getLongExtra(GoogleUmpConsentManager.EXTRA_SESSION_ID, 0L)
        GoogleUmpSilentConsentFormRunner.prepareHostActivity(this)
        GoogleUmpConsentManager.runConsentFlow(this, consentSessionId)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        GoogleUmpConsentManager.onHostActivityDestroyed(this, consentSessionId)
        super.onDestroy()
    }
}
