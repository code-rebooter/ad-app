package com.smart.android.adsdk.internal;

import static org.junit.Assert.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.smart.android.adsdk.AdError;
import com.smart.android.adsdk.AdErrorCode;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.Test;

public class TclCmpBackendClientTest {
    private static final String CHANNEL = "GOOGLE_AD_TV_LOCKSCREEN_HQ002_TCL";
    private static final String BASE_URL = "https://shared-api.example.test";

    @Test public void popupUsesSharedDomainAndMainAppRequestFields() throws Exception {
        List<Request> requests = new CopyOnWriteArrayList<>();
        DecisionRecorder result = request(client(requests, 200,
            "{\"code\":100000,\"data\":{\"consent_action\":\"REJECT\"}}", () -> device("AA:BB:CC:DD:EE:FF")), true);

        assertNull(result.error);
        assertEquals("REJECT", result.decision.action);
        assertEquals(1, requests.size());
        Request request = requests.get(0);
        assertEquals(BASE_URL + "/api/v2/ad/consent-popup", request.url().toString());
        assertEquals("POST", request.method());
        JsonObject body = requestBody(request);
        assertEquals(4, body.size());
        assertEquals(CHANNEL, body.get("channel_id").getAsString());
        assertEquals("AA:BB:CC:DD:EE:FF", body.get("mac").getAsString());
        assertEquals(123, body.get("ad_version").getAsLong());
        assertTrue(body.get("consent_expired").getAsBoolean());
    }

    @Test public void popupUsesMainAppMacFallbackAndDoesNotLoseFalseExpiry() throws Exception {
        List<Request> requests = new CopyOnWriteArrayList<>();
        request(client(requests, 200, "{\"consent_action\":\"ACCEPT_ALL\"}", () -> device("  ")), false);
        JsonObject body = requestBody(requests.get(0));
        assertEquals("00:00:00:00:00:00", body.get("mac").getAsString());
        assertFalse(body.get("consent_expired").getAsBoolean());
    }

    @Test public void saveSettingsDecodesAllSevenBackendIdLists() throws Exception {
        String payload = "{\"purpose_consent_ids\":[1,2],\"purpose_li_ids\":[3],"
            + "\"custom_purpose_consent_ids\":[4],\"custom_purpose_li_ids\":[5],"
            + "\"special_feature_ids\":[6],\"vendor_consent_ids\":[7,8],\"vendor_li_ids\":[9]}";
        DecisionRecorder result = request(client(new CopyOnWriteArrayList<>(), 200,
            "{\"code\":200,\"data\":{\"consent_action\":\"SAVE_SETTINGS\",\"consent_payload\":" + payload + "}}"), false);

        assertNull(result.error);
        assertEquals("SAVE_SETTINGS", result.decision.action);
        TclCmpBackendClient.Payload actual = result.decision.payload;
        assertEquals(Arrays.asList(1, 2), actual.purposeConsentIds);
        assertEquals(Collections.singletonList(3), actual.purposeLiIds);
        assertEquals(Collections.singletonList(4), actual.customPurposeConsentIds);
        assertEquals(Collections.singletonList(5), actual.customPurposeLiIds);
        assertEquals(Collections.singletonList(6), actual.specialFeatureIds);
        assertEquals(Arrays.asList(7, 8), actual.vendorConsentIds);
        assertEquals(Collections.singletonList(9), actual.vendorLiIds);
    }

    @Test public void omittedSettingsListsAreEmptyAndBareDecisionsAreSupported() throws Exception {
        DecisionRecorder result = request(client(new CopyOnWriteArrayList<>(), 200,
            "{\"consent_action\":\"SAVE_SETTINGS\",\"consent_payload\":{}}"), false);
        assertNull(result.error);
        TclCmpBackendClient.Payload actual = result.decision.payload;
        assertTrue(actual.purposeConsentIds.isEmpty());
        assertTrue(actual.purposeLiIds.isEmpty());
        assertTrue(actual.customPurposeConsentIds.isEmpty());
        assertTrue(actual.customPurposeLiIds.isEmpty());
        assertTrue(actual.specialFeatureIds.isEmpty());
        assertTrue(actual.vendorConsentIds.isEmpty());
        assertTrue(actual.vendorLiIds.isEmpty());
    }

    @Test public void supportsEveryExecutableBackendAction() throws Exception {
        for (String action : Arrays.asList("ACCEPT_ALL", "REJECT", "MAYBE_LATER", "SKIP_ALREADY_DECIDED")) {
            DecisionRecorder result = request(client(new CopyOnWriteArrayList<>(), 200,
                "{\"code\":100000,\"data\":{\"consent_action\":\"" + action + "\"}}"), false);
            assertNull(action, result.error);
            assertEquals(action, result.decision.action);
        }
    }

    @Test public void invalidDecisionsCannotBecomeAcceptAll() throws Exception {
        for (String data : Arrays.asList("{}", "{\"consent_action\":null}",
            "{\"consent_action\":\"unexpected\"}", "{\"consent_action\":\"SAVE_SETTINGS\"}",
            "{\"consent_action\":\"SAVE_SETTINGS\",\"consent_payload\":null}")) {
            String body = "{\"code\":100000,\"data\":" + data + "}";
            DecisionRecorder result = request(client(new CopyOnWriteArrayList<>(), 200, body), false);
            assertNull(data, result.decision);
            assertNotNull(data, result.error);
            assertEquals(AdErrorCode.CONFIG_PARSE_ERROR, result.error.getCode());
            assertEquals(body, result.error.getResponseBody());
        }
    }

    @Test public void popupPreservesBackendBusinessFailure() throws Exception {
        String body = "{\"code\":451002,\"message\":\"consent disabled\",\"data\":null}";
        DecisionRecorder result = request(client(new CopyOnWriteArrayList<>(), 200, body), false);
        assertNull(result.decision);
        assertError(result.error, "API", "451002", "consent disabled", body);
    }

    @Test public void popupPreservesHttpFailureBodyAndReason() throws Exception {
        String body = "{\"code\":812,\"message\":\"gateway unavailable\"}";
        DecisionRecorder result = request(client(new CopyOnWriteArrayList<>(), 503, body), false);
        assertNull(result.decision);
        assertError(result.error, "HTTP", "503", "gateway unavailable", body);
    }

    @Test public void reportUsesMainAppRequestFieldsAndAcceptsBothSuccessCodes() throws Exception {
        for (int code : new int[] {100000, 200}) {
            List<Request> requests = new CopyOnWriteArrayList<>();
            ReportRecorder result = report(client(requests, 200, "{\"code\":" + code + ",\"data\":{}}"), "SAVE_SETTINGS");
            assertNull(result.error);
            assertEquals(1, requests.size());
            Request request = requests.get(0);
            assertEquals(BASE_URL + "/api/v2/ad/consent-report", request.url().toString());
            assertEquals("POST", request.method());
            JsonObject body = requestBody(request);
            assertEquals(5, body.size());
            assertEquals(CHANNEL, body.get("channel_id").getAsString());
            assertEquals("AA:BB:CC:DD:EE:FF", body.get("mac").getAsString());
            assertEquals(123, body.get("ad_version").getAsLong());
            assertEquals(30, body.get("android_sdk_version").getAsInt());
            assertEquals("SAVE_SETTINGS", body.get("consent_action").getAsString());
        }
    }

    @Test public void reportRejectsBusinessFailureEvenWhenHttpSucceeded() throws Exception {
        String body = "{\"code\":460001,\"message\":\"report refused\"}";
        ReportRecorder result = report(client(new CopyOnWriteArrayList<>(), 200, body), "REJECT");
        assertError(result.error, "API", "460001", "report refused", body);
    }

    @Test public void reportPreservesHttpFailure() throws Exception {
        String body = "{\"message\":\"report temporarily unavailable\"}";
        ReportRecorder result = report(client(new CopyOnWriteArrayList<>(), 502, body), "REJECT");
        assertError(result.error, "HTTP", "502", "report temporarily unavailable", body);
    }

    @Test public void malformedPopupAndReportRetainTheirResponseBodies() throws Exception {
        for (String body : Arrays.asList("{broken", "[]", "null", "")) {
            DecisionRecorder decision = request(client(new CopyOnWriteArrayList<>(), 200, body), false);
            assertNull(decision.decision);
            assertNotNull(decision.error);
            assertEquals(AdErrorCode.CONFIG_PARSE_ERROR, decision.error.getCode());
            assertEquals(body, decision.error.getResponseBody());
            ReportRecorder report = report(client(new CopyOnWriteArrayList<>(), 200, body), "REJECT");
            assertNotNull(report.error);
            assertEquals(AdErrorCode.CONFIG_PARSE_ERROR, report.error.getCode());
            assertEquals(body, report.error.getResponseBody());
        }
    }

    @Test public void networkErrorsRetainTheirOriginAndMessage() throws Exception {
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            throw new IOException("connection interrupted");
        }).build();
        TclCmpBackendClient client = new TclCmpBackendClient(http, new Gson(), BASE_URL,
            () -> device(""), () -> 30);
        assertError(request(client, false).error, "NETWORK", null, "connection interrupted", null);
        assertError(report(client, "REJECT").error, "NETWORK", null, "connection interrupted", null);
    }

    private static TclCmpBackendClient client(List<Request> requests, int status, String body) {
        return client(requests, status, body, () -> device("AA:BB:CC:DD:EE:FF"));
    }

    private static TclCmpBackendClient client(List<Request> requests, int status, String body,
        Supplier<DeviceInfo> deviceInfo) {
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.add(chain.request());
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("HTTP status " + status)
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
        }).build();
        return new TclCmpBackendClient(http, new Gson(), BASE_URL, deviceInfo, () -> 30);
    }

    private static DeviceInfo device(String mac) {
        return new DeviceInfo("test.host", "1.2.3", 123, "", "", mac,
            "", "", "", "", "", "", 0, 0, 0, 0);
    }

    private static JsonObject requestBody(Request request) throws IOException {
        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        return JsonParser.parseString(buffer.readUtf8()).getAsJsonObject();
    }

    private static DecisionRecorder request(TclCmpBackendClient client, boolean expired) throws Exception {
        DecisionRecorder recorder = new DecisionRecorder();
        client.request(CHANNEL, expired, recorder);
        assertTrue("CMP decision callback timed out", recorder.done.await(3, TimeUnit.SECONDS));
        return recorder;
    }

    private static ReportRecorder report(TclCmpBackendClient client, String action) throws Exception {
        ReportRecorder recorder = new ReportRecorder();
        client.report(CHANNEL, action, recorder);
        assertTrue("CMP report callback timed out", recorder.done.await(3, TimeUnit.SECONDS));
        return recorder;
    }

    private static void assertError(AdError error, String source, String code, String message, String body) {
        assertNotNull(error);
        assertEquals(source, error.getSource());
        assertEquals(code, error.getOriginalCode());
        assertEquals(message, error.getMessage());
        assertEquals(body, error.getResponseBody());
    }

    private static final class DecisionRecorder implements TclCmpBackendClient.DecisionCallback {
        final CountDownLatch done = new CountDownLatch(1);
        TclCmpBackendClient.Decision decision;
        AdError error;
        @Override public void onResult(TclCmpBackendClient.Decision decision, AdError error) {
            this.decision = decision;
            this.error = error;
            done.countDown();
        }
    }

    private static final class ReportRecorder implements TclCmpBackendClient.ReportCallback {
        final CountDownLatch done = new CountDownLatch(1);
        AdError error;
        @Override public void onResult(AdError error) {
            this.error = error;
            done.countDown();
        }
    }
}
