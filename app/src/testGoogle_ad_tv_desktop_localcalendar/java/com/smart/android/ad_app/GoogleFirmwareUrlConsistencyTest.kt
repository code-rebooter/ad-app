package com.smart.android.ad_app

import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder
import java.util.Locale

class GoogleFirmwareUrlConsistencyTest {
    private val ua = "Dalvik/2.1.0 (Linux; U; Android 10; Sony BRAVIA Build/QP1A.191105.004)"
    private fun correct(url: String) = normalizeAarUrlUa(url, ua, "10", "Sony BRAVIA", "QP1A.191105.004")
    private fun query(url: String): Map<String, String> = url.substringAfter('?').substringBefore('#')
        .split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }

    @Test fun `all firmware field families share the same effective sources without editing signals`() {
        val raw = "https://example.test/ads?ua=old&osv=15&sdk_int=-1&device_model=mstar&build_id=old" +
            "&brand=old&make=old&device_name=old&product=old&sysversion=Android15" +
            "&ms=opaque%2Bsignal&uach=opaque%3D&sdkv=declared&custom=one&custom=two#tail"
        val corrected = correct(raw)
        val fields = query(corrected)
        assertEquals(ua, fields["ua"])
        assertEquals("10", fields["osv"])
        assertEquals("Sony BRAVIA", fields["device_model"])
        assertEquals("QP1A.191105.004", fields["build_id"])
        assertEquals(Hq008XhsxAarRuntimeBridge.getAndroidSdkInt().toString(), fields["sdk_int"])
        assertEquals(Hq008XhsxAarRuntimeBridge.getAndroidBrand(), fields["brand"])
        assertEquals(Hq008XhsxAarRuntimeBridge.getAndroidManufacturer(), fields["make"])
        assertEquals(Hq008XhsxAarRuntimeBridge.getAndroidDevice(), fields["device_name"])
        assertEquals(Hq008XhsxAarRuntimeBridge.getAndroidProduct(), fields["product"])
        assertEquals("Android : 10", fields["sysversion"])
        assertTrue(corrected.endsWith("&ms=opaque%2Bsignal&uach=opaque%3D&sdkv=declared&custom=one&custom=two#tail"))
        assertEquals(corrected, correct(corrected))
    }

    @Test fun `Turkish device language does not skip uppercase identity parameter aliases`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val result = query(correct("https://example.test/ads?ANDROID_VERSION=15&BUILD_ID=old&DEVICE_MODEL=mstar&DEVICE_NAME=old"))
            assertEquals("10", result["ANDROID_VERSION"])
            assertEquals("QP1A.191105.004", result["BUILD_ID"])
            assertEquals("Sony BRAVIA", result["DEVICE_MODEL"])
            assertEquals(Hq008XhsxAarRuntimeBridge.getAndroidDevice(), result["DEVICE_NAME"])
        } finally { Locale.setDefault(previous) }
    }

    @Test fun `empty present identity fields are repaired but a fragment is not treated as a query`() {
        val corrected = query(correct("https://example.test/ads?ua&build_id&osv&device_model&custom#tail"))
        assertEquals(ua, corrected["ua"])
        assertEquals("QP1A.191105.004", corrected["build_id"])
        assertEquals("10", corrected["osv"])
        assertEquals("Sony BRAVIA", corrected["device_model"])
        val fragment = "https://example.test/#fragment?ua=unrelated&build_id=unrelated"
        assertEquals(fragment, correct(fragment))
    }
    @Test fun `independent field and nested reflection reads share the native corrected getters`() {
        val corrections = try { Class.forName("io.codex.imarepro.FirmwareInputCorrections") }
            catch (_: ClassNotFoundException) { org.junit.Assume.assumeTrue("Independent engine only", false); return }
        val read = corrections.getMethod("readField", java.lang.reflect.Field::class.java, Any::class.java)
        val invoke = corrections.getMethod("invoke", java.lang.reflect.Method::class.java, Any::class.java, Array<Any>::class.java)
        val get = java.lang.reflect.Field::class.java.getMethod("get", Any::class.java)
        val nestedInvoke = java.lang.reflect.Method::class.java.getMethod("invoke", Any::class.java, Array<Any>::class.java)
        val cases = listOf(
            android.os.Build::class.java.getField("MODEL") to Hq008XhsxAarRuntimeBridge.getAndroidDeviceModel(),
            android.os.Build::class.java.getField("ID") to Hq008XhsxAarRuntimeBridge.getAndroidBuildId(),
            android.os.Build::class.java.getField("BRAND") to Hq008XhsxAarRuntimeBridge.getAndroidBrand(),
            android.os.Build::class.java.getField("MANUFACTURER") to Hq008XhsxAarRuntimeBridge.getAndroidManufacturer(),
            android.os.Build::class.java.getField("DEVICE") to Hq008XhsxAarRuntimeBridge.getAndroidDevice(),
            android.os.Build::class.java.getField("PRODUCT") to Hq008XhsxAarRuntimeBridge.getAndroidProduct(),
            android.os.Build.VERSION::class.java.getField("RELEASE") to Hq008XhsxAarRuntimeBridge.getAndroidVersionRelease()
        )
        for ((field, expected) in cases) {
            assertEquals(field.name, expected, read.invoke(null, field, null))
            assertEquals(field.name, expected, invoke.invoke(null, get, field, arrayOfNulls<Any>(1)))
            assertEquals(field.name, expected, invoke.invoke(null, nestedInvoke, get, arrayOf(field, arrayOfNulls<Any>(1))))
        }
    }
    @Test fun `fingerprint reflection and process preflight never fall back to invalid raw input`() {
        val corrections = try { Class.forName("io.codex.imarepro.FirmwareInputCorrections") }
            catch (_: ClassNotFoundException) { org.junit.Assume.assumeTrue("Independent engine only", false); return }
        val expected = runCatching { Hq008XhsxAarRuntimeBridge.getAndroidBuildFingerprint() }
        val field = android.os.Build::class.java.getField("FINGERPRINT")
        val read = corrections.getMethod("readField", java.lang.reflect.Field::class.java, Any::class.java)
        val invoke = corrections.getMethod("invoke", java.lang.reflect.Method::class.java, Any::class.java, Array<Any>::class.java)
        val get = java.lang.reflect.Field::class.java.getMethod("get", Any::class.java)
        val nested = java.lang.reflect.Method::class.java.getMethod("invoke", Any::class.java, Array<Any>::class.java)
        fun root(error: Throwable): Throwable = error.cause?.let(::root) ?: error
        val calls = listOf<() -> Any?>(
            { corrections.getMethod("fingerprint").invoke(null) },
            { read.invoke(null, field, null) },
            { invoke.invoke(null, get, field, arrayOfNulls<Any>(1)) },
            { invoke.invoke(null, nested, get, arrayOf(field, arrayOfNulls<Any>(1))) }
        )
        for (call in calls) {
            val result = runCatching(call)
            if (expected.isSuccess) assertEquals(expected.getOrThrow(), result.getOrThrow())
            else assertEquals(expected.exceptionOrNull()!!.message, root(result.exceptionOrNull() ?: error("Unexpected raw fingerprint fallback")).message)
        }
        val preflight = runCatching { corrections.getMethod("requireHostPolicy").invoke(null) }
        if (expected.isFailure) assertEquals(expected.exceptionOrNull()!!.message,
            root(preflight.exceptionOrNull() ?: error("Invalid fingerprint bypassed preflight")).message)
        else assertTrue(preflight.isSuccess)
    }

}
