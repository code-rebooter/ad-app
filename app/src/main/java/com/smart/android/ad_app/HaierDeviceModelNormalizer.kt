package com.smart.android.ad_app

import java.util.Locale

internal object HaierDeviceModelNormalizer {
    const val FIXED_MODEL = "X96_NEXT"

    private val exactGenericModels = setOf(
        "tv",
        "box",
        "stb",
        "ott",
        "aosp",
        "generic",
        "unknown",
        "mstar",
        "walley",
        "walleye"
    )

    private val genericModelMarkers = setOf(
        "tvbox",
        "androidtv",
        "androidbox",
        "smarttv",
        "tvstick",
        "tvdongle",
        "settopbox",
        "ottbox"
    )

    fun isGeneric(model: String?): Boolean {
        val compact = model.orEmpty()
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]"), "")
        if (compact.isBlank()) return true
        return compact in exactGenericModels ||
            genericModelMarkers.any(compact::contains)
    }

    fun normalize(model: String?): String {
        return if (isGeneric(model)) FIXED_MODEL else model.orEmpty()
    }
}
