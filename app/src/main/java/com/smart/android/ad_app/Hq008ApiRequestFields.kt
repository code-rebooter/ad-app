package com.smart.android.ad_app

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
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

    /** TCL UID only; participating processes read the effective persisted XML under the same lock. */
    fun readOrCreateRdid(context: Context): String = TclIdentityPersistence.readOrCreate(context)

    internal fun persistedRdid(
        read: () -> String?, save: (String) -> Unit,
        create: () -> String = { UUID.randomUUID().toString() },
        reread: () -> String? = read
    ): String {
        read()?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = create()
        save(generated)
        return reread()?.takeIf { it.isNotBlank() } ?: generated
    }
}
