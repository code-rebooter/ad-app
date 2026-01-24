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

fun Int.getServerDimenPx(): Int {
    // 1. 适配系统常量：如果是 MATCH_PARENT (-1) 或 WRAP_CONTENT (-2)，直接返回原值    // 不要去拼接字符串寻找资源，因为系统 LayoutParams 识别的就是 -1 和 -2
    if (this == android.view.ViewGroup.LayoutParams.MATCH_PARENT ||
        this == android.view.ViewGroup.LayoutParams.WRAP_CONTENT) {
        return this
    }

    // 2. 正常逻辑：拼出资源名
    val resName = "qb_px_${this}"

    // 3. 找到资源 ID
    val resId = getDimenResourceId(appContext, resName)

    return if (resId != 0) {
        appContext.resources.getDimensionPixelSize(resId)
    } else {
        // 如果找不到资源，为了防止崩溃，可以考虑返回原值(this)或者抛出异常
        // 建议增加一个 log，方便排查缺失的 dimen
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


