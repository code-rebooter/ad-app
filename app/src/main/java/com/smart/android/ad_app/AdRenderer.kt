package com.smart.android.ad_app

import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import com.smart.android.ad_app.bean.AdConfigDto
import com.smart.android.ad_app.bean.Position

object AdRenderer {
    private const val TAG = "AdRenderer"
    private const val HQ008_FLOATING_WIDTH = 400
    private const val HQ008_FLOATING_HEIGHT = 240
    private const val HQ008_FLOATING_X = 0
    private const val HQ008_FLOATING_Y = 0

    private data class WindowRenderConfig(
        val width: Int?,
        val height: Int?,
        val x: Int,
        val y: Int,
        val position: Position,
        val isFocusable: Boolean
    )

    fun showHq008LocalAd(
        adType: AdType,
        onFloatingFlowFinished: (() -> Unit)? = null
    ) {
        when (adType) {
            AdType.SPLASH -> showHq008SplashAd()
            AdType.FLOATING -> showHq008FloatingAd(onFloatingFlowFinished)
        }
    }

    private fun showHq008SplashAd() {
        val window = TvAdFloatingWindow(
            context = appContext,
            adId = null,
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

    private fun showHq008FloatingAd(onFloatingFlowFinished: (() -> Unit)?) {
        val window = TvAdFloatingWindow(
            context = appContext,
            adId = null,
            soundEnabled = true,
            onFloatingFlowFinished = onFloatingFlowFinished
        )
        window.configure {
            width = HQ008_FLOATING_WIDTH
            height = HQ008_FLOATING_HEIGHT
            x = HQ008_FLOATING_X
            y = HQ008_FLOATING_Y
            position = Position.RIGHT_BOTTOM
            isFocusable = false
        }
        Log.i(
            TAG,
            "HQ008 floating window fixed at right-bottom, size=${HQ008_FLOATING_WIDTH}x$HQ008_FLOATING_HEIGHT"
        )
        if (window.hasOverlayPermission()) {
            window.show()
        } else {
            onFloatingFlowFinished?.invoke()
        }
    }

    fun showSplashAd(dto: AdConfigDto) {
        val window = TvAdFloatingWindow(
            context = appContext,
            adId = dto.adId,
            soundEnabled = dto.soundEnabled
        )
        val renderConfig = resolveRenderConfig(
            defaultWidth = MATCH_PARENT,
            defaultHeight = MATCH_PARENT,
            defaultX = 0,
            defaultY = 0,
            defaultPosition = Position.CENTER,
            defaultFocusable = true
        )
        window.configure {
            width = renderConfig.width
            height = renderConfig.height
            x = renderConfig.x
            y = renderConfig.y
            position = renderConfig.position
            isFocusable = renderConfig.isFocusable
        }
        Log.i(
            TAG,
            "广告展示链路：准备展示开屏广告，adId=${dto.adId}，width=${renderConfig.width}，height=${renderConfig.height}，x=${renderConfig.x}，y=${renderConfig.y}，focusable=${renderConfig.isFocusable}"
        )

        if (window.hasOverlayPermission()) {
            window.show()
        }
    }

    fun showFloatingAd(
        dto: AdConfigDto,
        onFloatingFlowFinished: (() -> Unit)? = null
    ) {
        val window = TvAdFloatingWindow(
            context = appContext,
            adId = dto.adId,
            soundEnabled = dto.soundEnabled,
            onFloatingFlowFinished = onFloatingFlowFinished
        )
        val renderConfig = resolveRenderConfig(
            defaultWidth = dto.floatingWidth,
            defaultHeight = dto.floatingHeight,
            defaultX = dto.floatingX ?: 0,
            defaultY = dto.floatingY ?: 0,
            defaultPosition = dto.positionEnum,
            defaultFocusable = false
        )
        window.configure {
            width = renderConfig.width
            height = renderConfig.height
            x = renderConfig.x
            y = renderConfig.y
            position = renderConfig.position
            isFocusable = renderConfig.isFocusable
        }
        Log.i(
            TAG,
            "广告展示链路：准备展示悬浮广告，adId=${dto.adId}，width=${renderConfig.width}，height=${renderConfig.height}，x=${renderConfig.x}，y=${renderConfig.y}，focusable=${renderConfig.isFocusable}"
        )

        if (window.hasOverlayPermission()) {
            window.show()
        } else {
            onFloatingFlowFinished?.invoke()
        }
    }

    private fun resolveRenderConfig(
        defaultWidth: Int?,
        defaultHeight: Int?,
        defaultX: Int,
        defaultY: Int,
        defaultPosition: Position,
        defaultFocusable: Boolean
    ): WindowRenderConfig {
        return WindowRenderConfig(
            width = defaultWidth,
            height = defaultHeight,
            x = defaultX,
            y = defaultY,
            position = defaultPosition,
            isFocusable = defaultFocusable
        )
    }
}
