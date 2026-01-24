package com.smart.android.ad_app

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import com.smart.android.ad_app.bean.AdConfigDto
import com.smart.android.ad_app.bean.Position

object AdRenderer {

    fun showSplashAd(dto: AdConfigDto) {
        val window = TvAdFloatingWindow(appContext)
        window.configure {
            width = MATCH_PARENT
            height = MATCH_PARENT
            x = 0
            y = 0
            position = Position.CENTER
            isFocusable = true
        }

        if (window.hasOverlayPermission()) {
            window.show()
        }
    }

    fun showFloatingAd(dto: AdConfigDto) {
        val window = TvAdFloatingWindow(appContext)
        window.configure {
            width = dto.floatingWidth
            height = dto.floatingHeight
            x = dto.floatingX ?: 0
            y = dto.floatingY ?: 0
            position = dto.positionEnum
            isFocusable = false
        }

        if (window.hasOverlayPermission()) {
            window.show()
        }
    }
}
