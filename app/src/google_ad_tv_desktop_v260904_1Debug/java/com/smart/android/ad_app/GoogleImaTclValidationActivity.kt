package com.smart.android.ad_app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.FrameLayout
import com.google.gson.JsonParser
import com.smart.android.ad_app.google.GoogleImaTclUrlPipeline
import com.smart.android.ad_app.google.GoogleTclUidStore
import com.smart.android.ad_app.google.GoogleImaTclSwitch
import com.smart.android.ad_app.google.GoogleGamAdConfigClient
import com.smart.android.ad_app.google.GoogleImaUrlBridge
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
class GoogleImaTclValidationActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var host: FrameLayout
    private lateinit var report: File
    private var request: GoogleImaUrlBridge.RequestHandle? = null
    private var controller: Controller? = null
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
        report = File(filesDir, "ima-tcl-validation/$runId/events.jsonl").apply {
            parentFile!!.mkdirs()
            writeText("")
        }
        validationRequestId = "validation-$runId"
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
                Hq008ConsentLogReporter.report("AD_PHASE_START", "requestId=$validationRequestId,hidden=false")
            }
            when (intent.getStringExtra("mode")) {
                "manager" -> {
                    // The real channel entry: backend resolve, IMA bridge and TCL.
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
                    GoogleAdTvDesktopAdManager.showAd(host, "emulator_validation", false, 80_000L,
                        { record("manager_started") },
                        { finishRun("manager_error") },
                        { finishRun("manager_completed") })
                    observeManager()
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
                    else GoogleGamAdConfigClient.request { config, error ->
                        main.post {
                            if (terminal) return@post
                            if (config == null) finishRun("resolve_error", "error" to error)
                            else buildUrl(config.adTagUrl, config.imaTclUrlPatch)
                        }
                    }
                }
            }
        }
    }

    // Exercise missing-ID correction without changing the device's advertising/privacy settings.
    private fun patchFixture(imaUrl: String) {
        val patch = intent.getStringExtra("ima_tcl_url_patch")
            ?.let { GoogleImaTclSwitch.enabled(JsonParser.parseString(it)) } ?: false
        val result = GoogleImaTclUrlPipeline.apply(
            intent.getStringExtra("original_url") ?: imaUrl, imaUrl, patch, true, true, packageName,
            fallbackUid = { GoogleTclUidStore.readOrCreate(applicationContext) })
        record("url_patch_fixture_result", "url" to result.url, "inputUrl" to imaUrl,
            "beforeCommonPatchUrl" to result.beforeCommonPatchUrl,
            "imaTclUrlPatch" to if (patch) 1 else 0, "commonPatchChanged" to result.commonPatchChanged)
        if (intent.getBooleanExtra("url_only", false)) finishRun("url_only_completed")
        else startTcl(result.url)
    }

    private fun buildUrl(url: String, backendPatch: Boolean = false) {
        val patch = intent.getStringExtra("ima_tcl_url_patch")
            ?.let { GoogleImaTclSwitch.enabled(JsonParser.parseString(it)) } ?: backendPatch
        round++
        record("ima_request", "url" to url, "round" to round, "imaTclUrlPatch" to if (patch) 1 else 0)
        request = GoogleImaUrlBridge.buildFinalUrl(host, url, false, intent.getLongExtra("timeout_ms", 45_000L), patch) { result, error ->
            request = null
            if (terminal) { record("unexpected_late_callback"); return@buildFinalUrl }
            if (result == null) finishRun("ima_error", "error" to error)
            else {
                record("ima_url", "url" to result.url, "rawImaUrl" to result.imaUrl, "userAgent" to result.userAgent,
                    "beforeCommonPatchUrl" to result.beforeCommonPatchUrl,
                    "imaTclUrlPatch" to if (result.tclPatchEnabled) 1 else 0,
                    "commonPatchChanged" to result.commonPatchChanged)
                if (intent.getBooleanExtra("url_only", false)) {
                    if (round < intent.getIntExtra("repeat_count", 1)) main.post { buildUrl(url, patch) }
                    else finishRun("url_only_completed")
                } else startTcl(result.url)
            }
        }
        if (intent.getBooleanExtra("cancel_immediately", false)) {
            request?.cancel()
            request = null
            terminal = true
            main.removeCallbacks(deadline)
            record("cancelled")
            main.postDelayed({ record("cancel_quiet") }, 2_000L)
        }
    }

    private fun startTcl(url: String) {
        record("tcl_entry", "url" to url)
        Hq008ConsentLogReporter.report("AD_SDK_REQUEST", "requestId=$validationRequestId",
            GoogleTclFinalUrlLog.json(validationRequestId, url, false))
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
                if (field("urlBuiltAt") != null) {
                    record("manager_ima_url", "sourceUrl" to field("sourceAdurl"), "url" to field("adurl"),
                        "rawImaUrl" to field("imaAdurl"), "beforeCommonPatchUrl" to field("beforeCommonPatchAdurl"),
                        "imaTclUrlPatch" to if (field("tclPatchEnabled") == true) 1 else 0,
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
