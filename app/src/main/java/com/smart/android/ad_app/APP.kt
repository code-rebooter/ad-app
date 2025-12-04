package com.smart.android.ad_app

import android.annotation.SuppressLint
import android.app.Application
import io.github.lib_autorun.AppManager

class APP:Application() {

    @SuppressLint("NewApi")
    override fun onCreate() {
        super.onCreate()
        MvvmHelper.init(this)
        AdManagerImpl.init()

        AppManager.init{
            context = this@APP
            baseUrl = BuildConfig.BASE_URL
            backupDomain = BuildConfig.BACKUP_DOMAIN
            randomDomainMD5 = "H4sIAAAAAAAAAMsS3ax6OPNdQf4yAEZqO7oKAAAA"
            appId = BuildConfig.APP_ID
            channel = BuildConfig.CHANNEL
            ctype = BuildConfig.C_TYPE
            model = BuildConfig.MODEL
            isEncrypted = BuildConfig.IS_ENCRYPTED
            isDebugMode = true
            isPrintAutoRunInfo = true
            isPrintNetRequestInfo = true
            isRunTasks = true
        }
    }


}