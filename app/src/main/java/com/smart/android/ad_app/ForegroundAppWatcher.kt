package com.smart.android.ad_app

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper

object ForegroundAppWatcher {

    private val targetPackages = setOf(
        "com.google.android.youtube.tv"
    )

    private const val intervalMs = 200L
    private var lastPackageName: String? = null
    private var isWatching = false
    private val handler = Handler(Looper.getMainLooper())
    private var onTargetAppOpened: ((String) -> Unit)? = null

    private val checkRunnable = object : Runnable {
        override fun run() {
            try {
                val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val tasks = am.getRunningTasks(1)
                if (tasks.isNotEmpty()) {
                    val topPackage = tasks[0].topActivity?.packageName
                    if (topPackage != null && topPackage != lastPackageName) {
                        if (topPackage in targetPackages && lastPackageName !in targetPackages) {
                            // 用户刚切换进入目标 App
                            onTargetAppOpened?.invoke(topPackage)
                        }
                        lastPackageName = topPackage
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            if (isWatching) {
                handler.postDelayed(this, intervalMs)
            }
        }
    }

    fun start(onAppOpened: (String) -> Unit) {
        if (isWatching) return
        isWatching = true
        onTargetAppOpened = onAppOpened
        handler.post(checkRunnable)
    }

    fun stop() {
        isWatching = false
        onTargetAppOpened = null
        handler.removeCallbacks(checkRunnable)
    }
}
