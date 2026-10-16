package com.smart.android.ad_app

import com.itv.component.unified.UnifiedAdPrefs
import com.tcl.ff.component.vastad.convert.XmlConverter
import com.tcl.ff.component.vastad.loader.xmlmodels.VAST
import com.tcl.ff.component.vastad.core.data.c as VendorAdPod
import okhttp3.ResponseBody
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import android.util.Xml
import java.io.StringReader
import java.util.concurrent.TimeUnit
import java.util.WeakHashMap

/** Observes the original SDK calls without changing their result or consuming a body twice. */
internal object HaierAarPlaybackDiagnostics {
    private const val MAX_EVENTS = 32
    private const val MAX_XML_INSPECTION_CHARS = 128_000
    private val lock = Any()
    private var activeRequestId: String? = null
    private val ownerRequests = WeakHashMap<Any, String>()
    private val vastFetchRequest = ThreadLocal<String>()
    private var eventCount = 0
    private var droppedEvents = 0
    private var failurePriority = 0
    private val recentEvidence = ArrayDeque<JSONObject>()
    private val keyEvidence = linkedMapOf<String, JSONObject>()
    private val failure = linkedMapOf<String, Any?>()
    val enabled: Boolean get() = BuildConfig.UNIFIED_AD_SDK_NAME == "addy_jams" &&
        Hq008ConsentLogReporter.isFlowLoggingEnabled()

    fun begin(requestId: String) {
        if (!enabled) return
        synchronized(lock) {
            activeRequestId = requestId
            eventCount = 0
            droppedEvents = 0
            failurePriority = 0
            recentEvidence.clear()
            keyEvidence.clear()
            failure.clear()
        }
    }

    fun failureDetails(requestId: String): Map<String, Any?> = synchronized(lock) {
        if (activeRequestId == requestId) failure.toMap() else emptyMap()
    }

    fun bindController(owner: Any) {
        runCatching {
            val id = HaierAarRequestContext.currentRequestId() ?: return
            synchronized(lock) { ownerRequests[owner] = id }
        }
    }

    fun beginVastFetch() {
        vastFetchRequest.set(HaierAarRequestContext.currentRequestId().orEmpty())
    }

    private fun fetchRequestId(): String? = vastFetchRequest.get()?.takeIf { it.isNotEmpty() }
        ?: if (vastFetchRequest.get() == null) HaierAarRequestContext.currentRequestId() else null

    private fun ownerRequestId(owner: Any): String? = synchronized(lock) { ownerRequests[owner] }

    fun readVastBody(body: ResponseBody): String {
        val requestId = fetchRequestId()
        val started = System.nanoTime()
        // The delegate remains responsible for its original close/read/timeout behavior.
        return try {
            body.string().also { text ->
                runCatching {
                    observe("vast_body_read", mapOf(
                        "read_success" to true,
                        "response_text_length" to text.length,
                        "response_text_hash" to AdPrivacySanitizer.shortHash(text),
                        "read_duration_ms" to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    ), requestId = requestId)
                }
            }
        } catch (error: Exception) {
            runCatching {
                observe("vast_body_read", throwableDetails(error) + mapOf(
                    "read_success" to false,
                    "read_duration_ms" to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                ), isFailure = true, requestId = requestId)
            }
            throw error
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun convertVast(converter: Any, text: String?, model: Class<*>): Any? {
        val requestId = fetchRequestId()
        val result = try {
            (converter as XmlConverter<Any>).convert(text, model as Class<Any>)
        } finally {
            vastFetchRequest.remove()
        }
        runCatching {
            val vast = result as? VAST
            val ads = vast?.ad
            observe("vast_parse_result", inspectXml(text) + mapOf(
                "parse_success" to (vast != null),
                "parsed_ad_count" to (ads?.size ?: 0),
                "parsed_inline_count" to (ads?.count { it.inLine != null } ?: 0)
            ), requestId = requestId)
        }
        return result
    }

    fun parseException(error: Throwable) {
        runCatching { observe("vast_parse_exception", throwableDetails(error), isFailure = true, requestId = fetchRequestId()) }
    }

    fun requestError(owner: Any, code: Int) {
        observe("vast_request_error", mapOf(
            "sdkErrorCode" to code,
            "sdkErrorMeaning" to when (code) {
                100 -> "empty_or_unparsed_vast"
                103 -> "empty_playable_ad_pod"
                else -> "vendor_error"
            }
        ), isFailure = true, requestId = ownerRequestId(owner))
    }

    fun playerError(owner: Any, error: Throwable) {
        runCatching {
            val code = runCatching { error.javaClass.getField("errorCode").get(error) as? Int }.getOrNull()
            observe("player_error", throwableDetails(error) + mapOf("sdkErrorCode" to code), isFailure = true, requestId = ownerRequestId(owner))
        }
    }

    fun playerState(owner: Any, state: Int) = observe("player_state", mapOf("playback_state" to state), requestId = ownerRequestId(owner))

    fun playablePod(owner: Any, pod: Any?) {
        runCatching {
            val parsed = pod as? VendorAdPod
            val selected = (0 until minOf(parsed?.b() ?: 0, 6)).mapNotNull { index ->
                runCatching {
                    val info = parsed!!.a(index)
                    val media = info.d()
                    JSONObject(mapOf("index" to index, "creative_id_hash" to AdPrivacySanitizer.shortHash(info.b()),
                        "url_hash" to AdPrivacySanitizer.shortHash(media.value?.trim()),
                        "mime" to safeMessage(media.type).take(64), "width" to media.width,
                        "height" to safeMessage(media.height).take(32), "bitrate" to safeMessage(media.bitrate).take(32),
                        "delivery" to safeMessage(media.delivery).take(32)))
                }.getOrNull()
            }
            observe("playable_ad_pod", mapOf("playable_ad_count" to parsed?.b(),
                "selected_media" to org.json.JSONArray(selected)), requestId = ownerRequestId(owner))
        }
    }

    fun branchFlag(value: Boolean, name: String) = observe("branch_flag", mapOf("flag" to name, "enabled" to value))

    fun primaryState(state: Any?) = observe("primary_state", mapOf("ad_state" to (state as? Enum<*>)?.name))

    fun stage(name: String) = observe(name)

    fun controllerStage(owner: Any, name: String) = observe(name,
        fields = if (name == "container_size_error") mapOf("sdkErrorMeaning" to "invalid_container_size") else emptyMap(),
        isFailure = name == "container_size_error", requestId = ownerRequestId(owner))

    fun branchSnapshot() {
        if (!enabled) return
        runCatching {
            val appKey = BuildConfig.UNIFIED_AD_APP_KEY
            val tagId = BuildConfig.UNIFIED_AD_TAG_ID
            observe("branch_snapshot", mapOf(
                "readme_enabled" to UnifiedAdPrefs.isReadmeOpenEnabled(appContext, appKey),
                "lsap_enabled" to UnifiedAdPrefs.isLsapPlacementEnabled(appContext, appKey, tagId)
            ))
        }
    }

    private fun inspectXml(text: String?): Map<String, Any?> {
        if (text == null) return mapOf("xml_shape" to "null_body")
        if (text.length > MAX_XML_INSPECTION_CHARS) return mapOf("xml_shape" to "inspection_size_limit")
        return runCatching {
            val parser = Xml.newPullParser()
            if (parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) {
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            }
            parser.setInput(StringReader(text))
            var root: String? = null
            var wrappers = 0
            var inlines = 0
            var mediaFiles = 0
            var vastVersion: String? = null
            val mediaCandidates = mutableListOf<JSONObject>()
            val durations = mutableListOf<String>()
            val trackingCounts = linkedMapOf<String, Int>()
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    val name = parser.name.substringAfter(':')
                    if (root == null) root = name.take(80)
                    when (name) {
                        "VAST" -> vastVersion = parser.getAttributeValue(null, "version")?.take(32)
                        "Wrapper" -> wrappers++
                        "InLine" -> inlines++
                        "Duration" -> if (durations.size < 8) durations += safeMessage(parser.nextText().trim()).take(32)
                        "Tracking" -> {
                            val event = parser.getAttributeValue(null, "event")
                            if (event in setOf("start", "firstQuartile", "midpoint", "thirdQuartile", "complete")) {
                                trackingCounts[event!!] = (trackingCounts[event] ?: 0) + 1
                            }
                        }
                        "MediaFile" -> {
                            mediaFiles++
                            if (mediaCandidates.size < 8) {
                                val attrs = listOf("type", "delivery", "width", "height", "bitrate", "codec")
                                    .associateWith { safeMessage(parser.getAttributeValue(null, it)).take(64) }
                                val hash = AdPrivacySanitizer.shortHash(parser.nextText().trim())
                                mediaCandidates += JSONObject(attrs + mapOf("url_hash" to hash))
                            }
                        }
                    }
                }
                parser.next()
            }
            mapOf("xml_shape" to "inspected", "xml_root" to root,
                "xml_wrapper_count" to wrappers, "xml_inline_count" to inlines, "xml_media_file_count" to mediaFiles,
                "vast_version" to vastVersion, "xml_media_candidates" to org.json.JSONArray(mediaCandidates),
                "xml_durations" to org.json.JSONArray(durations), "xml_tracking_counts" to JSONObject(trackingCounts as Map<*, *>))
        }.getOrElse { mapOf("xml_shape" to "inspection_failed", "xml_inspection_error_type" to it.javaClass.name, "xml_inspection_error" to safeMessage(it.message)) }
    }

    fun throwableDetails(error: Throwable): Map<String, Any?> {
        val causes = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (current != null && causes.size < 6 && causes.none { it === current }) {
            causes += current
            current = current.cause
        }
        val root = causes.last()
        return mapOf(
            "sdkFailureClass" to error.javaClass.name,
            "sdkFailureMessage" to safeMessage(error.message),
            "sdkRootCauseClass" to root.javaClass.name,
            "sdkRootCauseMessage" to safeMessage(root.message),
            "sdkCauseTypes" to causes.joinToString(" > ") { it.javaClass.name }
        )
    }

    fun safeMessage(message: String?): String = message.orEmpty()
        .replace(Regex("https?://[^\\s]+"), "[url]")
        .take(160)

    fun evidence(stage: String, fields: Map<String, Any?>, requestId: String?,
                 isFailure: Boolean = false, essential: Boolean = false) =
        observe(stage, fields, isFailure, requestId, essential)

    /** Re-emits bounded evidence at the tail, where the 80-step flow cap preserves it. */
    fun finish(requestId: String) {
        runCatching {
            val details = synchronized(lock) {
                if (activeRequestId != requestId) return
                mapOf("dropped_noncritical_events" to droppedEvents,
                    "recent_evidence" to org.json.JSONArray(recentEvidence.toList()),
                    "key_evidence" to JSONObject(keyEvidence as Map<*, *>),
                    "failure_details" to JSONObject(failure as Map<*, *>))
            }
            observe("diagnostic_evidence_summary", details, requestId = requestId, essential = true)
        }
    }

    private fun observe(
        stage: String,
        fields: Map<String, Any?> = emptyMap(),
        isFailure: Boolean = false,
        requestId: String? = HaierAarRequestContext.currentRequestId(),
        essential: Boolean = false
    ) {
        // Diagnostic exceptions must never turn a successful SDK call into a failure.
        runCatching {
            if (!enabled || requestId == null || requestId != HaierAarRequestContext.currentRequestId()) return
            val record = synchronized(lock) {
                if (requestId != activeRequestId) return
                if (isFailure) {
                    // Keep a read failure through parse(null), but let a later fatal player
                    // exception supersede an earlier transport failure if playback was reached.
                    val priority = when (stage) {
                        "player_error", "actual_player_error" -> 100
                        "vast_body_read" -> 60
                        "vast_parse_exception" -> 50
                        "http_call_result" -> 40
                        else -> 20
                    }
                    if (priority > failurePriority) {
                        failure.clear()
                        failure["sdkFailureStage"] = stage
                        failurePriority = priority
                    }
                    fields.forEach { (key, value) ->
                        if (!failure.containsKey(key)) failure[key] = value
                    }
                }
                val item = JSONObject().apply {
                    put("diagnostic_version", 2)
                    put("request_id", requestId)
                    put("stage", stage)
                    put("timestamp_ms", System.currentTimeMillis())
                    fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
                }
                if (stage in setOf("vast_body_read", "vast_parse_exception", "vast_parse_result", "vast_request_error",
                        "playable_ad_pod", "branch_snapshot", "player_error", "actual_player_error", "video_codec_error")) {
                    keyEvidence[stage] = item
                }
                if (!essential) {
                    // Fixed memory bound even when the SDK repeats a callback.
                    if (recentEvidence.size >= 16) recentEvidence.removeFirst()
                    recentEvidence.addLast(item)
                }
                if (eventCount >= MAX_EVENTS && !essential) {
                    droppedEvents++
                    return
                }
                eventCount++
                item.toString()
            }
            Hq008ConsentLogReporter.report(
                eventType = "AD_SDK_DIAGNOSTIC",
                eventMessage = "requestId=$requestId,stage=$stage",
                adLog = record
            )
        }
    }
}
