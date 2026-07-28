@file:Suppress("DEPRECATION")

package com.smart.android.ad_app

import android.app.ActivityManager
import android.content.Context
import com.smart.android.ad_app.AdLocalLog as Log
import com.speed.log.printLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ForegroundAppWatcher {
    private const val TAG = "ForegroundAppWatcher"

    private val targetPackages = setOf(
        "com.google.android.youtube.tv",
        "com.netflix.mediaclient",
        "com.disney.disneyplus",
        "com.hulu.plus",
        "com.wbd.stream"
    )

    private const val intervalMs = 1_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var applicationContext: Context
    private var watcherJob: Job? = null
    private var lastPackageName: String? = null
    private var onTargetAppOpened: ((String) -> Unit)? = null

    fun start(context: Context, onAppOpened: (String) -> Unit) {
        synchronized(this) {
            applicationContext = context.applicationContext
            onTargetAppOpened = onAppOpened
            if (watcherJob?.isActive == true) {
                return
            }
            watcherJob = scope.launch {
                while (isActive) {
                    try {
                        val topPackage = getTopPackageName()
                        if (topPackage != null && topPackage != lastPackageName) {
                            Log.i(TAG, "topPackage changed: $lastPackageName -> $topPackage")
                            if (topPackage in targetPackages && lastPackageName !in targetPackages) {
                                withContext(Dispatchers.Main.immediate) {
                                    onTargetAppOpened?.invoke(topPackage)
                                }
                            }
                            lastPackageName = topPackage
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "watch failed: ${e.message}", e)
                    }
                    delay(intervalMs)
                }
            }
        }
    }

    fun stop() {
        synchronized(this) {
            watcherJob?.cancel()
            watcherJob = null
            lastPackageName = null
            onTargetAppOpened = null
        }
    }

    private fun getTopPackageName(): String? {
        val am = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningTasks(1).firstOrNull()?.topActivity?.packageName
    }
}
