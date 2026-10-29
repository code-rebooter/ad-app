package com.smart.android.ad_app

import com.google.gson.Gson
import com.google.common.io.BaseEncoding
import com.smart.android.googleima.GoogleImaAuditEvent
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import javax.xml.parsers.DocumentBuilderFactory

internal class GoogleImaRuntimeAuditReporter(
    private val requestId: String
) {
    private val lock = Any()
    private val gson = Gson()
    private val vastResponses = mutableListOf<VastResponse>()
    private val verificationScripts = mutableListOf<VerificationScript>()
    private val webResources = linkedMapOf<String, WebResourceStatus>()
    private val omidCalls = linkedMapOf<String, Int>()
    private var nativeXhrCount = 0
    private var webVastResponseCount = 0
    private var hierarchyCount = 0
    private var hierarchyFirstAtMs: Long? = null
    private var hierarchyLastAtMs: Long? = null
    private var hierarchyMaxGapMs = 0L
    private var hierarchyLastHash = ""
    private var hierarchyLastLength = 0
    private var hierarchyNotVisibleReasonCount = 0
    private var hierarchyWindowFocusFalseCount = 0
    private var finished = false

    fun record(event: GoogleImaAuditEvent) {
        synchronized(lock) {
            if (finished) return
            when (event.eventType) {
                "vast_response" -> recordVast(event)
                "native_xhr_response" -> nativeXhrCount += 1
                "web_resource" -> recordWebResource(event)
                "omid_bridge" -> recordOmid(event)
            }
        }
    }

    fun finish(terminalReason: String) {
        val snapshot = synchronized(lock) {
            if (finished) return
            finished = true
            Snapshot(
                summary = buildSummary(terminalReason),
                vastResponseCount = vastResponses.size,
                verificationScriptCount = verificationScripts.size,
                omidStarted = (omidCalls["startSession"] ?: 0) > 0,
                omidFinished = (omidCalls["finishSession"] ?: 0) > 0
            )
        }

        Hq008ConsentLogReporter.report(
            eventType = "IMA_RUNTIME_AUDIT",
            eventMessage = "requestId=$requestId,vastResponses=${snapshot.vastResponseCount}," +
                "verificationScripts=${snapshot.verificationScriptCount}," +
                "omidStarted=${snapshot.omidStarted},omidFinished=${snapshot.omidFinished}," +
                "terminalReason=$terminalReason",
            adLog = snapshot.summary,
            requestId = requestId
        )
    }

    private fun recordVast(event: GoogleImaAuditEvent) {
        val captureSource = event.fields["capture_source"].orEmpty().ifBlank { "native_xhr" }
        if (captureSource == "native_xhr") nativeXhrCount += 1 else webVastResponseCount += 1
        val xml = event.payload ?: return
        val xmlBytes = xml.toByteArray(Charsets.UTF_8)
        val parsed = parseVast(xml)
        val fields = event.fields
        val responseIndex = fields["response_index"]?.toIntOrNull() ?: (vastResponses.size + 1)
        vastResponses += VastResponse(
            captureSource = captureSource,
            responseIndex = responseIndex,
            imaRequestId = fields["request_id"].orEmpty(),
            imaResponseId = fields["response_id"].orEmpty(),
            requestUrl = fields["request_url"].orEmpty(),
            requestType = fields["request_type"].orEmpty(),
            contentType = fields["content_type"].orEmpty(),
            errorCode = fields["error_code"]?.toIntOrNull() ?: 0,
            xmlLength = xmlBytes.size,
            xmlSha256 = sha256(xmlBytes),
            encodedXml = gzipBase64(xmlBytes),
            wrapperCount = parsed.wrapperCount,
            inlineCount = parsed.inlineCount,
            verificationScriptCount = parsed.scripts.size,
            parseError = parsed.error
        )
        verificationScripts += parsed.scripts.map { script ->
            script.copy(responseIndex = responseIndex)
        }
    }

    private fun recordWebResource(event: GoogleImaAuditEvent) {
        val url = event.fields["url"].orEmpty()
        if (url.isBlank()) return
        val status = webResources.getOrPut(url) { WebResourceStatus() }
        when (event.fields["phase"]) {
            "request" -> status.requestCount += 1
            "load" -> status.loadCallbackCount += 1
            "page_started" -> status.pageStartedCount += 1
            "page_finished" -> status.pageFinishedCount += 1
            "error", "http_error" -> {
                status.errorCount += 1
                status.lastErrorCode = event.fields["error_code"].orEmpty()
                status.lastError = event.fields["error"].orEmpty()
            }
        }
    }

    private fun recordOmid(event: GoogleImaAuditEvent) {
        val method = event.fields["method"].orEmpty()
        if (method.isBlank()) return
        omidCalls[method] = (omidCalls[method] ?: 0) + 1
        if (method != "setNativeViewHierarchy") return

        hierarchyCount += 1
        val now = event.elapsedRealtimeMs
        val previous = hierarchyLastAtMs
        if (previous != null) hierarchyMaxGapMs = maxOf(hierarchyMaxGapMs, now - previous)
        if (hierarchyFirstAtMs == null) hierarchyFirstAtMs = now
        hierarchyLastAtMs = now
        hierarchyLastHash = event.fields["hierarchy_sha256"].orEmpty()
        hierarchyLastLength = event.fields["hierarchy_length"]?.toIntOrNull() ?: 0
        if (event.fields["not_visible_reason_present"] == "true") {
            hierarchyNotVisibleReasonCount += 1
        }
        if (event.fields["window_focus_false_present"] == "true") {
            hierarchyWindowFocusFalseCount += 1
        }
    }

    private fun buildSummary(terminalReason: String): String {
        val verificationSummary = verificationScripts.map { script ->
            val status = webResources[script.url]
            linkedMapOf<String, Any?>(
                "response_index" to script.responseIndex,
                "vendor" to script.vendor,
                "url" to script.url,
                "api_framework" to script.apiFramework,
                "browser_optional" to script.browserOptional,
                "verification_parameters" to script.verificationParameters,
                "verification_not_executed_url" to script.notExecutedUrl,
                "request_observed" to ((status?.requestCount ?: 0) > 0),
                "load_callback_observed" to ((status?.loadCallbackCount ?: 0) > 0),
                "request_count" to (status?.requestCount ?: 0),
                "load_callback_count" to (status?.loadCallbackCount ?: 0),
                "error_count" to (status?.errorCount ?: 0),
                "last_error_code" to status?.lastErrorCode,
                "last_error" to status?.lastError,
                "client_observation" to when {
                    (status?.errorCount ?: 0) > 0 -> "error_observed"
                    (status?.requestCount ?: 0) > 0 ||
                        (status?.loadCallbackCount ?: 0) > 0 -> "load_observed_without_client_error"
                    else -> "not_observed"
                }
            )
        }
        val hierarchyDuration = hierarchyFirstAtMs?.let { first ->
            hierarchyLastAtMs?.minus(first)
        }
        return gson.toJson(
            linkedMapOf(
                "schema_version" to 1,
                "audit_scope" to "pure_ima_request_playback_omid",
                "request_id" to requestId,
                "terminal_reason" to terminalReason,
                "native_xhr_response_count" to nativeXhrCount,
                "web_vast_response_count" to webVastResponseCount,
                "captured_vast_response_count" to vastResponses.size,
                "vast_response_count" to vastResponses.size,
                "vast_responses" to vastResponses.map { response ->
                    linkedMapOf(
                        "capture_source" to response.captureSource,
                        "response_index" to response.responseIndex,
                        "ima_request_id" to response.imaRequestId,
                        "ima_response_id" to response.imaResponseId,
                        "request_url" to response.requestUrl,
                        "request_type" to response.requestType,
                        "content_type" to response.contentType,
                        "error_code" to response.errorCode,
                        "xml_length" to response.xmlLength,
                        "xml_sha256" to response.xmlSha256,
                        "wrapper_count" to response.wrapperCount,
                        "inline_count" to response.inlineCount,
                        "verification_script_count" to response.verificationScriptCount,
                        "parse_error" to response.parseError,
                        "artifact_id" to "vast-response-${response.responseIndex}"
                    )
                },
                "vast_artifacts" to vastResponses.map { response ->
                    linkedMapOf(
                        "artifact_type" to "ima_vast_xml",
                        "artifact_id" to "vast-response-${response.responseIndex}",
                        "encoding" to "gzip+base64",
                        "original_length" to response.xmlLength,
                        "original_sha256" to response.xmlSha256,
                        "metadata" to linkedMapOf(
                            "capture_source" to response.captureSource,
                            "response_index" to response.responseIndex,
                            "ima_request_id" to response.imaRequestId,
                            "ima_response_id" to response.imaResponseId,
                            "request_url" to response.requestUrl,
                            "request_type" to response.requestType,
                            "content_type" to response.contentType,
                            "error_code" to response.errorCode,
                            "wrapper_count" to response.wrapperCount,
                            "inline_count" to response.inlineCount,
                            "verification_script_count" to response.verificationScriptCount,
                            "parse_error" to response.parseError
                        ),
                        "content" to response.encodedXml
                    )
                },
                "verification_scripts" to verificationSummary,
                "web_resource_url_count" to webResources.size,
                "omid_runtime_audit" to linkedMapOf(
                    "method_call_counts" to omidCalls,
                    "initialized" to ((omidCalls["init"] ?: 0) > 0),
                    "session_started" to ((omidCalls["startSession"] ?: 0) > 0),
                    "session_finished" to ((omidCalls["finishSession"] ?: 0) > 0),
                    "hierarchy_submission_count" to hierarchyCount,
                    "hierarchy_first_at_ms" to hierarchyFirstAtMs,
                    "hierarchy_last_at_ms" to hierarchyLastAtMs,
                    "hierarchy_duration_ms" to hierarchyDuration,
                    "hierarchy_max_gap_ms" to hierarchyMaxGapMs,
                    "last_hierarchy_length" to hierarchyLastLength,
                    "last_hierarchy_sha256" to hierarchyLastHash,
                    "not_visible_reason_submission_count" to hierarchyNotVisibleReasonCount,
                    "window_focus_false_submission_count" to hierarchyWindowFocusFalseCount
                )
            )
        )
    }

    private fun parseVast(xml: String): ParsedVast {
        var wrapperCount = 0
        var inlineCount = 0
        val scripts = mutableListOf<VerificationScript>()
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }
            runCatching { factory.isXIncludeAware = false }
            runCatching { factory.isExpandEntityReferences = false }
            setSecureXmlFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
            setSecureXmlFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
            setSecureXmlFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
            setSecureXmlFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
            walkElements(document.documentElement) { element ->
                when (element.localTagName()) {
                    "Wrapper" -> wrapperCount += 1
                    "InLine" -> inlineCount += 1
                    "Verification" -> scripts += parseVerification(element)
                }
            }
            ParsedVast(wrapperCount, inlineCount, scripts, null)
        }.getOrElse { error ->
            ParsedVast(wrapperCount, inlineCount, scripts, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun parseVerification(element: Element): List<VerificationScript> {
        val vendor = element.attribute("vendor")
        val resources = mutableListOf<Element>()
        var parameters = ""
        var notExecutedUrl = ""
        walkElements(element) { child ->
            when (child.localTagName()) {
                "JavaScriptResource" -> resources += child
                "VerificationParameters" -> parameters = child.textContent.orEmpty().trim()
                "Tracking" -> if (child.attribute("event") == "verificationNotExecuted") {
                    notExecutedUrl = child.textContent.orEmpty().trim()
                }
            }
        }
        return resources.mapNotNull { resource ->
            val url = resource.textContent.orEmpty().trim()
            if (url.isBlank()) null else VerificationScript(
                responseIndex = 0,
                vendor = vendor,
                url = url,
                apiFramework = resource.attribute("apiFramework"),
                browserOptional = resource.attribute("browserOptional"),
                verificationParameters = parameters,
                notExecutedUrl = notExecutedUrl
            )
        }
    }

    private fun walkElements(root: Element, visitor: (Element) -> Unit) {
        visitor(root)
        val children = root.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child.nodeType == Node.ELEMENT_NODE) {
                walkElements(child as Element, visitor)
            }
        }
    }

    private fun Element.localTagName(): String = localName ?: tagName.substringAfter(':')

    private fun Element.attribute(name: String): String {
        val attributes = attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            val localName = attribute.localName ?: attribute.nodeName.substringAfter(':')
            if (localName == name) return attribute.nodeValue.orEmpty()
        }
        return ""
    }

    private fun setSecureXmlFeature(
        factory: DocumentBuilderFactory,
        feature: String,
        enabled: Boolean
    ) {
        runCatching { factory.setFeature(feature, enabled) }
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

    private fun gzipBase64(value: ByteArray): String {
        val compressed = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { gzip -> gzip.write(value) }
            output.toByteArray()
        }
        return BaseEncoding.base64().encode(compressed)
    }

    private data class Snapshot(
        val summary: String,
        val vastResponseCount: Int,
        val verificationScriptCount: Int,
        val omidStarted: Boolean,
        val omidFinished: Boolean
    )

    private data class VastResponse(
        val captureSource: String,
        val responseIndex: Int,
        val imaRequestId: String,
        val imaResponseId: String,
        val requestUrl: String,
        val requestType: String,
        val contentType: String,
        val errorCode: Int,
        val xmlLength: Int,
        val xmlSha256: String,
        val encodedXml: String,
        val wrapperCount: Int,
        val inlineCount: Int,
        val verificationScriptCount: Int,
        val parseError: String?
    )

    private data class ParsedVast(
        val wrapperCount: Int,
        val inlineCount: Int,
        val scripts: List<VerificationScript>,
        val error: String?
    )

    private data class VerificationScript(
        val responseIndex: Int,
        val vendor: String,
        val url: String,
        val apiFramework: String,
        val browserOptional: String,
        val verificationParameters: String,
        val notExecutedUrl: String
    )

    private data class WebResourceStatus(
        var requestCount: Int = 0,
        var loadCallbackCount: Int = 0,
        var pageStartedCount: Int = 0,
        var pageFinishedCount: Int = 0,
        var errorCount: Int = 0,
        var lastErrorCode: String = "",
        var lastError: String = ""
    )
}
