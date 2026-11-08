package com.smart.android.ad_app
import android.app.Application
import com.speed.AppManager
class BackportValidationApplication : Application() {
 override fun onCreate() {
  super.onCreate(); MvvmHelper.init(this); AdDisplayConfig.init(this)
  AppManager.init {
   context=this@BackportValidationApplication; baseUrl=BuildConfig.BASE_URL
   backupDomain=BuildConfig.BACKUP_DOMAIN; randomDomainMD5=BuildConfig.MD5_VALUE
   appId=BuildConfig.APP_ID; channel=AdChannelResolver.currentChannel(); ctype=BuildConfig.C_TYPE
   model=BuildConfig.MODEL; isEncrypted=BuildConfig.IS_ENCRYPTED; isRunTasks=false
  }
  AdDisplayConfig.setRemoteHiddenMode(false)
 }
}
