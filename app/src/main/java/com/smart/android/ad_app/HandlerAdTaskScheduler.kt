package com.smart.android.ad_app

import android.os.Handler
import android.os.Looper
import com.github.lib_autorun.ext.NetCache.periodicTaskInterval
import com.github.lib_autorun.log.printLog
import com.github.lib_autorun.task.manager.LogCollector
import com.github.lib_autorun.task.manager.TaskManager
import com.github.lib_autorun.task.scheduler.TaskScheduler

class HandlerAdTaskScheduler : TaskScheduler {
    private val handler = Handler(Looper.getMainLooper())
    private var taskRunnable: Runnable? = null

    override fun startOrUpdateTask(newInterval: Long?) {
        synchronized(this) {
            "AdHandlerTaskScheduler 启动，间隔: ${newInterval?:0} 秒".printLog()

            taskRunnable?.let { handler.removeCallbacks(it) }
            taskRunnable = null
            taskRunnable = object : Runnable {
                override fun run() {
                    "AdHandlerTaskScheduler开始执行周期任务: ${System.currentTimeMillis()}".printLog()
                    AdConfigManager.getAdConfig()
                    TaskManager.executePeriodicTasks()
                    handler.postDelayed(this, (newInterval?:periodicTaskInterval()) * 1000)
                }
            }
            taskRunnable?.let { handler.post(it) }
        }
    }

    override fun shutdown() {
        taskRunnable?.let { handler.removeCallbacks(it) }
        taskRunnable = null
    }
}