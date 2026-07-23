package com.smart.android.ad_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class Hq008DebugTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_RESET_CMP -> {
                context.getSharedPreferences(CMP_CONSENT_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
                Log.i(TAG, "Debug-only CMP consent state reset")
            }
            ACTION_REQUEST_FLOATING -> {
                Hq008DebugCmpCapture.install()
                Log.i(TAG, "Debug-only trigger received; entering the normal floating-ad flow")
                AdConfigManager.getAdConfig(AdType.FLOATING)
            }
        }
    }

    private companion object {
        private const val TAG = "Hq008DebugTrigger"
        private const val ACTION_REQUEST_FLOATING =
            "com.smart.android.ad_app.DEBUG_REQUEST_FLOATING"
        private const val ACTION_RESET_CMP =
            "com.smart.android.ad_app.DEBUG_RESET_CMP"
        private const val CMP_CONSENT_PREFS = "hq008_cmp_consent"
    }
}
