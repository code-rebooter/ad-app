package com.smart.android.ad_app

import com.smart.android.ad_app.AdLocalLog as Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import com.smart.android.ad_app.bean.AdConfigDto
import com.smart.android.ad_app.bean.Position

object AdRenderer {
    private const val TAG = "AdRenderer"

    private const val HIDDEN_WINDOW_WIDTH = 320
    private const val HIDDEN_WINDOW_HEIGHT = 180
    private const val HIDDEN_WINDOW_OFFSET = -4000
    private val floatingWindowLock = Any()
    private var activeFloatingWindow: TvAdFloatingWindow? = null

    private data class WindowRenderConfig(
        val width: Int?,
        val height: Int?,
        val x: Int,
        val y: Int,
        val position: Position,
        val isFocusable: Boolean
    )

    fun showSplashAd(dto: AdConfigDto) {
        val window = TvAdFloatingWindow(
            context = appContext,
            adId = dto.adId,
            soundEnabled = dto.soundEnabled,
            callbackTimeoutMs = dto.callbackTimeoutMs
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
            "广告展示链路：准备展示开屏广告，adId=${dto.adId}，hidden=${AdDisplayConfig.isHiddenMode()}，width=${renderConfig.width}，height=${renderConfig.height}，x=${renderConfig.x}，y=${renderConfig.y}，focusable=${renderConfig.isFocusable}"
        )

        if (window.hasOverlayPermission()) {
            window.show()
        }
    }

    fun showFloatingAd(
        dto: AdConfigDto,
        onFloatingFlowFinished: (() -> Unit)? = null
    ) {
        var windowRef: TvAdFloatingWindow? = null
        val window = TvAdFloatingWindow(
            context = appContext,
            adId = dto.adId,
            soundEnabled = dto.soundEnabled,
            callbackTimeoutMs = dto.callbackTimeoutMs,
            onFloatingFlowFinished = {
                clearActiveFloatingWindow(windowRef)
                onFloatingFlowFinished?.invoke()
            }
        )
        windowRef = window
        synchronized(floatingWindowLock) {
            activeFloatingWindow = window
        }
        if (!AdPlaybackAvailability.isPlaybackAllowed()) {
            stopFloatingAd("external_playback_denied:before_window_show")
            return
        }
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
            "广告展示链路：准备展示悬浮广告，adId=${dto.adId}，hidden=${AdDisplayConfig.isHiddenMode()}，width=${renderConfig.width}，height=${renderConfig.height}，x=${renderConfig.x}，y=${renderConfig.y}，focusable=${renderConfig.isFocusable}"
        )

        if (window.hasOverlayPermission()) {
            window.show()
        } else {
            clearActiveFloatingWindow(window)
            onFloatingFlowFinished?.invoke()
        }
    }

    fun stopFloatingAd(reason: String) {
        val window = synchronized(floatingWindowLock) {
            activeFloatingWindow.also { activeFloatingWindow = null }
        }
        if (window == null) {
            return
        }
        Log.i(TAG, "广告展示链路：外部状态要求停止当前悬浮广告，reason=$reason")
        window.destroy()
    }

    private fun clearActiveFloatingWindow(window: TvAdFloatingWindow?) {
        synchronized(floatingWindowLock) {
            if (activeFloatingWindow === window) {
                activeFloatingWindow = null
            }
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
        val useHiddenMode = BuildFlavor.isHq008Family() && AdDisplayConfig.isHiddenMode()
        if (!useHiddenMode) {
            return WindowRenderConfig(
                width = defaultWidth,
                height = defaultHeight,
                x = defaultX,
                y = defaultY,
                position = defaultPosition,
                isFocusable = defaultFocusable
            )
        }
        return WindowRenderConfig(
            width = HIDDEN_WINDOW_WIDTH,
            height = HIDDEN_WINDOW_HEIGHT,
            x = HIDDEN_WINDOW_OFFSET,
            y = HIDDEN_WINDOW_OFFSET,
            position = Position.LEFT_TOP,
            isFocusable = false
        )
    }
}
