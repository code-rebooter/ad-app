package com.smart.android.ad_app

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.net.URLDecoder
import java.net.URLEncoder

private val aarUaParameterNames = setOf(
    "ua",
    "useragent",
    "user_agent",
    "user-agent",
    "af_ua",
    "webview_ua",
    "http.agent"
)

private val aarDeviceModelParameterNames = setOf(
    "devicemodel",
    "device_model",
    "device-model"
)

private val aarAndroidVersionParameterNames = setOf(
    "osv",
    "os_version",
    "os-version",
    "osversion",
    "androidversion",
    "android_version",
    "android-version",
    "androidrelease",
    "android_release",
    "android-release"
)

internal fun normalizeAarUrlUa(
    rawUrl: String,
    effectiveUa: String = HaierAarRuntimeBridge.currentEffectiveUa(),
    effectiveAndroidVersion: String = HaierAarRuntimeBridge.getAndroidVersionRelease()
): String {
    val queryMarker = rawUrl.indexOf('?')
    if (queryMarker < 0) return rawUrl
    val fragmentMarker = rawUrl.indexOf('#', queryMarker + 1)
        .takeIf { it >= 0 }
        ?: rawUrl.length
    val prefix = rawUrl.substring(0, queryMarker + 1)
    val query = rawUrl.substring(queryMarker + 1, fragmentMarker)
    val suffix = rawUrl.substring(fragmentMarker)
    val normalized = normalizeEncodedParameters(query, effectiveUa, effectiveAndroidVersion)
    return prefix + normalized + suffix
}

internal fun normalizeAarPayloadUa(
    raw: String,
    contentType: String?,
    effectiveUa: String = HaierAarRuntimeBridge.currentEffectiveUa(),
    effectiveAndroidVersion: String = HaierAarRuntimeBridge.getAndroidVersionRelease()
): String {
    if (raw.isBlank()) return raw
    val type = contentType.orEmpty().lowercase()
    if (type.contains("json") || raw.trimStart().startsWith('{') || raw.trimStart().startsWith('[')) {
        return runCatching {
            val root = JsonParser.parseString(raw)
            if (!root.isJsonObject && !root.isJsonArray) return@runCatching raw
            normalizeJsonValue(root, effectiveUa, effectiveAndroidVersion).toString()
        }.getOrDefault(raw)
    }
    if (type.contains("x-www-form-urlencoded") || type.contains("form")) {
        return normalizeEncodedParameters(raw, effectiveUa, effectiveAndroidVersion)
    }
    return raw
}

private fun normalizeEncodedParameters(
    raw: String,
    effectiveUa: String,
    effectiveAndroidVersion: String
): String {
    if (raw.isEmpty()) return raw
    return raw.split('&').joinToString("&") { parameter ->
        val equals = parameter.indexOf('=')
        if (equals < 0) return@joinToString parameter
        val encodedName = parameter.substring(0, equals)
        val decodedName = runCatching {
            URLDecoder.decode(encodedName, Charsets.UTF_8.name())
        }.getOrDefault(encodedName)
        when {
            isAarUaParameter(decodedName) -> {
                encodedName + "=" + encodeQueryValue(effectiveUa)
            }

            isAarAndroidVersionParameter(decodedName) -> {
                encodedName + "=" + encodeQueryValue(effectiveAndroidVersion)
            }

            isAarDeviceModelParameter(decodedName) -> {
                val encodedValue = parameter.substring(equals + 1)
                val decodedValue = runCatching {
                    URLDecoder.decode(encodedValue, Charsets.UTF_8.name())
                }.getOrDefault(encodedValue)
                val normalizedModel = HaierDeviceModelNormalizer.normalize(decodedValue)
                if (normalizedModel == decodedValue) {
                    parameter
                } else {
                    encodedName + "=" + encodeQueryValue(normalizedModel)
                }
            }

            else -> parameter
        }
    }
}

private fun normalizeJsonValue(
    value: JsonElement?,
    effectiveUa: String,
    effectiveAndroidVersion: String,
    insideDeviceObject: Boolean = false
): JsonElement? {
    when {
        value == null || value.isJsonNull -> Unit
        value.isJsonObject -> {
            val jsonObject = value.asJsonObject
            jsonObject.entrySet().toList().forEach { (key, child) ->
                if (isAarUaParameter(key)) {
                    jsonObject.addProperty(key, effectiveUa)
                } else if (isAarAndroidVersionParameter(key) || isDeviceReleaseParameter(key, insideDeviceObject)) {
                    jsonObject.addProperty(key, effectiveAndroidVersion)
                } else if (
                    isAarDeviceModelParameter(key) ||
                    (insideDeviceObject && key.equals("model", ignoreCase = true))
                ) {
                    val originalModel = child
                        .takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                        ?.asString
                    if (originalModel != null && HaierDeviceModelNormalizer.isGeneric(originalModel)) {
                        jsonObject.addProperty(
                            key,
                            HaierDeviceModelNormalizer.normalize(originalModel)
                        )
                    }
                } else {
                    normalizeJsonValue(
                        value = child,
                        effectiveUa = effectiveUa,
                        effectiveAndroidVersion = effectiveAndroidVersion,
                        insideDeviceObject = key.equals("device", ignoreCase = true)
                    )
                }
            }
        }

        value.isJsonArray -> {
            val jsonArray = value.asJsonArray
            for (index in 0 until jsonArray.size()) {
                normalizeJsonValue(
                    jsonArray[index],
                    effectiveUa,
                    effectiveAndroidVersion,
                    insideDeviceObject
                )
            }
        }
    }
    return value
}

private fun isAarUaParameter(name: String): Boolean {
    return aarUaParameterNames.contains(name.trim().lowercase())
}

private fun isAarDeviceModelParameter(name: String): Boolean {
    return aarDeviceModelParameterNames.contains(name.trim().lowercase())
}

private fun isAarAndroidVersionParameter(name: String): Boolean {
    return aarAndroidVersionParameterNames.contains(name.trim().lowercase())
}

private fun isDeviceReleaseParameter(name: String, insideDeviceObject: Boolean): Boolean {
    return insideDeviceObject && name.trim().equals("release", ignoreCase = true)
}

private fun encodeQueryValue(value: String): String {
    return URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
