package com.smart.android.ad_app.google

import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import com.smart.android.ad_app.AdLocalLog as Log
import com.smart.android.ad_app.AdPrivacySanitizer
import java.util.concurrent.atomic.AtomicBoolean

/** The independent source set supplies the only URL generator. */
object GoogleAdUrlBridge {
    interface RequestHandle { fun cancel() }
    data class GeneratedUrl(
        val url: String,
        val userAgent: String? = null,
        val diagnostics: Map<String, String> = emptyMap()
    )
    data class FinalUrl(
        val url: String,
        val userAgent: String?,
        val generatedUrl: String,
        val beforeCommonPatchUrl: String,
        val diagnostics: Map<String, String>
    ) {
        val commonPatchChanged: Boolean get() = url != beforeCommonPatchUrl
    }
    interface Generator {
        val engine: String
        fun generate(
            container: ViewGroup, adTagUrl: String, soundEnabled: Boolean, timeoutMs: Long,
            onResult: (GeneratedUrl?, String?) -> Unit
        ): RequestHandle
    }

    private val main = Handler(Looper.getMainLooper())
    val engine: String get() = GoogleUrlEngine.engine

    fun buildFinalUrl(
        container: ViewGroup, adTagUrl: String, soundEnabled: Boolean, timeoutMs: Long,
        onResult: (FinalUrl?, String?) -> Unit
    ): RequestHandle {
        val completed = AtomicBoolean()
        val context = container.context.applicationContext
        val delegate = GoogleUrlEngine.generate(container, adTagUrl, soundEnabled, timeoutMs) { generated, error ->
            // Delivery is always asynchronous and on main, even if a generator fails during setup.
            main.post {
                if (!completed.compareAndSet(false, true)) return@post
                if (generated == null || generated.url.isBlank()) {
                    onResult(null, error ?: "${engine}_empty_generated_url")
                } else {
                    val result = runCatching {
                        val suppressIdentifierFallback =
                            generated.diagnostics["suppressIdentifierFallback"] == "true" ||
                                generated.diagnostics["identifierSuppressed"] == "true"
                        val corrected = GoogleUrlTclPipeline.apply(generated.url,
                            fallbackUid = { GoogleTclUidStore.readOrCreate(context) },
                            suppressIdentifierFallback = suppressIdentifierFallback,
                            reportedSoundEnabled = soundEnabled)
                        FinalUrl(corrected.url, generated.userAgent, generated.url,
                            corrected.beforeCommonPatchUrl, generated.diagnostics)
                    }
                    result.fold(onSuccess = {
                        Log.i("GoogleAdUrl", "URL 生成完成，engine=$engine,urlHash=${AdPrivacySanitizer.shortHash(it.url)},urlLength=${it.url.length}")
                        onResult(it, null)
                    }, onFailure = { onResult(null, "common_url_correction_failed:${it.message}") })
                }
            }
        }
        return object : RequestHandle {
            override fun cancel() {
                completed.set(true)
                delegate.cancel()
            }
        }
    }
}
