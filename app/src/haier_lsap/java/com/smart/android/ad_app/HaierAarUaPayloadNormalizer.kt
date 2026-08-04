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
    "devicetype",
    "device_type",
    "device-type",
    "devicemodel",
    "device_model",
    "device-model",
    "deviceType",
    "phonemodel",
    "phone_model",
    "phone-model",
    "phonetype",
    "phone_type",
    "phone-type",
    "phoneType",
    "dev_model",
    "dev-model",
    "cmodel"
)

private val aarLooseDeviceModelParameterNames = setOf("model")

private val aarBrandParameterNames = setOf(
    "brand",
    "dev_brand",
    "dev-brand",
    "cbrand"
)

private val aarManufacturerParameterNames = setOf(
    "manufacturer",
    "make",
    "devicemake",
    "device_make",
    "device-make"
)

private val aarDeviceCodeParameterNames = setOf(
    "device",
    "devicename",
    "device_name",
    "device-name"
)

private val aarProductParameterNames = setOf(
    "product",
    "dev_name",
    "dev-name",
    "devname"
)

private val aarAndroidVersionParameterNames = setOf(
    "osv",
    "os_v",
    "os_ver",
    "os_version",
    "os-version",
    "osversion",
    "systemversion",
    "system_version",
    "system-version",
    "androidversion",
    "android_version",
    "android-version",
    "androidversionname",
    "android_version_name",
    "android-version-name",
    "androidosversion",
    "android_os_version",
    "android-os-version",
    "androidrelease",
    "android_release",
    "android-release",
    "ver_code"
)

private val aarSdkIntParameterNames = setOf(
    "sdk",
    "sdkint",
    "sdk_int",
    "sdk-int",
    "androidsdk",
    "android_sdk",
    "android-sdk",
    "androidsdkint",
    "android_sdk_int",
    "android-sdk-int",
    "apilevel",
    "api_level",
    "api-level",
    "os_api",
    "os-api"
)

private val aarSystemVersionLabelParameterNames = setOf(
    "sysversion",
    "sys_version",
    "sys-version"
)

private val aarBuildIdParameterNames = setOf(
    "build",
    "buildid",
    "build_id",
    "build-id",
    "androidbuild",
    "android_build",
    "android-build",
    "androidbuildid",
    "android_build_id",
    "android-build-id",
    "romversion",
    "rom_version",
    "rom-version",
    "tclosversion",
    "tcl_os_version",
    "tcl-os-version"
)

private data class AarIdentityProfile(
    val effectiveUa: String,
    val effectiveAndroidVersion: String,
    val effectiveSdkInt: Int,
    val effectiveSystemVersionLabel: String,
    val effectiveDeviceModel: String,
    val effectiveBuildId: String,
    val effectiveBrand: String,
    val effectiveManufacturer: String,
    val effectiveDevice: String,
    val effectiveProduct: String
)

internal fun normalizeAarUrlUa(
    rawUrl: String,
    effectiveUa: String = HaierAarRuntimeBridge.currentEffectiveUa(),
    effectiveAndroidVersion: String = HaierAarRuntimeBridge.getAndroidVersionRelease(),
    effectiveDeviceModel: String = HaierAarRuntimeBridge.getAndroidDeviceModel(),
    effectiveBuildId: String = HaierAarRuntimeBridge.getAndroidBuildId()
): String {
    val queryMarker = rawUrl.indexOf('?')
    if (queryMarker < 0) return rawUrl
    val fragmentMarker = rawUrl.indexOf('#', queryMarker + 1)
        .takeIf { it >= 0 }
        ?: rawUrl.length
    val prefix = rawUrl.substring(0, queryMarker + 1)
    val query = rawUrl.substring(queryMarker + 1, fragmentMarker)
    val suffix = rawUrl.substring(fragmentMarker)
    val profile = aarIdentityProfile(
        effectiveUa = effectiveUa,
        effectiveAndroidVersion = effectiveAndroidVersion,
        effectiveSdkInt = HaierAarRuntimeBridge.getAndroidSdkInt(),
        effectiveDeviceModel = effectiveDeviceModel,
        effectiveBuildId = effectiveBuildId
    )
    val normalized = normalizeEncodedParameters(
        raw = query,
        profile = profile
    )
    return prefix + normalized + suffix
}

internal fun normalizeAarPayloadUa(
    raw: String,
    contentType: String?,
    effectiveUa: String = HaierAarRuntimeBridge.currentEffectiveUa(),
    effectiveAndroidVersion: String = HaierAarRuntimeBridge.getAndroidVersionRelease(),
    effectiveDeviceModel: String = HaierAarRuntimeBridge.getAndroidDeviceModel(),
    effectiveBuildId: String = HaierAarRuntimeBridge.getAndroidBuildId()
): String {
    if (raw.isBlank()) return raw
    val profile = aarIdentityProfile(
        effectiveUa = effectiveUa,
        effectiveAndroidVersion = effectiveAndroidVersion,
        effectiveSdkInt = HaierAarRuntimeBridge.getAndroidSdkInt(),
        effectiveDeviceModel = effectiveDeviceModel,
        effectiveBuildId = effectiveBuildId
    )
    val type = contentType.orEmpty().lowercase()
    if (type.contains("json") || raw.trimStart().startsWith('{') || raw.trimStart().startsWith('[')) {
        return runCatching {
            val root = JsonParser.parseString(raw)
            if (!root.isJsonObject && !root.isJsonArray) return@runCatching raw
            normalizeJsonValue(
                value = root,
                profile = profile
            ).toString()
        }.getOrDefault(raw)
    }
    if (type.contains("x-www-form-urlencoded") || type.contains("form") || looksLikeEncodedParameters(raw)) {
        return normalizeEncodedParameters(
            raw = raw,
            profile = profile
        )
    }
    return raw
}

internal fun normalizeAarHeziPlaintext(
    raw: String,
    effectiveUa: String = HaierAarRuntimeBridge.currentEffectiveUa(),
    effectiveAndroidVersion: String = HaierAarRuntimeBridge.getAndroidVersionRelease(),
    effectiveDeviceModel: String = HaierAarRuntimeBridge.getAndroidDeviceModel(),
    effectiveSdkInt: Int = HaierAarRuntimeBridge.getAndroidSdkInt()
): String {
    if (!raw.contains(";;")) return raw
    val hasTrailingDelimiter = raw.endsWith(";;")
    val parts = raw.split(";;").toMutableList()
    if (parts.size < 18 || !parts[0].startsWith("Dalvik/")) return raw

    val profile = aarIdentityProfile(
        effectiveUa = effectiveUa,
        effectiveAndroidVersion = effectiveAndroidVersion,
        effectiveSdkInt = effectiveSdkInt,
        effectiveDeviceModel = effectiveDeviceModel,
        effectiveBuildId = HaierAarRuntimeBridge.getAndroidBuildId()
    )
    parts[0] = effectiveUa
    parts[4] = profile.effectiveManufacturer
    parts[5] = profile.effectiveBrand
    parts[6] = effectiveDeviceModel
    parts[8] = effectiveAndroidVersion
    parts[9] = profile.effectiveSdkInt.toString()
    parts[16] = effectiveUa

    val joined = parts.joinToString(";;")
    return if (hasTrailingDelimiter && !joined.endsWith(";;")) {
        "$joined;;"
    } else {
        joined
    }
}

private fun normalizeEncodedParameters(
    raw: String,
    profile: AarIdentityProfile
): String {
    if (raw.isEmpty()) return raw
    return raw.split('&').joinToString("&") { parameter ->
        val equals = parameter.indexOf('=')
        if (equals < 0) return@joinToString parameter
        val encodedName = parameter.substring(0, equals)
        val encodedValue = parameter.substring(equals + 1)
        val decodedName = runCatching {
            URLDecoder.decode(encodedName, Charsets.UTF_8.name())
        }.getOrDefault(encodedName)
        val decodedValue = runCatching {
            URLDecoder.decode(encodedValue, Charsets.UTF_8.name())
        }.getOrDefault(encodedValue)
        val normalizedValue = normalizedIdentityValueForName(
            name = decodedName,
            observedValue = decodedValue,
            profile = profile,
            insideDeviceObject = false,
            depth = 0
        )
        if (normalizedValue == null || normalizedValue == decodedValue) {
            parameter
        } else {
            encodedName + "=" + encodeQueryValue(normalizedValue)
        }
    }
}

private fun normalizeJsonValue(
    value: JsonElement?,
    profile: AarIdentityProfile,
    insideDeviceObject: Boolean = false,
    depth: Int = 0
): JsonElement? {
    when {
        value == null || value.isJsonNull -> Unit
        value.isJsonObject -> {
            val jsonObject = value.asJsonObject
            jsonObject.entrySet().toList().forEach { (key, child) ->
                val observedValue = child.asJsonStringOrNull()
                val normalizedValue = observedValue?.let {
                    normalizedIdentityValueForName(
                        name = key,
                        observedValue = it,
                        profile = profile,
                        insideDeviceObject = insideDeviceObject,
                        depth = depth
                    )
                }
                if (normalizedValue != null && normalizedValue != observedValue) {
                    jsonObject.addProperty(key, normalizedValue)
                } else {
                    normalizeJsonValue(
                        value = child,
                        profile = profile,
                        insideDeviceObject = insideDeviceObject || key.equals("device", ignoreCase = true),
                        depth = depth + 1
                    )
                }
            }
        }

        value.isJsonArray -> {
            val jsonArray = value.asJsonArray
            for (index in 0 until jsonArray.size()) {
                normalizeJsonValue(
                    jsonArray[index],
                    profile,
                    insideDeviceObject,
                    depth
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

private fun isAarLooseDeviceModelParameter(name: String): Boolean {
    return aarLooseDeviceModelParameterNames.contains(name.trim().lowercase())
}

private fun isAarAndroidVersionParameter(name: String): Boolean {
    return aarAndroidVersionParameterNames.contains(name.trim().lowercase())
}

private fun isAarSdkIntParameter(name: String): Boolean {
    return aarSdkIntParameterNames.contains(name.trim().lowercase())
}

private fun isAarSystemVersionLabelParameter(name: String): Boolean {
    return aarSystemVersionLabelParameterNames.contains(name.trim().lowercase())
}

private fun isAarBuildIdParameter(name: String): Boolean {
    return aarBuildIdParameterNames.contains(name.trim().lowercase())
}

private fun isAarBrandParameter(name: String): Boolean {
    return aarBrandParameterNames.contains(name.trim().lowercase())
}

private fun isAarManufacturerParameter(name: String): Boolean {
    return aarManufacturerParameterNames.contains(name.trim().lowercase())
}

private fun isAarDeviceCodeParameter(name: String): Boolean {
    return aarDeviceCodeParameterNames.contains(name.trim().lowercase())
}

private fun isAarProductParameter(name: String): Boolean {
    return aarProductParameterNames.contains(name.trim().lowercase())
}

private fun isDeviceReleaseParameter(name: String, insideDeviceObject: Boolean): Boolean {
    return insideDeviceObject && name.trim().equals("release", ignoreCase = true)
}

private fun looksLikeEncodedParameters(raw: String): Boolean {
    if (!raw.contains('=')) return false
    val name = raw.substringBefore('=')
    if (name.isBlank() || name.any { it <= ' ' || it == '&' || it == '?' }) return false
    return true
}

private fun encodeQueryValue(value: String): String {
    return URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}

private fun normalizedIdentityValueForName(
    name: String,
    observedValue: String,
    profile: AarIdentityProfile,
    insideDeviceObject: Boolean,
    depth: Int
): String? {
    return when {
        isAarUaParameter(name) -> profile.effectiveUa
        isAarSystemVersionLabelParameter(name) -> profile.effectiveSystemVersionLabel
        isAarSdkIntParameter(name) -> profile.effectiveSdkInt.toString()
        isAarAndroidVersionParameter(name) || isDeviceReleaseParameter(name, insideDeviceObject) ->
            profile.effectiveAndroidVersion

        isAarDeviceModelParameter(name) -> profile.effectiveDeviceModel
        isAarLooseDeviceModelParameter(name) &&
            (insideDeviceObject || depth == 0) &&
            shouldNormalizeLooseModelParameter(observedValue) -> profile.effectiveDeviceModel

        isAarBuildIdParameter(name) ||
            ((insideDeviceObject || depth == 0) && name.equals("build", ignoreCase = true)) ->
            profile.effectiveBuildId

        isAarBrandParameter(name) -> profile.effectiveBrand
        isAarManufacturerParameter(name) -> profile.effectiveManufacturer
        isAarDeviceCodeParameter(name) -> profile.effectiveDevice
        isAarProductParameter(name) -> profile.effectiveProduct
        else -> null
    }
}

private fun shouldNormalizeLooseModelParameter(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return true
    if (trimmed.all(Char::isDigit)) return false
    if (trimmed.length <= 4 && trimmed.all { it.isDigit() || it == '.' }) return false
    return HaierDeviceModelNormalizer.isGeneric(trimmed) || trimmed.any(Char::isLetter)
}

private fun aarIdentityProfile(
    effectiveUa: String,
    effectiveAndroidVersion: String,
    effectiveSdkInt: Int,
    effectiveDeviceModel: String,
    effectiveBuildId: String
): AarIdentityProfile {
    return AarIdentityProfile(
        effectiveUa = effectiveUa,
        effectiveAndroidVersion = effectiveAndroidVersion,
        effectiveSdkInt = effectiveSdkInt,
        effectiveSystemVersionLabel = "Android : $effectiveAndroidVersion",
        effectiveDeviceModel = effectiveDeviceModel,
        effectiveBuildId = effectiveBuildId,
        effectiveBrand = HaierAarRuntimeBridge.getAndroidBrand(),
        effectiveManufacturer = HaierAarRuntimeBridge.getAndroidManufacturer(),
        effectiveDevice = HaierAarRuntimeBridge.getAndroidDevice(),
        effectiveProduct = HaierAarRuntimeBridge.getAndroidProduct()
    )
}

private fun JsonElement.asJsonStringOrNull(): String? {
    if (!isJsonPrimitive || !asJsonPrimitive.isString) return null
    return asString
}
