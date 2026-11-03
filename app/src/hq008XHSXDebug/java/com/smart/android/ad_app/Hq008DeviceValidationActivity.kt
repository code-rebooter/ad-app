package com.smart.android.ad_app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.TextView
import com.smart.android.ad_app.bean.AdConfigDto
import com.tcl.ff.component.overseahttp.http.HttpRequester
import com.tcl.ff.component.vastad.Controller
import com.tcl.ff.component.vastad.Initialization
import com.tcl.ff.component.vastad.core.callbacks.AdStatusListener
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.smart.android.ad_app.google.GoogleGamPatchClient
import com.google.gson.Gson
import okio.Buffer
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/** Debug-only on-device checks. Fixture traffic is explicit and never used by formal ad requests. */
class Hq008DeviceValidationActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var reportFile: File
    private lateinit var status: TextView
    private lateinit var host: FrameLayout
    private val nativeClass by lazy { Class.forName("com.smart.android.ad_app.Hq008TclVideoAd") }
    private val native by lazy { nativeClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)!! }
    private var restore: (() -> Unit)? = null
    private var networkRestore: (() -> Unit)? = null
    private var fixtureCompleted = false
    private var fixtureId = ""
    private var fixtureError = false
    private var fixturePatchCount = 0
    private val fixtureEvents = mutableListOf<String>()
    private var fixtureUploadCount = 0
    private var fixtureAudit: JSONObject? = null
    private var finished = false
    private val reportObserver: (String, Map<String, Any>, String?) -> Unit = { phase, payload, error ->
        record("ad_report", "phase" to phase, "payload" to JSONObject(payload), "error" to error)
    }
    private val traceListener = Hq008ConsentLogReporter.DebugTraceListener { event, message ->
        record(event, "message" to message)
        fixtureEvents += event
        if (event == "AD_PHASE_COMPLETED" && message.contains(fixtureId)) fixtureCompleted = true
        if (event == "AD_PHASE_ERROR" && message.contains(fixtureId)) fixtureError = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runId = intent.getStringExtra("run_id").orEmpty().replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "latest" }
        reportFile = File(filesDir, "device-validation/$runId.jsonl").apply { parentFile!!.mkdirs(); writeText("") }
        host = FrameLayout(this)
        status = TextView(this).apply { textSize = 22f; setPadding(24, 24, 24, 24); text = "Running XHSX device checks…" }
        host.addView(status)
        setContentView(host)
        HandlerAdTaskScheduler.shutdown()
        Hq008ConsentLogReporter.addDebugTraceListener(traceListener)
        Hq008AdReporter.debugReportObserver = reportObserver
        record("begin", "mode" to intent.getStringExtra("mode"), "sdkReady" to Initialization.isHasInit())
        host.post { safely {
            if (intent.getStringExtra("mode") == "fixture") fixture()
            else {
                ensureFixtureInitialization()
                installPendingLifecycleFixture()
                cancelQueued()
            }
        } }
    }

    private fun record(event: String, vararg details: Pair<String, Any?>) {
        if (!::reportFile.isInitialized) return
        val json = JSONObject().put("event", event).put("elapsedMs", SystemClock.elapsedRealtime())
        details.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        synchronized(this) { reportFile.appendText("$json\n") }
        AdLocalLog.i("Hq008DeviceCheck", json.toString())
    }

    private fun safely(action: () -> Unit) {
        runCatching(action).onFailure { error ->
            record("FAIL", "error" to error.stackTraceToString())
            status.text = "FAIL: ${error.message}"
            finished = true
            restore?.invoke(); restore = null
            AdManagerImpl.destroyAd()
        }
    }

    private fun later(delay: Long = 0L, action: () -> Unit) {
        main.postDelayed({ if (!finished) safely(action) }, delay)
    }

    private fun checkCase(name: String, condition: Boolean) {
        record(if (condition) "PASS" else "FAIL", "case" to name)
        check(condition) { name }
    }

    private fun field(target: Any, name: String): Field = generateSequence(target.javaClass) { it.superclass }
        .flatMap { it.declaredFields.asSequence() }.first { it.name == name }.apply { isAccessible = true }
    private fun get(target: Any, name: String): Any? = field(target, name).get(target)
    private fun current(): Any? = get(native, "currentRequest")
    private fun player(request: Any): Controller = get(get(request, "controllerLease")!!, "controller") as Controller
    private fun config(controller: Controller): Any {
        val core = Hq008TclControllerIdentity.of(controller)
        return generateSequence(core.javaClass) { it.superclass }.flatMap { it.declaredFields.asSequence() }
            .first { it.type.name == "com.tcl.ff.component.vastad.core.b" }.apply { isAccessible = true }.get(core)!!
    }
    private fun listener(config: Any): AdStatusListener = config.javaClass.declaredMethods
        .single { it.parameterTypes.isEmpty() && it.returnType == AdStatusListener::class.java }
        .apply { isAccessible = true }.invoke(config) as AdStatusListener
    private fun silence(config: Any) {
        config.javaClass.declaredFields.single { it.type == AdStatusListener::class.java }
            .apply { isAccessible = true }.set(config, object : AdStatusListener {
                override fun onAdLoaded(controller: Controller) {}
                override fun onAdStartPlay() {}
                override fun onAdStartPlay(progress: Double) {}
                override fun onAdFinished() {}
                override fun onAdError(errorCode: Int) {}
                override fun onContainerSizeError() {}
            })
    }

    private fun cancelQueued() {
        AdRenderer.stopFloatingAd("device_validation")
        AdManagerImpl.destroyAd()
        Hq008FloatingFlowGuard.cancelActive("device_validation")
        var callbacks = 0
        AdManagerImpl.showAd(host, "device125-queued", false, 5_000L,
            { callbacks++ }, { callbacks++ }, { callbacks++ })
        AdManagerImpl.destroyAd("device125-queued")
        later(100) {
            checkCase("cancel_before_queued_start", current() == null && callbacks == 0)
            initDeadline()
        }
    }

    private fun initDeadline() {
        val sdkFlag = Initialization::class.java.declaredFields.single {
            Modifier.isStatic(it.modifiers) && it.type == java.lang.Boolean.TYPE
        }.apply { isAccessible = true }
        val ready = sdkFlag.getBoolean(null)
        val completed = get(native, "initCompleted")
        val requested = get(native, "initRequested")
        restore = {
            sdkFlag.setBoolean(null, ready)
            field(native, "initCompleted").set(native, completed)
            field(native, "initRequested").set(native, requested)
        }
        sdkFlag.setBoolean(null, false)
        field(native, "initCompleted").setBoolean(native, false)
        field(native, "initRequested").setBoolean(native, true)
        var errors = 0
        val start = SystemClock.elapsedRealtime()
        AdManagerImpl.showAd(host, "device125-init-timeout", false, 800L, null, { errors++ }, {})
        later(1_100) {
            val elapsed = SystemClock.elapsedRealtime() - start
            checkCase("initialization_in_total_deadline", errors == 1 && current() == null && get(native, "pendingRequest") == null && elapsed < 2_000L)
            restore?.invoke(); restore = null
            checkCase("real_sdk_initialized", Initialization.isHasInit())
            staleCallbacks()
        }
    }

    private fun staleCallbacks() {
        var oldCallbacks = 0
        AdManagerImpl.showAd(host, "device125-old", false, 10_000L,
            { oldCallbacks++ }, { oldCallbacks++ }, { oldCallbacks++ })
        later {
            val oldRequest = current()!!
            val oldController = player(oldRequest)
            val oldConfig = config(oldController)
            val oldListener = listener(oldConfig)
            silence(oldConfig)
            AdManagerImpl.destroyAd("device125-old")
            var newErrors = 0
            AdManagerImpl.showAd(host, "device125-new", false, 2_500L, null, { newErrors++ }, {})
            later {
                val newRequest = current()!!
                val timer = get(newRequest, "timeoutRunnable")
                val newController = player(newRequest)
                val newConfig = config(newController)
                silence(newConfig)
                oldListener.onAdLoaded(oldController)
                oldListener.onAdStartPlay()
                oldListener.onAdStartPlay(0.0)
                oldListener.onAdFinished()
                oldListener.onAdError(999)
                oldListener.onContainerSizeError()
                AdManagerImpl.destroyAd("device125-old")
                later {
                    checkCase("stale_callbacks_keep_new_request_and_timer", current() === newRequest && get(newRequest, "timeoutRunnable") === timer && oldCallbacks == 0 && newErrors == 0)
                    checkCase("stale_controller_does_not_release_reused_native_player", config(newController) === newConfig)
                    later(2_800) {
                        checkCase("new_timeout_survives_old_callbacks", newErrors == 1 && current() == null)
                        traceIsolation()
                    }
                }
            }
        }
    }

    private fun traceIsolation() {
        Hq008ConsentLogReporter.report("CMP_GATE_START", "device_validation=true")
        Hq008ConsentLogReporter.bindAuthorizationRequestId(Hq008ConsentLogReporter.activeRequestId(), "device125-trace-new")
        Hq008ConsentLogReporter.updatePopupLogEnabled(false, "device125-trace-new")
        Hq008ConsentLogReporter.report("AD_PHASE_START", "requestId=device125-trace-new")
        Hq008ConsentLogReporter.report("FLOATING_FLOW_SKIPPED", "reason=in_flight")
        Hq008ConsentLogReporter.report("AD_PHASE_TIMEOUT", "requestId=device125-trace-old")
        Hq008ConsentLogReporter.finishActiveFlow("late", "device125-trace-old")
        Hq008ConsentLogReporter.updatePopupLogEnabled(true, "device125-trace-old")
        checkCase("in_flight_and_stale_finish_preserve_new_trace_and_flag", Hq008ConsentLogReporter.activeRequestId() == "device125-trace-new" && !Hq008ConsentLogReporter.isFlowLoggingEnabled())
        Hq008ConsentLogReporter.finishActiveFlow("device_validation_done", "device125-trace-new")
        record("DONE", "passed" to true)
        status.text = "PASS: cancellation, initialization deadline, stale callbacks, native player ownership, timeout and trace isolation"
        networkRestore?.invoke(); networkRestore = null
        finished = true
    }

    private fun ensureFixtureInitialization() {
        if (!Initialization.isHasInit()) {
            val json = assets.open("tcl-original-waterfall-2.8.02.json").bufferedReader().use { it.readText() }
            val config = Gson().fromJson(json, com.tcl.ff.component.overseabasebusiness.bean.AdSystemConfig::class.java)
            val fetcher = com.tcl.ff.component.overseabasebusiness.AdConfigFetcher.get()
            field(fetcher, "e").set(fetcher, config)
            Initialization.init(applicationContext,
                com.tcl.ff.component.overseabase.base.constant.AdReportSwitchConfig(), null)
            record("fixture_configuration_seeded", "source" to "pinned_original_snapshot")
        }
        check(Initialization.isHasInit()) { "SDK has not initialized" }
    }

    private fun installPendingLifecycleFixture() {
        val requester = HttpRequester.get()
        val clientField = field(requester, "c")
        val client = clientField.get(requester) as OkHttpClient
        val previousObserver = TclRuntimeBridge.debugSessionObserver
        networkRestore = {
            clientField.set(requester, client)
            TclRuntimeBridge.debugSessionObserver = previousObserver
        }
        clientField.set(requester, client.newBuilder().addInterceptor { chain ->
            val body = """{"code":100000,"data":{"adsUrl":"https://tcl-lifecycle.fixture/pending","beaconingType":2,"adType":1,"adPlatformCode":"0","adUnitID":"fixture"}}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("lifecycle fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build())
        TclRuntimeBridge.debugSessionObserver = { session ->
            val pendingClient = GoogleGamPatchClient(OkHttpClient.Builder().addInterceptor {
                Thread.sleep(10_000L)
                throw java.io.IOException("deliberately pending lifecycle fixture")
            }.build(), "https://lifecycle-backend.fixture/patch")
            session.patch = TclBackendPatch(mapOf("request_id" to session.id, "channel_id" to session.channelId,
                "rdid" to "fixture-local"), { (session.deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L) },
                pendingClient, {}, {})
        }
    }

    private fun fixture() {
        ensureFixtureInitialization()
        val requester = HttpRequester.get()
        val clientField = generateSequence<Class<*>>(requester.javaClass) { it.superclass }.flatMap { it.declaredFields.asSequence() }
            .first { OkHttpClient::class.java.isAssignableFrom(it.type) }.apply { isAccessible = true }
        val originalClient = clientField.get(requester) as OkHttpClient
        val originalRemoteSet = AdDisplayConfig.isRemoteConfigSet()
        val originalHidden = AdDisplayConfig.isRemoteHiddenMode()
        restore = {
            clientField.set(requester, originalClient)
            if (originalRemoteSet) AdDisplayConfig.setRemoteHiddenMode(originalHidden) else AdDisplayConfig.clearRemoteConfig()
            TclRuntimeBridge.debugSessionObserver = null
            Hq008ConsentLogReporter.debugUploadObserver = null
        }
        val fixtureCase = intent.getStringExtra("fixture_case") ?: "single"
        val seconds = if (fixtureCase == "long") 210 else 20
        val duration = "00:%02d:%02d".format(seconds / 60, seconds % 60)
        val media = if (fixtureCase == "long") "long.mp4" else "probe.mp4"
        fun ad(index: Int) = """<Ad id="fixture-$index" sequence="$index"><InLine><AdSystem version="1">TclSyncFixture</AdSystem><AdTitle>TCL native fixture $index</AdTitle><Impression>http://127.0.0.1:18765/impression?ad=$index</Impression><Creatives><Creative id="creative-$index" sequence="$index"><Linear><Duration>$duration</Duration><TrackingEvents><Tracking event="start">http://127.0.0.1:18765/start?ad=$index</Tracking><Tracking event="firstQuartile">http://127.0.0.1:18765/firstQuartile?ad=$index</Tracking><Tracking event="midpoint">http://127.0.0.1:18765/midpoint?ad=$index</Tracking><Tracking event="thirdQuartile">http://127.0.0.1:18765/thirdQuartile?ad=$index</Tracking><Tracking event="complete">http://127.0.0.1:18765/complete?ad=$index</Tracking></TrackingEvents><MediaFiles><MediaFile delivery="progressive" type="video/mp4" width="640" height="360">http://127.0.0.1:18765/$media?ad=$index</MediaFile></MediaFiles></Linear></Creative></Creatives></InLine></Ad>"""
        val vast = when (fixtureCase) {
            "empty" -> "<VAST version=\"3.0\"/>"
            "pod" -> "<VAST version=\"3.0\">${ad(1)}${ad(2)}</VAST>"
            else -> "<VAST version=\"3.0\">${ad(1)}</VAST>"
        }
        val sourceUrl = "https://tcl-fixture.example/source?x=one&x=two&sig=a%2Bb%2Fc%3D"
        val guaranteeUrl = "https://tcl-fixture.example/guarantee"
        val finalUrl = "http://127.0.0.1:18765/root.vast"
        TclRuntimeBridge.debugSessionObserver = { session ->
            val patchClient = GoogleGamPatchClient(OkHttpClient.Builder().addInterceptor { chain ->
                fixturePatchCount++
                val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
                val body = JSONObject(buffer.readUtf8())
                record("fixture_patch_request", "body" to body)
                check(body.getString("request_id") == fixtureId)
                check(body.getString("channel_id") == session.channelId)
                val originalUrl = body.getString("ad_tag_url")
                val isGuarantee = originalUrl.toHttpUrlOrNull()?.encodedPath == "/guarantee"
                check(originalUrl == sourceUrl || (fixtureCase == "guarantee" && isGuarantee))
                val patchedUrl = if (isGuarantee) "http://127.0.0.1:18765/guarantee.vast" else finalUrl
                val code = if (fixtureCase == "patch_error") 500 else 200
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture patch")
                    .body("""{"code":$code,"data":{"ad_tag_url":"$patchedUrl"}}""".toResponseBody("application/json".toMediaType())).build()
            }.build(), "https://fixture-backend.example/api/v2/ad/google-gam/patch")
            session.patch = TclBackendPatch(mapOf("request_id" to session.id, "channel_id" to session.channelId,
                "rdid" to "fixture-local-id", "sdk_version" to "2.8.02"),
                { (session.deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L) }, patchClient,
                { session.event("patch_request", mapOf("source_url" to it)) },
                {
                    synchronized(session.patchedUrls) { session.patchedUrls.add(it) }
                    session.event("patch_ready", mapOf("final_url" to it))
                })
        }
        Hq008ConsentLogReporter.debugUploadObserver = { phase, payload, _ ->
            if (phase == "request" && payload["request_id"] == fixtureId) {
                fixtureUploadCount++
                val summary = JSONObject(payload["ad_log"] as String)
                val steps = summary.getJSONArray("steps")
                for (i in 0 until steps.length()) {
                    val step = steps.getJSONObject(i)
                    if (step.optString("eventType") == "TCL_RUNTIME_AUDIT") fixtureAudit = step.optJSONObject("adLog")
                }
                record("fixture_log_upload", "summary" to summary)
            }
        }
        val fixtureInterceptor = Interceptor { chain ->
            val request = chain.request()
            record("sdk_http", "method" to request.method, "host" to request.url.host, "path" to request.url.encodedPath)
            if (request.method == "POST") {
                val json = Gson().toJson(mapOf("code" to "100000", "data" to mapOf("adsUrl" to sourceUrl,
                    "beaconingType" to 2, "adType" to 1, "adPlatformCode" to "0", "adUnitID" to "fixture-unit",
                    "guaranteeLink" to if (fixtureCase == "guarantee") guaranteeUrl else "")))
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture address")
                    .body(json.toResponseBody("application/json".toMediaType())).build()
            } else if (request.url.encodedPath in setOf("/root.vast", "/inline.vast", "/guarantee.vast")) {
                record("fixture_vast_injected", "host" to request.url.host)
                val body = if (fixtureCase == "wrapper" && request.url.encodedPath == "/root.vast")
                    """<VAST version="3.0"><Ad><Wrapper><AdSystem>Fixture</AdSystem><VASTAdTagURI>http://127.0.0.1:18765/inline.vast</VASTAdTagURI><Creatives/></Wrapper></Ad></VAST>"""
                else if (fixtureCase == "guarantee" && request.url.encodedPath == "/root.vast") "<VAST version=\"3.0\"/>"
                else vast
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Device validation fixture")
                    .body(body.toResponseBody("application/xml".toMediaType())).build()
            } else chain.proceed(request)
        }
        clientField.set(requester, originalClient.newBuilder().addInterceptor(fixtureInterceptor).build())
        fixtureId = "device125-fixture-${System.currentTimeMillis()}"
        AdDisplayConfig.setRemoteHiddenMode(false)
        Hq008ConsentLogReporter.report("CMP_GATE_START", "device_validation_fixture=true")
        Hq008ConsentLogReporter.bindAuthorizationRequestId(Hq008ConsentLogReporter.activeRequestId(), fixtureId)
        Hq008ConsentLogReporter.updatePopupLogEnabled(true, fixtureId)
        Hq008ConsentLogReporter.report("AD_PHASE_START", "requestId=$fixtureId")
        AdRenderer.showFloatingAd(AdConfigDto(adId = fixtureId, adType = AdType.FLOATING.value, adUrl = null,
            contentType = null, displayDuration = 0, floatingHeight = 270, floatingWidth = 480, floatingX = 20,
            floatingY = 20, imageUrl = null, isClosable = 1, isCountdownVisible = false, position = 1,
            videoUrl = null, callbackTimeoutMs = 180_000L), onFloatingFlowFinished = {
            safely {
                checkCase("real_native_fixture_finished_and_window_cleaned",
                    (if (fixtureCase in setOf("empty", "patch_error")) fixtureError else fixtureCompleted) && current() == null)
                checkCase("patch_once_per_selected_root_no_wrapper_repatch", fixturePatchCount == if (fixtureCase == "guarantee") 2 else 1)
                checkCase("one_terminal_runtime_audit_upload", fixtureUploadCount == 1 && fixtureAudit != null)
                checkCase("filled_matches_response", ("AD_FILLED" in fixtureEvents) == (fixtureCase !in setOf("empty", "patch_error")))
                checkCase("audit_precedes_terminal", fixtureEvents.indexOf("TCL_RUNTIME_AUDIT") <
                    fixtureEvents.indexOf(if (fixtureCase in setOf("empty", "patch_error")) "AD_PHASE_ERROR" else "AD_PHASE_COMPLETED"))
                if (fixtureCase !in setOf("empty", "patch_error")) {
                    val events = fixtureAudit!!.getJSONArray("event_timeline")
                    checkCase("real_tracking_http_observed", (0 until events.length()).any {
                        val event = events.getJSONObject(it)
                        event.optString("event") == "http_result" && event.optString("kind") == "tracking"
                    })
                    val segments = fixtureAudit!!.getJSONObject("playback").getJSONArray("segments")
                    checkCase("per_ad_metadata_preserved", segments.length() == if (fixtureCase == "pod") 2 else 1)
                    if (fixtureCase == "guarantee") {
                        val responses = fixtureAudit!!.getJSONArray("vast_responses")
                        checkCase("guarantee_xml_records_actual_patched_url", (0 until responses.length()).any {
                            responses.getJSONObject(it).optString("request_url")
                                .startsWith("http://127.0.0.1:18765/guarantee.vast")
                        })
                    }
                }
                record("fixture_audit", "audit" to fixtureAudit)
                restore?.invoke(); restore = null
                record("DONE", "passed" to true)
                status.text = "PASS: real TCL load, video decode, completion and floating window cleanup"
                finished = true
            }
        })
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        restore?.invoke(); restore = null
        networkRestore?.invoke(); networkRestore = null
        Hq008ConsentLogReporter.removeDebugTraceListener(traceListener)
        if (Hq008AdReporter.debugReportObserver === reportObserver) Hq008AdReporter.debugReportObserver = null
        if (fixtureId.isNotBlank()) {
            if (Hq008ConsentLogReporter.activeRequestId() == fixtureId) {
                AdRenderer.stopFloatingAd("device_validation_activity_destroyed")
            }
            AdManagerImpl.destroyAd(fixtureId)
        }
        super.onDestroy()
    }
}
