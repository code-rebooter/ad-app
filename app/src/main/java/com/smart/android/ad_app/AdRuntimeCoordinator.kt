package com.smart.android.ad_app

import android.content.Context
import android.util.Log
import com.speed.ext.isNetworkAvailable
import com.speed.log.printLog
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
        if (BuildFlavor.isHq008Family()) {
            Hq008LocalSchedulePolicy.initialize(appContext)
        }
        Log.i(TAG, "正式链路：开始启动广告运行协调器，flavor=${BuildConfig.FLAVOR}，package=${appContext.packageName}")
        synchronized(this) {
            if (startupJob?.isActive == true) {
                Log.i(TAG, "正式链路：广告运行协调器已有启动任务在执行，本次跳过重复启动")
                return
            }
            startupJob = scope.launch {
                Log.i(TAG, "正式链路：启动协程已创建，开始等待网络、初始化监听与调度")
                waitForNetwork(appContext)
                if (!isActive) {
                    Log.w(TAG, "正式链路：启动协程在完成前被取消，本轮初始化终止")
                    return@launch
                }

                if (BuildConfig.FLAVOR == "hq006") {
                    WebViewProviderHook.ensureHooked()
                }

                Log.i(TAG, "正式链路：运行协调器进入主流程，flavor=${BuildConfig.FLAVOR}，当前隐藏模式=${AdDisplayConfig.isHiddenMode()}")
                ForegroundAppWatcher.start(appContext) { packageName ->
                    Log.i(TAG, "正式链路：监听到前台目标应用变化，packageName=$packageName，准备请求开屏广告")
                    "当前打开的应用包名: $packageName".printLog()
                    AdConfigManager.getAdConfig(AdType.SPLASH)
                }

                val initialDelayMs = ScheduleManagerImpl.handlerInitialDelayTime().coerceAtLeast(0)
                if (initialDelayMs > 0) {
                    Log.i(TAG, "正式链路：在启动定时调度前先等待 ${initialDelayMs}ms，避免进程刚启动就立刻请求广告")
                    "Ad开始延迟 HandlerTaskScheduler 任务".printLog()
                    delay(initialDelayMs)
                }

                Log.i(TAG, "正式链路：开始启动 Handler 定时调度器，轮询间隔=${ScheduleManagerImpl.handlerScheduleTime()}s")
                HandlerAdTaskScheduler.startOrUpdateTask(ScheduleManagerImpl.handlerScheduleTime())
            }
        }
    }

    private suspend fun waitForNetwork(context: Context) {
        while (currentCoroutineContext().isActive && !context.isNetworkAvailable()) {
            Log.w(TAG, "正式链路：当前网络不可用，等待恢复后再继续初始化广告能力")
            "网络不可用，等待中...".printLog()
            delay(networkRetryDelayMs)
        }
        if (context.isNetworkAvailable()) {
            Log.i(TAG, "正式链路：网络已恢复可用，继续执行广告初始化任务")
            "网络正常，执行启动任务".printLog()
        }
    }
}
