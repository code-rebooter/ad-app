package com.smart.android.ad_app

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

fun Context.isInHome(): Boolean {
    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val runningTasks = activityManager.getRunningTasks(1)
    if (runningTasks.isNullOrEmpty()) return false

    val topPackageName = runningTasks[0].topActivity?.packageName ?: return false

    // 获取所有桌面应用的包名
    val homePackages = packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
        PackageManager.MATCH_DEFAULT_ONLY
    ).map { it.activityInfo.packageName }

    return homePackages.contains(topPackageName)
}

fun Context.isInHomeOrAppStore(): Boolean {
    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val runningTasks = activityManager.getRunningTasks(1)
    if (runningTasks.isNullOrEmpty()) return false

    val topPackageName = runningTasks[0].topActivity?.packageName ?: return false
    val topActivityName = runningTasks[0].topActivity?.className ?: return false
    println("当前顶部的Activity路径是：$topActivityName")
    if(topActivityName.contains("海勤引导页Activity名字"))return false

    if(topPackageName=="com.dolphinos.tv.store")return true
    // 获取所有桌面应用的包名
    val homePackages = packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
        PackageManager.MATCH_DEFAULT_ONLY
    ).map { it.activityInfo.packageName }

    return homePackages.contains(topPackageName)
}

fun Int.getServerDimenPx( ): Int {
    // 拼出资源名
    val resName = "qb_px_${this}"
    // 找到资源 ID
    val resId = getDimenResourceId(appContext,resName)
    if (resId != 0) {
        return appContext.resources.getDimensionPixelSize(resId)
    } else {
        throw IllegalArgumentException("Dimen resource not found for: $resName")
    }
}

fun getDimenResourceId(context: Context, dimenName: String): Int {
    return context.resources.getIdentifier(
        dimenName,  // 资源名称（如 "qb_400"）
        "dimen",     // 资源类型（dimen）
        context.packageName
    )
}


