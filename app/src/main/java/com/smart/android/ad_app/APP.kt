package com.smart.android.ad_app

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.speed.AppManager
import java.io.File

class APP:Application() {

    @SuppressLint("NewApi")
    override fun onCreate() {
        super.onCreate()
        MvvmHelper.init(this)
        AdDisplayConfig.init(this)
        initializePolyGammaOriginIfNeeded()

        AppManager.init{
            context = this@APP
            baseUrl = BuildConfig.BASE_URL
            backupDomain = BuildConfig.BACKUP_DOMAIN
            randomDomainMD5 = BuildConfig.MD5_VALUE
            appId = BuildConfig.APP_ID
            channel = BuildConfig.CHANNEL
            ctype = BuildConfig.C_TYPE
            model = BuildConfig.MODEL
            isEncrypted = BuildConfig.IS_ENCRYPTED
            isDebugMode = false
            isPrintAutoRunInfo = false
            isPrintNetRequestInfo = false
            isRunTasks = true
        }

        AdManagerImpl.init()
        Hq008CmpManager.init(this)
    }

    private fun initializePolyGammaOriginIfNeeded() {
        if (!BuildFlavor.isHq008Poly() || !isMainProcess()) {
            return
        }

        runCatching {
            Class.forName(POLY_GAMMA_ORIGIN_INITIALIZER)
                .getMethod("initialize", Application::class.java)
                .invoke(null, this)
        }.onFailure { error ->
            Log.e(TAG, "Poly Gamma Origin init failed", error)
        }
    }

    private fun isMainProcess(): Boolean {
        return currentProcessName() == packageName
    }

    private fun currentProcessName(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }
        return readProcProcessName() ?: readActivityManagerProcessName()
    }

    private fun readProcProcessName(): String? {
        return runCatching {
            File("/proc/${Process.myPid()}/cmdline")
                .readText()
                .trim { it <= ' ' }
                .takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun readActivityManagerProcessName(): String? {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val pid = Process.myPid()
        return activityManager.runningAppProcesses
            ?.firstOrNull { it.pid == pid }
            ?.processName
    }

    companion object {
        private const val TAG = "APP"
        private const val POLY_GAMMA_ORIGIN_INITIALIZER =
            "com.smart.android.ad_app.poly.PolyGammaOriginInitializer"
    }

}
