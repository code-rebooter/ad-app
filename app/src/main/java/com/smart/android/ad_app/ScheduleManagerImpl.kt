package com.smart.android.ad_app

object ScheduleManagerImpl : IScheduleManager {
    override fun handlerInitialDelayTime(): Long {
        return if (BuildConfig.HQ008_LOCAL_INTEGRATION) {
            Hq008LocalSchedulePolicy.initialDelayMs()
        } else {
            BuildConfig.HANDLER_INITIAL_DELAY_MS
        }
    }

    override fun handlerScheduleTime(): Long {
        return if (BuildConfig.HQ008_LOCAL_INTEGRATION) {
            Hq008LocalSchedulePolicy.pollingSeconds()
        } else {
            BuildConfig.HANDLER_SCHEDULE_SECONDS
        }
    }
}
