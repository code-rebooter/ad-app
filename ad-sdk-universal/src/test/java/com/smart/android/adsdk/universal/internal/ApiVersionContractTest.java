package com.smart.android.adsdk.universal.internal;

import static org.junit.Assert.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.smart.android.adsdk.universal.AdError;
import com.smart.android.adsdk.universal.AdSdk;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;

public class ApiVersionContractTest {
    private static final long HOST_VERSION_CODE = 4_000_000_123L;
    private static final String HOST_VERSION_NAME = "9.8.7";

    static RequestVersions hostVersions() {
        return new RequestVersions(HOST_VERSION_CODE, HOST_VERSION_NAME);
    }

    static DeviceInfo host() {
        return new DeviceInfo("actual.host.package", HOST_VERSION_NAME, HOST_VERSION_CODE,
            "android-id", "host-uuid", "AA:BB:CC:DD:EE:FF", "", "host-ua", "host-make",
            "host-model", "13", "en-US", 1920, 1080, 1920, 1080);
    }

    static void assertVersions(JsonObject body) {
        assertTrue("ad_version must be a JSON number", body.get("ad_version").getAsJsonPrimitive().isNumber());
        assertEquals(HOST_VERSION_CODE, body.get("ad_version").getAsLong());
        assertTrue("ad_version_name must be a JSON string", body.get("ad_version_name").getAsJsonPrimitive().isString());
        assertEquals(HOST_VERSION_NAME, body.get("ad_version_name").getAsString());
        assertTrue("sdk_version must be a JSON string", body.get("sdk_version").getAsJsonPrimitive().isString());
        assertFalse(AdSdk.getSdkVersion().isEmpty());
        assertEquals(AdSdk.getSdkVersion(), body.get("sdk_version").getAsString());
    }

    @Test public void flowControlSendsHostAndSdkVersions() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"code\":100000,\"data\":{\"enabled\":true,\"skip_cmp\":true}}"));
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            new FlowControlClient(ApiVersionContractTest::host, new OkHttpClient(), new Gson(), server.url("/").toString())
                .resolve("google-channel", new FlowControlResolver.Callback() {
                    @Override public void onAllowed(boolean skipCmp) { done.countDown(); }
                    @Override public void onBlocked(String reason) { onError(new AssertionError(reason)); }
                    @Override public void onError(Throwable error) { failure.set(error); done.countDown(); }
                });
            assertTrue(done.await(3, TimeUnit.SECONDS)); assertNull(failure.get());
            JsonObject body = takeBody(server, "/api/v2/ad/sdk/flow-control");
            assertVersions(body);
            assertEquals("google-channel", body.get("channel_id").getAsString());
            assertTrue(body.has("android_sdk_version"));
        }
    }

    @Test public void googleAuthorizeReturnsLinkAndSendsRealHostVersionsInOneRequest() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"code\":100000,\"data\":{\"authorized\":true,\"request_id\":\"server-id\",\"ad_tag_url\":\"https://example.test/vast\"}}"));
            Gson gson = new Gson();
            RemoteAdConfigClient client = new RemoteAdConfigClient(ApiVersionContractTest::host,
                new OkHttpClient(), gson, new RemoteAdConfigParser(gson), server.url("/").toString());
            ResultRecorder result = new ResultRecorder();
            client.resolve("google-channel", "local-id", result);
            result.assertAd();
            JsonObject authorize = takeBody(server, "/api/v2/ad/sdk/authorize");
            assertVersions(authorize);
            assertEquals("local-id", authorize.get("request_id").getAsString());
            assertEquals("actual.host.package", authorize.get("app_id").getAsString());
            assertEquals("google-channel", authorize.get("channel_id").getAsString());
            assertEquals("AA:BB:CC:DD:EE:FF", authorize.get("mac").getAsString());
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void tclAuthorizeSendsHostVersionsWithoutRequestingGoogle() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"code\":100000,\"data\":{\"authorized\":true}}"));
            Gson gson = new Gson();
            RemoteAdConfigClient client = new RemoteAdConfigClient(ApiVersionContractTest::host,
                new OkHttpClient(), gson, new RemoteAdConfigParser(gson), server.url("/").toString(), true);
            ResultRecorder result = new ResultRecorder();
            client.resolve("tcl-channel", "local-id", result);
            result.assertAd();
            JsonObject body = takeBody(server, "/api/v2/ad/sdk/authorize");
            assertVersions(body);
            assertEquals("tcl-channel", body.get("channel_id").getAsString());
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void stateAndDebugLogReportsCarryVersionsForBothChannels() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            for (String channel : Arrays.asList("google-channel", "tcl-channel")) {
                server.enqueue(new MockResponse().setBody("{\"code\":100000}"));
                server.enqueue(new MockResponse().setBody("{\"code\":100000}"));
                Hq008AdReporter reporter = new Hq008AdReporter(ApiVersionContractTest::host,
                    new OkHttpClient(), new Gson(), channel, server.url("/").toString(), "test-engine");
                reporter.requested("request-id", 123L, 640, 480, Collections.emptyMap());
                Map<String, Object> summary = new HashMap<>();
                summary.put("steps", Collections.emptyList());
                reporter.consentLog("AD_PHASE_COMPLETED", "completed", summary);
                Set<String> paths = new HashSet<>();
                for (int i = 0; i < 2; i++) {
                    RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
                    assertNotNull(request);
                    paths.add(request.getPath());
                    JsonObject body = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
                    assertVersions(body);
                    assertEquals(channel, body.get("channel_id").getAsString());
                    if (request.getPath().endsWith("/report")) {
                        assertEquals("request-id", body.get("request_id").getAsString());
                        assertEquals("REQUESTED", body.get("message").getAsString());
                    } else {
                        assertEquals("AD_PHASE_COMPLETED", body.get("event_type").getAsString());
                    }
                }
                assertEquals(new HashSet<>(Arrays.asList("/api/v2/ad/report", "/api/v2/ad/consent-log-report")), paths);
            }
        }
    }

    private static JsonObject takeBody(MockWebServer server, String path) throws Exception {
        RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(request); assertEquals(path, request.getPath());
        assertEquals("POST", request.getMethod());
        return JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
    }

    private static final class ResultRecorder implements RemoteAdConfigResolver.Callback {
        final CountDownLatch done = new CountDownLatch(1);
        RemoteAdConfigResult result;
        AdError error;
        @Override public void onResolved(RemoteAdConfigResult value) { result = value; done.countDown(); }
        @Override public void onError(AdError value) { error = value; done.countDown(); }
        void assertAd() throws Exception {
            assertTrue(done.await(3, TimeUnit.SECONDS)); assertNull(error);
            assertNotNull(result); assertTrue(result.hasAd());
        }
    }
}
