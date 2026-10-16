package com.smart.android.adsdk.universal.internal;

import static org.junit.Assert.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.smart.android.adsdk.universal.AdError;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.Test;

/** Verify the serialized bodies of all six MAC-bearing endpoints for both ad providers. */
public class BackendAndroidIdContractTest {
    @Test public void everyMacEndpointSendsTheRealAndroidIdForGoogleAndTcl() throws Exception {
        verify("0123456789abcdef", "0123456789abcdef");
    }

    @Test public void everyMacEndpointSendsAnEmptyStringWhenAndroidIdIsUnavailable() throws Exception {
        for (String missing : Arrays.asList("", null, "unknown_device")) {
            verify(missing, "");
        }
    }

    private static void verify(String androidId, String expected) throws Exception {
        for (boolean tcl : new boolean[] {false, true}) {
            String channel = tcl ? "tcl-channel" : "google-channel";
            DeviceInfo host = new DeviceInfo("actual.host", "2.1", 21L, androidId, "host-uuid",
                "AA:BB:CC:DD:EE:FF", "", "ua", "make", "model", "13", "en-US", 1920, 1080, 1920, 1080);
            List<String> paths = new CopyOnWriteArrayList<>();
            List<JsonObject> bodies = new CopyOnWriteArrayList<>();
            CountDownLatch captured = new CountDownLatch(6);
            CountDownLatch callbacks = new CountDownLatch(4);
            AtomicReference<Object> failure = new AtomicReference<>();
            Gson gson = new Gson();
            OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
                Buffer buffer = new Buffer(); chain.request().body().writeTo(buffer);
                paths.add(chain.request().url().encodedPath());
                bodies.add(JsonParser.parseString(buffer.readUtf8()).getAsJsonObject());
                captured.countDown();
                String response = "{\"code\":200,\"data\":{\"enabled\":true,\"skip_cmp\":true,"
                    + "\"authorized\":true,\"gam_config\":{\"ad_tag_url\":\"https://example.test/vast\"},"
                    + "\"consent_action\":\"ACCEPT_ALL\"}}";
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create(response, MediaType.get("application/json"))).build();
            }).build();
            try {
                new FlowControlClient(() -> host, http, gson, "https://example.test/")
                    .resolve(channel, new FlowControlResolver.Callback() {
                        @Override public void onAllowed(boolean skipCmp) { callbacks.countDown(); }
                        @Override public void onBlocked(String reason) { failure.set(reason); callbacks.countDown(); }
                        @Override public void onError(Throwable error) { failure.set(error); callbacks.countDown(); }
                    });
                new RemoteAdConfigClient(() -> host, http, gson, new RemoteAdConfigParser(gson), "https://example.test/", tcl)
                    .resolve(channel, "local-request", new RemoteAdConfigResolver.Callback() {
                        @Override public void onResolved(RemoteAdConfigResult result) {
                            if (!result.hasAd()) failure.set(result.getSkipReason());
                            callbacks.countDown();
                        }
                        @Override public void onError(AdError error) { failure.set(error); callbacks.countDown(); }
                    });
                TclCmpBackendClient cmp = tcl
                    ? new TclCmpBackendClient(http, gson, "https://example.test/", () -> host, () -> 33)
                    : TclCmpBackendClient.forGoogle(http, gson, "https://example.test/", () -> host, () -> 33);
                cmp.request(channel, true, (decision, error) -> {
                    if (error != null) failure.set(error);
                    callbacks.countDown();
                });
                cmp.report(channel, "ACCEPT_ALL", error -> {
                    if (error != null) failure.set(error);
                    callbacks.countDown();
                });
                Hq008AdReporter reporter = new Hq008AdReporter(() -> host, http, gson, channel, "https://example.test/", "test-engine");
                reporter.requested("local-request", 0L, 640, 480, Collections.emptyMap());
                reporter.consentLog("AD_PHASE_COMPLETED", "completed", new HashMap<>());
                assertTrue(captured.await(3, TimeUnit.SECONDS));
                assertTrue(callbacks.await(3, TimeUnit.SECONDS));
                assertNull(failure.get()); assertEquals(6, bodies.size());
                Set<String> expectedPaths = new HashSet<>(Arrays.asList(
                    "/api/v2/ad/sdk/flow-control", "/api/v2/ad/sdk/authorize", "/api/v2/ad/consent-popup",
                    "/api/v2/ad/consent-report", "/api/v2/ad/report", "/api/v2/ad/consent-log-report"));
                assertEquals(expectedPaths, new HashSet<>(paths));
                for (JsonObject body : bodies) {
                    assertEquals(channel, body.get("channel_id").getAsString());
                    assertEquals("AA:BB:CC:DD:EE:FF", body.get("mac").getAsString());
                    assertTrue(body.get("androidid").getAsJsonPrimitive().isString());
                    assertEquals(expected, body.get("androidid").getAsString());
                }
            } finally {
                http.dispatcher().cancelAll();
                http.connectionPool().evictAll();
                http.dispatcher().executorService().shutdown();
            }
        }
    }
}
