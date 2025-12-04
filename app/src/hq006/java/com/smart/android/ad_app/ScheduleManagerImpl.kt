package com.smart.android.ad_app

object ScheduleManagerImpl : IScheduleManager {
    override fun handlerInitialDelayTime(): Long  = 3500

    override fun handlerScheduleTime(): Long  = 5*60


}
