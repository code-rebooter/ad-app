package com.smart.android.ad_app

import android.view.View

object ScheduleManagerImpl : IScheduleManager {
    override fun handlerInitialDelayTime(): Long  = 25

    override fun handlerScheduleTime(): Long  = 40


}
