package com.smart.android.ad_app.google

import com.google.gson.JsonElement
import java.math.BigDecimal

/** The API uses 0/1; tolerate string and boolean encodings without Gson Boolean coercion. */
internal object GoogleImaTclSwitch {
    fun enabled(value: JsonElement?): Boolean {
        if (value == null || !value.isJsonPrimitive) return false
        val primitive = value.asJsonPrimitive
        return when {
            primitive.isBoolean -> primitive.asBoolean
            primitive.isNumber -> runCatching {
                primitive.asBigDecimal.compareTo(BigDecimal.ONE) == 0
            }.getOrDefault(false)
            primitive.isString -> primitive.asString.trim().let { it == "1" || it.equals("true", true) }
            else -> false
        }
    }
}
