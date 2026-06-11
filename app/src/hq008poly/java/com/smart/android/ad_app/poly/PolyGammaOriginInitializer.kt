package com.smart.android.ad_app.poly

import android.app.Application
import android.content.Context
import android.provider.Settings
import android.util.Pair
import org.polygamma.android.origin.Origin
import org.polygamma.android.origin.OriginOptions

object PolyGammaOriginInitializer {

    @JvmStatic
    fun initialize(application: Application) {
        Origin.initializeWithOptions(
            application,
            OriginOptions()
                .addCapability(Origin.CAPABILITY_ANTIFRAUD)
                .addDynamicDeviceId("DeviceID") { context ->
                    Pair(resolveDeviceId(context.applicationContext), false)
                }
                .addAndroidDeviceId(application)
                .addTelephonyDeviceId(application)
        )
    }

    private fun resolveDeviceId(context: Context): String {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
    }
}
