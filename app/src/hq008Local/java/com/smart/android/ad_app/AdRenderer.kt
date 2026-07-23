package com.smart.android.ad_app

import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import com.smart.android.ad_app.bean.Position

object AdRenderer {
    private const val TAG = "AdRenderer"
    private const val FLOATING_WIDTH = 240
    private const val FLOATING_HEIGHT = 135

    fun showAd(
        adType: AdType,
        adId: String? = null,
        onFloatingFlowFinished: (() -> Unit)? = null
    ) {
        when (adType) {
            AdType.SPLASH -> showSplashAd(adId)
            AdType.FLOATING -> showFloatingAd(adId, onFloatingFlowFinished)
        }
    }

    private fun showSplashAd(adId: String?) {
        val window = TvAdFloatingWindow(
            context = appContext,
            adId = adId,
            soundEnabled = true
        )
        window.configure {
            width = MATCH_PARENT
            height = MATCH_PARENT
            x = 0
            y = 0
            position = Position.CENTER
            isFocusable = true
        }
        Log.i(TAG, "HQ008 splash window uses the fixed full-screen layout")
        if (window.hasOverlayPermission()) {
            window.show()
        }
    }

    private fun showFloatingAd(adId: String?, onFloatingFlowFinished: (() -> Unit)?) {
        val window = TvAdFloatingWindow(
            context = appContext,
            adId = adId,
            soundEnabled = true,
            onFloatingFlowFinished = onFloatingFlowFinished
        )
        window.configure {
            width = FLOATING_WIDTH
            height = FLOATING_HEIGHT
            x = 0
            y = 0
            position = Position.RIGHT_BOTTOM
            isFocusable = false
        }
        Log.i(TAG, "HQ008 floating window fixed at right-bottom, size=${FLOATING_WIDTH}x$FLOATING_HEIGHT")
        if (window.hasOverlayPermission()) {
            window.show()
        } else {
            onFloatingFlowFinished?.invoke()
        }
    }
}
