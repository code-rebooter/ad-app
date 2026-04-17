package com.smart.android.ad_app

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.provider.Settings

/**
 * 检查并请求悬浮窗权限
 */
fun Context.requestOverlayPermission(requestCode: Int = 1001) {
    if (!Settings.canDrawOverlays(this)) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:$packageName".toUri()
        )
        if (this is android.app.Activity) {
            startActivityForResult(intent, requestCode)
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }
}
