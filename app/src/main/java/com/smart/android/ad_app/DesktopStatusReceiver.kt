package com.smart.android.ad_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

var isInDesktop = false
class DesktopStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == "io.permission.DESKTOP_STATUS") {
            val inDesktop = intent.getBooleanExtra("inDesktop", false)
            "收到桌面状态广播: $inDesktop".adDebugPrintLog()
            isInDesktop = inDesktop
        }
    }
}
