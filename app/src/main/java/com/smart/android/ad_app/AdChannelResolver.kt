package com.smart.android.ad_app

import android.util.Log
import java.io.BufferedReader

internal object AdChannelResolver {
    private const val TAG = "AdChannelResolver"
    private const val CHANNEL_PROPERTY_KEY = "persist.vendor.ad.channel"
    private const val SOURCE_SYSTEM_PROPERTY = "system_property"
    private const val SOURCE_BUILD_CONFIG = "build_config"

    data class ResolvedChannel(
        val value: String,
        val source: Source
    )

    enum class Source(val label: String) {
        SYSTEM_PROPERTY(SOURCE_SYSTEM_PROPERTY),
        BUILD_CONFIG(SOURCE_BUILD_CONFIG)
    }

    fun currentChannel(): String = resolve().value

    fun currentChannelSource(): String = resolve().source.label

    fun resolve(): ResolvedChannel {
        val propertyValue = normalizeChannel(readSystemProperty(CHANNEL_PROPERTY_KEY))
        if (propertyValue != null) {
            return ResolvedChannel(
                value = propertyValue,
                source = Source.SYSTEM_PROPERTY
            )
        }
        return ResolvedChannel(
            value = BuildConfig.CHANNEL,
            source = Source.BUILD_CONFIG
        )
    }

    internal fun normalizeChannel(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }

    private fun readSystemProperty(key: String): String? {
        return readSystemPropertyReflective(key) ?: readSystemPropertyViaGetprop(key)
    }

    private fun readSystemPropertyReflective(key: String): String? {
        return runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val getMethod = clazz.getMethod("get", String::class.java)
            getMethod.invoke(null, key) as? String
        }.onFailure { error ->
            Log.w(TAG, "读取系统属性失败，改走 getprop，key=$key，error=${error.message}")
        }.getOrNull()
    }

    private fun readSystemPropertyViaGetprop(key: String): String? {
        return runCatching {
            val process = ProcessBuilder("/system/bin/getprop", key)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use(BufferedReader::readLine)
        }.onFailure { error ->
            Log.w(TAG, "通过 getprop 读取系统属性失败，key=$key，error=${error.message}")
        }.getOrNull()
    }
}
