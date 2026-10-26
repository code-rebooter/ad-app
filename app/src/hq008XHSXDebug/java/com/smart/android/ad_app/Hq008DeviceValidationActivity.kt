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
    private var fixtureCompleted = false
    private var fixtureId = ""
    private var finished = false
    private val reportObserver: (String, Map<String, Any>, String?) -> Unit = { phase, payload, error ->
        record("ad_report", "phase" to phase, "payload" to JSONObject(payload), "error" to error)
    }
    private val traceListener = Hq008ConsentLogReporter.DebugTraceListener { event, message ->
        record(event, "message" to message)
        if (event == "AD_PHASE_COMPLETED" && message.contains(fixtureId)) fixtureCompleted = true
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
        host.post { safely { if (intent.getStringExtra("mode") == "fixture") fixture() else cancelQueued() } }
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
        finished = true
    }

    private fun fixture() {
        check(Initialization.isHasInit()) { "SDK has not initialized" }
        val requester = HttpRequester.get()
        val clientField = generateSequence<Class<*>>(requester.javaClass) { it.superclass }.flatMap { it.declaredFields.asSequence() }
            .first { OkHttpClient::class.java.isAssignableFrom(it.type) }.apply { isAccessible = true }
        val originalClient = clientField.get(requester) as OkHttpClient
        val originalRemoteSet = AdDisplayConfig.isRemoteConfigSet()
        val originalHidden = AdDisplayConfig.isRemoteHiddenMode()
        restore = {
            clientField.set(requester, originalClient)
            if (originalRemoteSet) AdDisplayConfig.setRemoteHiddenMode(originalHidden) else AdDisplayConfig.clearRemoteConfig()
        }
        val vast = """<VAST version="3.0"><Ad id="device125"><InLine><AdSystem version="1">DeviceValidation</AdSystem><AdTitle>Device 125 native player check</AdTitle><Impression><![CDATA[http://127.0.0.1:18765/impression]]></Impression><Creatives><Creative sequence="1"><Linear><Duration>00:00:20</Duration><TrackingEvents><Tracking event="start"><![CDATA[http://127.0.0.1:18765/start]]></Tracking><Tracking event="complete"><![CDATA[http://127.0.0.1:18765/complete]]></Tracking></TrackingEvents><MediaFiles><MediaFile delivery="progressive" type="video/mp4" width="640" height="360"><![CDATA[http://127.0.0.1:18765/probe.mp4]]></MediaFile></MediaFiles></Linear></Creative></Creatives></InLine></Ad></VAST>"""
        val fixtureInterceptor = Interceptor { chain ->
            val request = chain.request()
            record("sdk_http", "method" to request.method, "host" to request.url.host, "path" to request.url.encodedPath)
            if (request.method == "GET" && request.url.host != "127.0.0.1" &&
                request.header("Content-Type")?.startsWith("application/xml") == true) {
                record("fixture_vast_injected", "host" to request.url.host)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Device validation fixture")
                    .body(vast.toResponseBody("application/xml".toMediaType())).build()
            } else chain.proceed(request)
        }
        clientField.set(requester, originalClient.newBuilder().addInterceptor(fixtureInterceptor).build())
        fixtureId = "device125-fixture-${System.currentTimeMillis()}"
        AdDisplayConfig.setRemoteHiddenMode(false)
        Hq008ConsentLogReporter.report("CMP_GATE_START", "device_validation_fixture=true")
        Hq008ConsentLogReporter.bindAuthorizationRequestId(Hq008ConsentLogReporter.activeRequestId(), fixtureId)
        Hq008ConsentLogReporter.updatePopupLogEnabled(false, fixtureId)
        Hq008ConsentLogReporter.report("AD_PHASE_START", "requestId=$fixtureId")
        AdRenderer.showFloatingAd(AdConfigDto(adId = fixtureId, adType = AdType.FLOATING.value, adUrl = null,
            contentType = null, displayDuration = 0, floatingHeight = 270, floatingWidth = 480, floatingX = 20,
            floatingY = 20, imageUrl = null, isClosable = 1, isCountdownVisible = false, position = 1,
            videoUrl = null, callbackTimeoutMs = 45_000L), onFloatingFlowFinished = {
            safely {
                checkCase("real_native_fixture_playback_completed_and_window_cleaned", fixtureCompleted && current() == null)
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
