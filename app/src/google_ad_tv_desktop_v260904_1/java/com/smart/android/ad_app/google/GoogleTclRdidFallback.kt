package com.smart.android.ad_app.google

import java.net.URLDecoder
import java.net.URLEncoder

/** Final URL correction; untouched query pieces retain their original bytes and order. */
internal object GoogleTclRdidFallback {
    private val noIdentifierFlags = setOf("is_lat", "ltd", "tfcd", "tfua", "dnt")

    fun apply(url: String, uid: () -> String): String {
        val hash = url.indexOf('#')
        val fragment = if (hash < 0) "" else url.substring(hash)
        val head = if (hash < 0) url else url.substring(0, hash)
        val question = head.indexOf('?')
        val path = if (question < 0) head else head.substring(0, question)
        val query = if (question < 0) "" else head.substring(question + 1)
        val pairs = if (query.isEmpty()) emptyList() else query.split('&')
        fun key(pair: String) = decode(pair.substringBefore('='))
        fun value(pair: String) = decode(pair.substringAfter('=', ""))

        // A missing ID is different from the platform explicitly withholding identifiers.
        if (pairs.any { key(it) in noIdentifierFlags && value(it).trim().let { v ->
                v == "1" || v.equals("true", ignoreCase = true)
            } }) return url
        if (pairs.any { key(it) == "rdid" && value(it).isNotBlank() }) return url

        val fallback = uid().takeIf { it.isNotBlank() } ?: return url
        val replacement = "rdid=" + URLEncoder.encode(fallback, "UTF-8")
        var wroteRdid = false
        var wroteType = false
        val corrected = mutableListOf<String>()
        for (pair in pairs) {
            when (key(pair)) {
                "rdid" -> if (!wroteRdid) { corrected.add(replacement); wroteRdid = true }
                // Match TCL's original type when filling its locally generated, persisted UID.
                "idtype" -> if (!wroteType) { corrected.add("idtype=aaid"); wroteType = true }
                else -> corrected.add(pair)
            }
        }
        if (!wroteRdid) corrected.add(replacement)
        if (!wroteType) corrected.add("idtype=aaid")
        return path + "?" + corrected.joinToString("&") + fragment
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: IllegalArgumentException) {
        value
    }
}
