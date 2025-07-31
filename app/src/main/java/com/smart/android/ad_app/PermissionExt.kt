package com.smart.android.ad_app

import android.content.Context
import android.os.Process
import android.os.UserHandle

/**
 * 给指定包名的应用授权指定的权限
 *
 * @param packageName 目标应用包名
 * @param permission 需要授予的权限
 */
fun Context.grantPermission(packageName: String, permission: String) {
    try {
        // 获取 PackageManager 实例
        val pm = this.packageManager

        // 获取 grantRuntimePermission 方法
        val method = pm::class.java.getMethod(
            "grantRuntimePermission",
            String::class.java,
            String::class.java,
            UserHandle::class.java
        )

        // 调用 grantRuntimePermission 方法
        method.invoke(pm, packageName, permission, Process.myUserHandle())

    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun Context.grantSystemAlertWindowPermission(){
    this.grantPermission(this.packageName,"android.permission.SYSTEM_ALERT_WINDOW")
}
