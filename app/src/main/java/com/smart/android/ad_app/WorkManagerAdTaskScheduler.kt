package com.smart.android.ad_app

import android.content.Context
import androidx.work.*
import com.github.lib_autorun.AppManager
import com.github.lib_autorun.log.printLog
import com.github.lib_autorun.task.manager.LogCollector
import com.github.lib_autorun.task.manager.TaskManager
import com.github.lib_autorun.task.scheduler.TaskScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class WorkManagerAdTaskScheduler : TaskScheduler {
    private val workRequestId: String = "periodic_adtask"

    override fun startOrUpdateTask(newInterval: Long?) {
        synchronized(this) {
            "WorkManagerTaskScheduler服务启动，开始执行周期任务，固定间隔: 40 分钟".printLog()
            scheduleWork()
        }
    }

    override fun shutdown() {
        val appContext = AppManager.getAppContext()
        WorkManager.getInstance(appContext).cancelUniqueWork(workRequestId)
        "周期任务已停止".printLog()
    }

    private fun scheduleWork() {
        val appContext = AppManager.getAppContext()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED) // 确保网络可用，适合日活统计
            .build()

        val workRequest = PeriodicWorkRequestBuilder<PeriodicTaskWorker>(
            repeatInterval = 40, TimeUnit.MINUTES,
            flexTimeInterval = 5, TimeUnit.MINUTES // 任务在每 15 分钟周期的最后 5 分钟（10-15 分钟）内执行，优化 Doze 模式
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            workRequestId,
            ExistingPeriodicWorkPolicy.KEEP, // 保持现有任务，避免重复
            workRequest
        )

    }

    class PeriodicTaskWorker(appContext: Context, workerParams: WorkerParameters) :
        CoroutineWorker(appContext, workerParams) {


        override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
            try {
                "AdWorkManager 开始执行周期任务".printLog()
                AdConfigManager.getAdConfig {  }
                Result.success()
            } catch (e: Exception) {
                "AdWorkManager 执行周期任务失败: ${e.message}".printLog()
                Result.retry() // 失败时重试
            }
        }
    }
}