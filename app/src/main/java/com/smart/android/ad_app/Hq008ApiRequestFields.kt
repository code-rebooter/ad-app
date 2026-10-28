package com.smart.android.ad_app

import android.content.Context
import android.provider.Settings
import java.util.UUID

/** Metadata sent only to our backend; it does not alter the Google ad URL. */
internal object Hq008ApiRequestFields {
    // This app uses the TCL 2.8.02 execution SDK, not the universal SDK 1.0.0.
    private const val TCL_SDK_VERSION = "2.8.02"

    fun appendTo(body: MutableMap<String, Any>, context: Context, googleAuthorization: Boolean = false) {
        body.putAll(fields(BuildConfig.AD_CHANNEL_TYPE, BuildConfig.VERSION_NAME) {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        })
        if (googleAuthorization && BuildFlavor.isGoogleAd()) {
            body["rdid"] = readOrCreateRdid(context)
        }
    }

    internal fun fields(channelType: String, versionName: String, androidId: () -> String?): Map<String, Any> =
        linkedMapOf<String, Any>("androidid" to runCatching(androidId).getOrNull().orEmpty()).apply {
            if (BuildFlavor.usesTclRuntime(channelType)) {
                put("ad_version_name", versionName)
                put("sdk_version", TCL_SDK_VERSION)
            }
        }

    @Synchronized
    fun readOrCreateRdid(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences("google_tcl_ad", Context.MODE_PRIVATE)
        return persistedRdid(
            read = { prefs.getString("aid", "") },
            save = { prefs.edit().putString("aid", it).apply() }
        )
    }

    internal fun persistedRdid(
        read: () -> String?, save: (String) -> Unit,
        create: () -> String = { UUID.randomUUID().toString() }
    ): String = read()?.takeIf { it.isNotBlank() } ?: create().also(save)
}
