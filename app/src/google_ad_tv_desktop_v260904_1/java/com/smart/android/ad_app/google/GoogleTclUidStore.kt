package com.smart.android.ad_app.google

import android.content.Context
import java.util.UUID

/** Same read/create/persist behavior as TCL DeviceInfoGeneratorImpl, in host storage. */
internal object GoogleTclUidStore {
    @Synchronized
    fun readOrCreate(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences("google_tcl_ad", Context.MODE_PRIVATE)
        val stored = prefs.getString("aid", "").orEmpty()
        if (stored.isNotBlank()) return stored
        return UUID.randomUUID().toString().also { uid ->
            prefs.edit().putString("aid", uid).apply()
        }
    }
}
