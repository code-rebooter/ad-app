package com.smart.android.ad_app

object ScheduleManagerImpl : IScheduleManager {
    override fun handlerInitialDelayTime(): Long  = 4500

    override fun handlerScheduleTime(): Long  = 60


}
