package com.smart.android.ad_app

import com.google.gson.Gson
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import com.google.common.io.BaseEncoding

internal data class TclVastObservation(
    val wrapperCount: Int = 0,
    val inlineCount: Int = 0,
    val videoUrls: List<String> = emptyList(),
    val verificationUrls: List<String> = emptyList(),
    val parseError: String? = null,
    val trackingUrls: Map<String, List<String>> = emptyMap()
) {
    val filled: Boolean get() = videoUrls.isNotEmpty()

    companion object {
        fun parse(xml: String): TclVastObservation = try {
            require(!Regex("<!\\s*(DOCTYPE|ENTITY)\\b", RegexOption.IGNORE_CASE).containsMatchIn(xml)) {
                "DTD/entity declarations are not allowed"
            }
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            val builder = factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
            }
            val root = builder.parse(InputSource(StringReader(xml))).documentElement
            require(root.localName == "VAST" || root.tagName == "VAST") { "Not a VAST response" }
            val elements = root.getElementsByTagNameNS("*", "*")
            var wrappers = 0
            var inlines = 0
            val media = mutableListOf<String>()
            val verification = mutableListOf<String>()
            val tracking = linkedMapOf<String, MutableSet<String>>()
            for (i in 0 until elements.length) {
                val element = elements.item(i) as? Element ?: continue
                when (element.localName ?: element.tagName) {
                    "Wrapper" -> wrappers++
                    "InLine" -> {
                        inlines++
                        val linearNodes = element.getElementsByTagNameNS("*", "Linear")
                        for (j in 0 until linearNodes.length) {
                            val files = (linearNodes.item(j) as Element).getElementsByTagNameNS("*", "MediaFile")
                            for (k in 0 until files.length) {
                                val file = files.item(k) as Element
                                val url = file.textContent.trim()
                                var node: org.w3c.dom.Node? = file
                                val exactPath = listOf("MediaFile", "MediaFiles", "Linear", "Creative",
                                    "Creatives", "InLine", "Ad", "VAST").all { expected ->
                                    val matches = node is Element &&
                                        (node?.localName ?: node?.nodeName?.substringAfter(':')) == expected
                                    node = node?.parentNode
                                    matches
                                } && node?.nodeType == org.w3c.dom.Node.DOCUMENT_NODE
                                val address = runCatching { java.net.URI(url) }.getOrNull()
                                if (exactPath && address?.scheme?.lowercase() in setOf("http", "https") &&
                                    !address?.host.isNullOrBlank()) media += url
                            }
                        }
                    }
                    "JavaScriptResource" -> verification += element.textContent.trim()
                    "Tracking", "Impression" -> {
                        val url = element.textContent.trim()
                        val event = if ((element.localName ?: element.tagName) == "Impression") "impression"
                            else element.getAttribute("event")
                        if (url.startsWith("http://") || url.startsWith("https://"))
                            tracking.getOrPut(url) { linkedSetOf() }.add(event)
                    }
                }
            }
            TclVastObservation(wrappers, inlines, media.distinct(), verification.distinct(),
                trackingUrls = tracking.mapValues { it.value.toList() })
        } catch (error: Exception) {
            TclVastObservation(parseError = error.message ?: error.javaClass.simpleName)
        }
    }
}

/** One engine-specific summary inside the existing terminal consent-log-report upload. */
internal class TclRuntimeAuditReporter(
    private val requestId: String,
    private val channelId: String,
    private val soundEnabled: Boolean,
    private val enabled: Boolean
) {
    private val lock = Any()
    private val gson = Gson()
    private val counts = linkedMapOf<String, Int>()
    private val timeline = mutableListOf<Map<String, Any?>>()
    private val responses = mutableListOf<Map<String, Any?>>()
    private val artifacts = mutableListOf<Map<String, Any?>>()
    private var artifactBytes = 0
    private var omittedArtifacts = 0
    private var omittedEvents = 0
    private var responseCount = 0
    private var finalSnapshot: Map<String, Any?> = emptyMap()
    private var finished = false

    fun record(type: String, atMs: Long, fields: Map<String, Any?> = emptyMap()) = synchronized(lock) {
        if (!enabled || finished) return@synchronized
        counts[type] = (counts[type] ?: 0) + 1
        if (timeline.size >= 400) {
            timeline.removeAt(1) // Keep the first event and the latest evidence.
            omittedEvents++
        }
        timeline += linkedMapOf<String, Any?>("event" to type, "elapsed_realtime_ms" to atMs) + fields
        if (type == "player_snapshot") finalSnapshot = fields
    }

    fun vast(xml: String, atMs: Long, fields: Map<String, Any?>, observation: TclVastObservation) {
        if (!enabled) return
        val bytes = xml.toByteArray(Charsets.UTF_8)
        val digest = sha256(bytes)
        val compressed = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(bytes) }
            output.toByteArray()
        }
        synchronized(lock) {
            if (finished) return
            val index = ++responseCount
            val id = "tcl-vast-$index"
            val keep = responses.size < 64 && artifactBytes + compressed.size <= 4 * 1024 * 1024
            val response = fields + linkedMapOf(
                "elapsed_realtime_ms" to atMs, "response_index" to index,
                "xml_length" to bytes.size, "xml_sha256" to digest,
                "wrapper_count" to observation.wrapperCount, "inline_count" to observation.inlineCount,
                "media_urls" to observation.videoUrls,
                "verification_resources" to observation.verificationUrls.map {
                    mapOf("url" to it, "execution_status" to "not_observed", "omid_support" to "not_available")
                },
                "parse_error" to observation.parseError, "artifact_id" to if (keep) id else null,
                "artifact_omitted" to !keep
            )
            if (responses.size < 64) responses += response
            if (keep) {
                artifacts += linkedMapOf("artifact_type" to "tcl_vast_xml", "artifact_id" to id,
                    "encoding" to "gzip+base64", "original_length" to bytes.size,
                    "original_sha256" to digest, "content" to BaseEncoding.base64().encode(compressed))
                artifactBytes += compressed.size
            } else omittedArtifacts++
        }
    }

    fun finish(reason: String, playback: Map<String, Any?>): String? = synchronized(lock) {
        if (finished) return@synchronized null
        finished = true
        if (!enabled) return@synchronized null
        gson.toJson(linkedMapOf(
            "schema_version" to 1, "audit_scope" to "tcl_native_request_vast_playback_tracking",
            "engine" to "tcl", "sdk_version" to "2.8.02", "request_id" to requestId,
            "channel_id" to channelId, "terminal_reason" to reason,
            "event_counts" to counts, "event_timeline" to timeline,
            "omitted_event_count" to omittedEvents, "vast_responses" to responses,
            "vast_response_count" to responseCount,
            "omitted_response_count" to responseCount - responses.size,
            "vast_artifacts" to artifacts, "omitted_artifact_count" to omittedArtifacts,
            "playback" to playback, "last_player_snapshot" to finalSnapshot,
            "sound_audit" to mapOf("configured_player_volume" to if (soundEnabled) 1f else 0f,
                "device_volume_collection" to "not_collected",
                "ad_platform_volume_reporting" to "not_observed"),
            "measurement_capabilities" to mapOf("ima_events" to "not_applicable", "omid" to "not_available")
        ))
    }

    companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
