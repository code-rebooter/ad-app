package com.smart.android.ad_app

object ScheduleManagerImpl : IScheduleManager {
    override fun handlerInitialDelayTime(): Long {
        return if (BuildFlavor.isHq008Family()) {
            Hq008LocalSchedulePolicy.initialDelayMs()
        } else {
            BuildConfig.HANDLER_INITIAL_DELAY_MS
        }
    }

    override fun handlerScheduleTime(): Long {
        return if (BuildFlavor.isHq008Family()) {
            Hq008LocalSchedulePolicy.pollingSeconds()
        } else {
            BuildConfig.HANDLER_SCHEDULE_SECONDS
        }
    }
}
