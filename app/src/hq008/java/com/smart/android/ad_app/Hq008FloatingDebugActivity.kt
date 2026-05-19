package com.smart.android.ad_app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.FragmentActivity

class Hq008FloatingDebugActivity : FragmentActivity() {

    private val finishHandler = Handler(Looper.getMainLooper())
    private val finishRunnable = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdConfigManager.getAdConfig(AdType.FLOATING)
        finishHandler.postDelayed(finishRunnable, 20_000L)
    }

    override fun onDestroy() {
        finishHandler.removeCallbacks(finishRunnable)
        super.onDestroy()
    }
}
