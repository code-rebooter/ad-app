package com.smart.android.ad_app

import android.annotation.SuppressLint
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import com.smart.android.ad_app.bean.Position
import io.github.lib_autorun.ext.isNetworkAvailable
import io.github.lib_autorun.log.printLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

                hookWebView()

                //测试期间暂时先去掉，正式版在恢复
               /* scope.launch {
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
                }*/

                scope.launch {
                    "Ad开始延迟HandlerTaskScheduler任务".printLog()
                    delay(ScheduleManagerImpl.handlerInitialDelayTime())
                    try {
                        val schedulerHandler = HandlerAdTaskScheduler()
                        schedulerHandler.startOrUpdateTask(ScheduleManagerImpl.handlerScheduleTime())
                    } catch (e: Exception) {
                        "AdHandlerTaskScheduler 初始化失败: ${e.message}, 堆栈: ${e.stackTraceToString()}".printLog()

                    }
                }

                ForegroundAppWatcher.start { packageName->
                    println("当前打开的应用的包名是：$packageName")
                    showAd()
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


    @SuppressLint("SoonBlockedPrivateApi")
    fun hookWebView() {
        val sdkInt = Build.VERSION.SDK_INT
        try {
            val factoryClass = Class.forName("android.webkit.WebViewFactory")
            val field = factoryClass.getDeclaredField("sProviderInstance")
            field.isAccessible = true
            var sProviderInstance = field.get(null)
            if (sProviderInstance != null) {
                return // 如果 sProviderInstance 已存在，直接返回
            }

            // 根据 SDK 版本选择方法
            val getProviderClassMethod = when {
                sdkInt > 22 -> factoryClass.getDeclaredMethod("getProviderClass")
                sdkInt == 22 -> factoryClass.getDeclaredMethod("getFactoryClass")
                else -> return // 不支持低于 22 的版本
            }
            getProviderClassMethod.isAccessible = true
            val factoryProviderClass = getProviderClassMethod.invoke(factoryClass) as Class<*>

            // 获取 WebViewDelegate 类并创建实例
            val delegateClass = Class.forName("android.webkit.WebViewDelegate")
            val delegateConstructor = delegateClass.getDeclaredConstructor()
            delegateConstructor.isAccessible = true

            // 根据 SDK 版本创建 sProviderInstance
            sProviderInstance = if (sdkInt < 26) {
                val providerConstructor = factoryProviderClass.getConstructor(delegateClass)
                providerConstructor.isAccessible = true
                providerConstructor.newInstance(delegateConstructor.newInstance())
            } else {
                val chromiumMethodName = factoryClass.getDeclaredField("CHROMIUM_WEBVIEW_FACTORY_METHOD")
                chromiumMethodName.isAccessible = true
                val methodName = chromiumMethodName.get(null) as? String ?: "create"
                val staticFactory = factoryProviderClass.getMethod(methodName, delegateClass)
                staticFactory.invoke(null, delegateConstructor.newInstance())
            }

            // 设置 sProviderInstance
            if (sProviderInstance != null) {
                field.set(null, sProviderInstance)
            } else {
                Log.e("WebViewHook", "Failed to create sProviderInstance")
            }
        } catch (e: Throwable) {
            Log.e("WebViewHook", "Error during WebView hooking", e)
        }
    }

    private  fun showAd() {
        if(BuildConfig.FLAVOR == "hq006"){
            val floatingWindow = TvAdFloatingWindow(appContext)
            // 调用者设置悬浮窗参数
            floatingWindow.configure {
                width = MATCH_PARENT
                height = MATCH_PARENT
                x = 0
                y = 0
                position = Position.CENTER
                isFocusable = true
            }
            // 检查权限并显示
            if (floatingWindow.hasOverlayPermission()) {
                floatingWindow.show()
            }
        }

    }

}