package com.smart.android.ad_app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
