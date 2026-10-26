package com.smart.android.ad_app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.FrameLayout
import com.smart.android.ad_app.google.GoogleUrlTclPipeline
import com.smart.android.ad_app.google.GoogleTclUidStore
import com.smart.android.ad_app.google.GoogleGamAdConfigParser
import com.smart.android.ad_app.google.GoogleGamPatchClient
import com.speed.ext.getMacAddress
import okhttp3.Call
import com.smart.android.ad_app.google.GoogleAdUrlBridge
import com.smart.android.ad_app.google.GoogleTclFillBridge
import com.smart.android.ad_app.google.GoogleTclFinalUrlLog
import com.smart.android.tclad.Ad
import com.smart.android.tclad.AdStatusListener
import com.smart.android.tclad.AdType
import com.smart.android.tclad.Controller
import com.tcl.ff.component.overseahttp.http.HttpRequester
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File

/** Explicit, visible device validation. No release source or manifest includes this page. */
class GoogleUrlTclValidationActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var host: FrameLayout
    private lateinit var report: File
    private var request: GoogleAdUrlBridge.RequestHandle? = null
    private var controller: Controller? = null
    private var patchRequest: Call? = null
    private var validationChannelId = ""
    private var originalClient: OkHttpClient? = null
    private var terminal = false
    private var round = 0
    private var validationRequestId = ""
    private var fillWatch: GoogleTclFillBridge.Handle? = null
    private var restoreDisplay: (() -> Unit)? = null
    private val deadline = Runnable { record("timeout"); terminal = true; release() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        val runId = intent.getStringExtra("run_id")?.replace(Regex("[^a-zA-Z0-9_-]"), "_") ?: "latest"
        report = File(filesDir, "google-url-tcl-validation/$runId/events.jsonl").apply {
            parentFile!!.mkdirs()
            writeText("")
        }
        validationRequestId = "validation-$runId"
        validationChannelId = AdChannelResolver.currentChannel()
        Hq008AdReporter.debugReportObserver = { phase, payload, error ->
            record("status_report_$phase", "payload" to JSONObject(payload), "error" to error)
        }
        Hq008ConsentLogReporter.debugUploadObserver = { phase, payload, error ->
            record("log_upload_$phase", "payload" to JSONObject(payload), "error" to error)
        }
        host = FrameLayout(this)
        setContentView(host)
        record("created", "mode" to intent.getStringExtra("mode"))
        main.postDelayed(deadline, 90_000L)
        host.post {
            installCapture()
            if (!intent.getBooleanExtra("url_only", false)) {
                Hq008ConsentLogReporter.updatePopupLogEnabled(true)
                Hq008ConsentLogReporter.report("CMP_GATE_START", "validation=true")
                Hq008ConsentLogReporter.bindAuthorizationRequestId(
                    Hq008ConsentLogReporter.activeRequestId(), validationRequestId
                )
                Hq008ConsentLogReporter.report("AD_PHASE_START", "requestId=$validationRequestId,hidden=false")
            }
            when (intent.getStringExtra("mode")) {
                "manager" -> {
                    // The real channel entry: authorize GAM payload, independent URL generator and TCL.
                    val wasSet = AdDisplayConfig.isRemoteConfigSet()
                    val wasHidden = AdDisplayConfig.isRemoteHiddenMode()
                    val previousUrl = AdDisplayConfig.getRemoteConfigUrl()
                    restoreDisplay = {
                        if (wasSet) AdDisplayConfig.setRemoteHiddenMode(wasHidden)
                        else {
                            AdDisplayConfig.clearRemoteConfig()
                            if (previousUrl.isNotEmpty()) AdDisplayConfig.setRemoteConfigUrl(previousUrl)
                        }
                    }
                    AdDisplayConfig.setRemoteHiddenMode(false)
                    Hq008SdkAuthorizeClient.request(this, AdChannelResolver.currentChannel()) { dto, error ->
                        runOnUiThread {
                            if (error != null || dto?.authorized != true) {
                                finishRun("manager_authorize_failed")
                            } else {
                                GoogleAdTvDesktopAdManager.showAd(
                                    flRoot = host,
                                    adId = dto.request_id,
                                    soundEnabled = dto.sound_mode == true,
                                    callbackTimeoutMs = 80_000L,
                                    gamConfig = dto.gamConfigForPlayback(),
                                    channelId = dto.requestChannelId,
                                    adStart = { record("manager_started") },
                                    adError = { finishRun("manager_error") },
                                    adComplete = { finishRun("manager_completed") }
                                )
                                observeManager()
                            }
                        }
                    }
                }
                else -> {
                    val supplied = intent.getStringExtra("ad_tag_url")
                    if (!supplied.isNullOrBlank()) {
                        when (intent.getStringExtra("mode")) {
                            "tcl_fixture" -> startTcl(supplied)
                            "url_patch_fixture" -> patchFixture(supplied)
                            else -> buildUrl(supplied)
                        }
                    }
                    else Hq008SdkAuthorizeClient.request(this, AdChannelResolver.currentChannel()) { dto, error ->
                        main.post {
                            if (terminal) return@post
                            val config = if (dto?.authorized == true)
                                GoogleGamAdConfigParser.parse(dto.gamConfigForPlayback()) else null
                            if (config == null) finishRun("authorize_config_error", "error" to error)
                            else {
                                validationRequestId = dto!!.request_id
                                validationChannelId = dto.requestChannelId ?: validationChannelId
                                buildUrl(config.adTagUrl)
                            }
                        }
                    }
                }
            }
        }
    }

    // Exercise missing-ID correction without changing the device's advertising/privacy settings.
    private fun patchFixture(generatedUrl: String) {
        val result = GoogleUrlTclPipeline.apply(generatedUrl,
            fallbackUid = { GoogleTclUidStore.readOrCreate(applicationContext) })
        record("url_patch_fixture_result", "url" to result.url, "inputUrl" to generatedUrl,
            "beforeCommonPatchUrl" to result.beforeCommonPatchUrl,
            "commonPatchChanged" to result.commonPatchChanged)
        if (intent.getBooleanExtra("url_only", false)) finishRun("url_only_completed")
        else requestBackendPatch(result.url)
    }

    private fun buildUrl(url: String) {
        round++
        record("url_request", "engine" to GoogleAdUrlBridge.engine, "url" to url, "round" to round)
        request = GoogleAdUrlBridge.buildFinalUrl(host, url, false, intent.getLongExtra("timeout_ms", 45_000L)) { result, error ->
            request = null
            if (terminal) { record("unexpected_late_callback"); return@buildFinalUrl }
            if (result == null) finishRun("url_generation_error", "error" to error)
            else {
                record("generated_url", "engine" to GoogleAdUrlBridge.engine, "url" to result.url, "rawGeneratedUrl" to result.generatedUrl, "userAgent" to result.userAgent,
                    "beforeCommonPatchUrl" to result.beforeCommonPatchUrl,
                    "commonPatchChanged" to result.commonPatchChanged)
                if (intent.getBooleanExtra("url_only", false)) {
                    if (round < intent.getIntExtra("repeat_count", 1)) main.post { buildUrl(url) }
                    else finishRun("url_only_completed")
                } else requestBackendPatch(result.url)
            }
        }
        if (intent.getBooleanExtra("cancel_immediately", false)) {
            patchRequest?.cancel()
            patchRequest = null
            request?.cancel()
            request = null
            terminal = true
            main.removeCallbacks(deadline)
            record("cancelled")
            main.postDelayed({ record("cancel_quiet") }, 2_000L)
        }
    }

    private fun requestBackendPatch(url: String) {
        val parameters = linkedMapOf<String, Any>(
            "request_id" to validationRequestId, "channel_id" to validationChannelId,
            "ad_tag_url" to url, "ad_version" to BuildConfig.VERSION_CODE,
            "mac" to (runCatching { getMacAddress() }.getOrNull() ?: "00:00:00:00:00:00")
        ).apply { Hq008ApiRequestFields.appendTo(this, applicationContext, googleAuthorization = true) }
        record("gam_patch_request", "url" to url)
        patchRequest = GoogleGamPatchClient.shared.request(parameters, 30_000L) { finalUrl, error ->
            main.post {
                if (terminal) return@post
                patchRequest = null
                if (finalUrl == null) finishRun("gam_patch_error", "error" to error)
                else {
                    record("gam_patch_result", "url" to finalUrl)
                    startTcl(finalUrl)
                }
            }
        }
    }

    private fun startTcl(url: String) {
        record("tcl_entry", "url" to url)
        Hq008ConsentLogReporter.report("AD_SDK_REQUEST", "requestId=$validationRequestId",
            GoogleTclFinalUrlLog.json(validationRequestId, url))
        Hq008AdReporter.reportRequested(validationRequestId, validationRequestId, false, host.width, host.height)
        fillWatch = GoogleTclFillBridge.watch(url) { evidence ->
            record("filled_before_parse", "nativeRequestId" to evidence.nativeRequestId)
            Hq008AdReporter.reportFilled(validationRequestId, validationRequestId, false,
                mapOf("fillSource" to "vast_response_before_tcl_parse", "vastResponseLength" to evidence.responseLength))
            Hq008ConsentLogReporter.report("AD_FILLED", "requestId=$validationRequestId")
        }
        controller = Ad.get().begin(applicationContext).lazyLoad()
            .setAdType(AdType.WATERFALL).setPlayerType(0).setPlayerViewType(1).setVolume(0f)
            .setVastUrl(url).listen(object : AdStatusListener {
                override fun onAdLoaded(value: Controller) {
                    record("tcl_loaded")
                    Hq008AdReporter.reportLoaded(validationRequestId, validationRequestId, false)
                    main.post { if (!terminal) { controller = value; value.start(host) } }
                }
                override fun onAdStartPlay() { record("tcl_started") }
                override fun onAdStartPlay(seconds: Double) {
                    record("tcl_started_duration", "seconds" to seconds)
                    Hq008AdReporter.reportStarted(validationRequestId, validationRequestId, false, seconds)
                }
                override fun onAdFinished() { main.post { finishRun("tcl_completed") } }
                override fun onAdError(code: Int) { main.post { finishRun("tcl_error", "code" to code) } }
                override fun onContainerSizeError() { main.post { finishRun("tcl_container_error") } }
            }).start()
    }

    private fun observeManager() {
        if (terminal) return
        try {
            val current = GoogleAdTvDesktopAdManager::class.java.getDeclaredField("current").apply { isAccessible = true }
                .get(GoogleAdTvDesktopAdManager)
            if (current != null) {
                fun field(name: String): Any? = current.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(current)
                if (field("patchReadyAt") != null) {
                    record("manager_generated_url", "engine" to GoogleAdUrlBridge.engine, "sourceUrl" to field("sourceAdurl"), "url" to field("adurl"),
                        "rawGeneratedUrl" to field("generatedAdurl"), "beforeCommonPatchUrl" to field("beforeCommonPatchAdurl"),
                        "localCorrectedUrl" to field("localAdurl"),
                        "commonPatchChanged" to field("commonPatchChanged"))
                    return
                }
            }
        } catch (error: ReflectiveOperationException) {
            record("observe_error", "error" to error.toString())
            return
        }
        main.postDelayed({ observeManager() }, 100L)
    }

    private fun installCapture() {
        val requester = HttpRequester.get()
        val field = HttpRequester::class.java.getDeclaredField("c").apply { isAccessible = true }
        val original = field.get(requester) as OkHttpClient
        originalClient = original
        field.set(requester, original.newBuilder().addInterceptor { chain ->
            val input = chain.request()
            record("tcl_http_request", "method" to input.method, "url" to input.url.toString())
            try {
                val response = chain.proceed(input)
                record("tcl_http_response", "url" to input.url.toString(), "status" to response.code)
                if (response.header("Content-Type").orEmpty().contains("xml", true)) {
                    val body = response.peekBody(1_048_576).string()
                    val xml = File(report.parentFile, "vast-${SystemClock.elapsedRealtime()}.xml")
                    xml.writeText(body)
                    record("vast_copy", "file" to xml.name)
                }
                response
            } catch (error: Exception) {
                record("tcl_http_error", "url" to input.url.toString(), "error" to error.toString())
                throw error
            }
        }.build())
    }

    @Synchronized private fun record(event: String, vararg values: Pair<String, Any?>) {
        val row = JSONObject().put("event", event).put("elapsedMs", SystemClock.elapsedRealtime())
        values.forEach { row.put(it.first, it.second ?: JSONObject.NULL) }
        report.appendText(row.toString() + "\n")
    }

    private fun finishRun(event: String, vararg values: Pair<String, Any?>) {
        if (terminal) return
        record(event, *values)
        if (intent.getStringExtra("mode") != "manager" && !intent.getBooleanExtra("url_only", false)) {
            if (event == "tcl_completed") {
                Hq008AdReporter.reportCompleted(validationRequestId, validationRequestId, false)
                Hq008ConsentLogReporter.report("AD_PHASE_COMPLETED", "requestId=$validationRequestId")
            } else {
                Hq008AdReporter.reportError(validationRequestId, validationRequestId, false,
                    values.firstOrNull { it.first == "code" }?.second as? Int, event)
                Hq008ConsentLogReporter.report("AD_PHASE_ERROR", "requestId=$validationRequestId,reason=$event")
            }
        }
        terminal = true
        main.removeCallbacks(deadline)
        release()
    }

    private fun release() {
        patchRequest?.cancel()
        patchRequest = null
        request?.cancel()
        request = null
        fillWatch?.cancel()
        fillWatch = null
        controller?.release()
        controller = null
        GoogleAdTvDesktopAdManager.destroyAd()
        restoreDisplay?.invoke()
        restoreDisplay = null
    }

    override fun onDestroy() {
        terminal = true
        main.removeCallbacksAndMessages(null)
        release()
        originalClient?.let {
            HttpRequester::class.java.getDeclaredField("c").apply { isAccessible = true }.set(HttpRequester.get(), it)
        }
        Hq008AdReporter.debugReportObserver = null
        Hq008ConsentLogReporter.debugUploadObserver = null
        super.onDestroy()
    }
}
