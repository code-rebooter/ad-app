package com.smart.android.ad_app

import android.view.View
import android.widget.FrameLayout

interface IScheduleManager {
    fun handlerInitialDelayTime(): Long
    fun handlerScheduleTime(): Long
}