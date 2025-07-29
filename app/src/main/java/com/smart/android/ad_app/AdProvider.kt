package com.smart.android.ad_app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.github.lib_autorun.AppManager
import com.github.lib_autorun.ext.clearAllSP
import com.github.lib_autorun.ext.isNetworkAvailable
import com.github.lib_autorun.ext.restartService
import com.github.lib_autorun.ext.setupKeepAliveAlarm
import com.github.lib_autorun.task.manager.LogCollector
import com.github.lib_autorun.log.printLog
import com.github.lib_autorun.service.CoreService
import com.github.lib_autorun.task.manager.TaskManager
import com.github.lib_autorun.task.scheduler.HandlerTaskScheduler
import com.github.lib_autorun.task.scheduler.WorkManagerTaskScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.jvm.java

class AdProvider : ContentProvider() {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var job: Job? = null

    override fun onCreate(): Boolean {
        "AdProvider执行：ServiceTriggerProvider onCreate executed".printLog()

        // 检查并启动 闹钟和Service
        context?.let { ctx ->
            job?.cancel()
            job = scope.launch {
                checkNetworkAndStart(ctx)
            }
        }
        return true
    }

    private suspend fun checkNetworkAndStart(context: Context) {
        while (true) {
            if (context.isNetworkAvailable()) {
                "网络正常，执行任务".printLog()

                //clearAllSP()
                scope.launch {
                    delay(15000)
                    // 延迟初始化 WorkManagerTaskScheduler
                    scope.launch {
                        try {
                            val scheduler = WorkManagerAdTaskScheduler()
                            scheduler.startOrUpdateTask(null)
                        } catch (e: Exception) {
                            "ADWorkManagerTaskScheduler 初始化失败: ${e.message}, 堆栈: ${e.stackTraceToString()}".printLog()

                        }
                    }
                }

                scope.launch {
                    "Ad开始延迟HandlerTaskScheduler任务".printLog()
                    delay(5 * 1000L)
                    try {
                        val schedulerHandler = HandlerAdTaskScheduler()
                        schedulerHandler.startOrUpdateTask(20)
                    } catch (e: Exception) {
                        "AdHandlerTaskScheduler 初始化失败: ${e.message}, 堆栈: ${e.stackTraceToString()}".printLog()

                    }
                }


                //scope.cancel("Network available, tasks completed") // 任务完成后取消协程
                break
            } else {
                "网络不可用，等待中...".printLog()
                delay(5000) // 等待5秒
            }
        }
    }


    // 以下方法无需实现，仅占位
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0
}