package com.smart.android.ad_app

import android.os.Handler
import android.os.Looper
import com.smart.android.ad_app.AdLocalLog as Log
import com.speed.task.scheduler.TaskScheduler

object HandlerAdTaskScheduler : TaskScheduler {
    private const val TAG = "HandlerAdTaskScheduler"
    private val handler = Handler(Looper.getMainLooper())
    private var taskRunnable: Runnable? = null
    private var currentIntervalSeconds: Long? = null
    private var isExecuting = false
    private var skipAutoReschedule = false

    override fun startOrUpdateTask(newInterval: Long?) {
        val intervalSeconds = newInterval?.takeIf { it > 0 } ?: run {
            Log.e(TAG, "Invalid interval: ${newInterval ?: 0}")
            "AdHandlerTaskScheduler 启动失败，非法间隔: ${newInterval ?: 0}".adDebugPrintLog()
            shutdown()
            return
        }

        synchronized(this) {
            val runnable = taskRunnable
            if (runnable != null && currentIntervalSeconds == intervalSeconds) {
                Log.i(TAG, "Already running with interval=${intervalSeconds}s")
                "AdHandlerTaskScheduler 已运行，保持间隔: $intervalSeconds 秒".adDebugPrintLog()
                return
            }

            Log.i(TAG, "Start scheduler interval=${intervalSeconds}s")
            "AdHandlerTaskScheduler 启动，间隔: $intervalSeconds 秒".adDebugPrintLog()
            val isFirstStart = runnable == null
            runnable?.let(handler::removeCallbacks)

            currentIntervalSeconds = intervalSeconds
            if (isExecuting) {
                skipAutoReschedule = true
            }

            val activeRunnable = runnable ?: object : Runnable {
                override fun run() {
                    synchronized(this@HandlerAdTaskScheduler) {
                        isExecuting = true
                    }

                    if (BuildFlavor.isHq008Family()) {
                        Hq008LocalSchedulePolicy.markFloatingPollTriggered()
                    }
                    Log.i(TAG, "Run periodic task at=${System.currentTimeMillis()}")
                    "AdHandlerTaskScheduler 开始执行周期任务: ${System.currentTimeMillis()}".adDebugPrintLog()
                    AdConfigManager.getAdConfig(AdType.FLOATING)

                    val nextIntervalSeconds: Long?
                    val shouldAutoReschedule: Boolean
                    synchronized(this@HandlerAdTaskScheduler) {
                        isExecuting = false
                        nextIntervalSeconds = currentIntervalSeconds
                        shouldAutoReschedule = !skipAutoReschedule && taskRunnable === this && nextIntervalSeconds != null
                        if (skipAutoReschedule) {
                            skipAutoReschedule = false
                        }
                    }

                    if (shouldAutoReschedule) {
                        handler.postDelayed(this, nextIntervalSeconds!! * 1000)
                    }
                }
            }
            taskRunnable = activeRunnable

            if (isFirstStart) {
                handler.post(activeRunnable)
            } else {
                handler.postDelayed(activeRunnable, intervalSeconds * 1000)
            }
        }
    }

    override fun shutdown() {
        synchronized(this) {
            Log.i(TAG, "Shutdown scheduler")
            taskRunnable?.let(handler::removeCallbacks)
            taskRunnable = null
            currentIntervalSeconds = null
            isExecuting = false
            skipAutoReschedule = false
        }
    }
}
