package com.smart.android.ad_app

import android.content.Context
import android.util.Log
import io.github.lib_autorun.ext.isNetworkAvailable
import io.github.lib_autorun.log.printLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object AdRuntimeCoordinator {

    private const val TAG = "AdRuntimeCoordinator"
    private const val networkRetryDelayMs = 5_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var startupJob: Job? = null

    fun start(context: Context) {
        val appContext = context.applicationContext
        Log.i(TAG, "start called flavor=${BuildConfig.FLAVOR} package=${appContext.packageName}")
        synchronized(this) {
            if (startupJob?.isActive == true) {
                Log.i(TAG, "startupJob already active, skip.")
                return
            }
            startupJob = scope.launch {
                Log.i(TAG, "startup coroutine launched.")
                waitForNetwork(appContext)
                if (!isActive) {
                    Log.w(TAG, "startup coroutine cancelled before completion.")
                    return@launch
                }

                if (BuildConfig.FLAVOR == "hq006") {
                    WebViewProviderHook.ensureHooked()
                }

                Log.i(TAG, "Runtime start flavor=${BuildConfig.FLAVOR} hidden=${AdDisplayConfig.isHiddenMode()}")
                ForegroundAppWatcher.start { packageName ->
                    Log.i(TAG, "Foreground target app opened: $packageName")
                    "当前打开的应用包名: $packageName".printLog()
                    AdConfigManager.getAdConfig(AdType.SPLASH)
                }

                val initialDelayMs = ScheduleManagerImpl.handlerInitialDelayTime().coerceAtLeast(0)
                if (initialDelayMs > 0) {
                    Log.i(TAG, "Initial delay before handler scheduler: ${initialDelayMs}ms")
                    "Ad开始延迟 HandlerTaskScheduler 任务".printLog()
                    delay(initialDelayMs)
                }

                Log.i(TAG, "Start handler scheduler interval=${ScheduleManagerImpl.handlerScheduleTime()}s")
                HandlerAdTaskScheduler.startOrUpdateTask(ScheduleManagerImpl.handlerScheduleTime())
            }
        }
    }

    private suspend fun waitForNetwork(context: Context) {
        while (currentCoroutineContext().isActive && !context.isNetworkAvailable()) {
            Log.w(TAG, "Network unavailable, waiting...")
            "网络不可用，等待中...".printLog()
            delay(networkRetryDelayMs)
        }
        if (context.isNetworkAvailable()) {
            Log.i(TAG, "Network available, continue startup tasks.")
            "网络正常，执行启动任务".printLog()
        }
    }
}
