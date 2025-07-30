package com.smart.android.ad_app

import android.view.View

object ScheduleManagerImpl : IScheduleManager {
    override fun handlerInitialDelayTime(): Long  = 45

    override fun handlerScheduleTime(): Long  = 60


}
