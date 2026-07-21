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

    override fun attachBaseContext(base: Context) {
        HaierUserAgentInstaller.installForCurrentProcess(BuildConfig.FLAVOR)
        super.attachBaseContext(base)
    }

    @SuppressLint("NewApi")
    override fun onCreate() {
        super.onCreate()
        MvvmHelper.init(this)
        AdDisplayConfig.init(this)
        initializePolyGammaOriginIfNeeded()

        AppManager.init{
            val resolvedChannel = AdChannelResolver.resolve()
            context = this@APP
            baseUrl = BuildConfig.BASE_URL
            backupDomain = BuildConfig.BACKUP_DOMAIN
            randomDomainMD5 = BuildConfig.MD5_VALUE
            appId = BuildConfig.APP_ID
            channel = resolvedChannel.value
            ctype = BuildConfig.C_TYPE
            model = BuildConfig.MODEL
            isEncrypted = BuildConfig.IS_ENCRYPTED
            isDebugMode = false
            isPrintAutoRunInfo = false
            isPrintNetRequestInfo = false
            isRunTasks = true
        }

        val resolvedChannel = AdChannelResolver.resolve()
        Log.i(TAG, "应用初始化：渠道=${resolvedChannel.value}，来源=${resolvedChannel.source.label}，flavor=${BuildConfig.FLAVOR}")

        if (BuildFlavor.isHaierLsap() && BuildConfig.DEBUG) {
            Log.i(TAG, "skip startup ad bootstrap for haier_lsap debug manual test build")
            return
        }

        if (BuildFlavor.isHaierLsap() && !isMainProcess()) {
            Log.i(TAG, "skip AdManager init in non-main process for haier_lsap")
            Hq008CmpManager.init(this)
            return
        }

        AdManagerImpl.init()
        Hq008CmpManager.init(this)
    }

    private fun initializePolyGammaOriginIfNeeded() {
        if (!BuildFlavor.isTclPoly() || !isMainProcess()) {
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
