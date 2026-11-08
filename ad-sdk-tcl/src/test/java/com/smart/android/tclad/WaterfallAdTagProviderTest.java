package com.smart.android.tclad;

import static org.junit.Assert.*;
import com.google.gson.JsonParser;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.Test;

public final class WaterfallAdTagProviderTest {
    @Test public void callTimeoutReportsFailureWithoutWaitingForTheSessionWatchdog() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            var client = new OkHttpClient.Builder().callTimeout(150, TimeUnit.MILLISECONDS).build();
            var provider = new WaterfallAdTagProvider(server.url("/resolve").toString(), WaterfallRequestTest.template(), client);
            var future = new CompletableFuture<String>();
            var responses = new java.util.concurrent.atomic.AtomicInteger();
            provider.resolveCancellable(null, new AdTagProvider.Callback() {
                @Override public void onHttpResponse(int status) { responses.incrementAndGet(); }
                @Override public void onResolved(String url) { future.complete("UNEXPECTED:" + url); }
                @Override public void onError(int code) { future.complete("ERROR:" + code); }
            });
            assertEquals("ERROR:301", future.get(3, TimeUnit.SECONDS));
            assertEquals(0, responses.get());
        }
    }
    private AdTagProvider.Callback result(CompletableFuture<String> future) {
        return new AdTagProvider.Callback() {
            @Override public void onResolved(String url) { future.complete(url); }
            @Override public void onError(int code) { future.complete("ERROR:" + code); }
        };
    }
    private String response(String url) {
        return "{\"code\":100000,\"data\":{\"adsUrl\":\"" + url + "\"}}";
    }
    @Test public void posts58FieldsAndReturnsTagWithoutOverwritingBackendValues() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String tag = server.url("/tag").toString() + "?" + WaterfallRequestTest.resource("tcl-waterfall-tag-query.txt");
            server.enqueue(new MockResponse().setBody(response(tag)));
            var provider = new WaterfallAdTagProvider(server.url("/resolve").toString(), WaterfallRequestTest.template());
            var future = new CompletableFuture<String>();
            provider.resolve(WaterfallRequestTest.capturedHostParams(), result(future));
            String resolved = future.get(3, TimeUnit.SECONDS);
            var post = server.takeRequest(3, TimeUnit.SECONDS);
            assertNotNull(post);
            assertEquals("POST", post.getMethod());
            WaterfallRequestTest.assertCapturedBody(post.getBody().readUtf8());
            assertTrue(post.getHeader("Content-Type").startsWith("application/json"));
            assertEquals(WaterfallRequestTest.template().getValues().get("userAgent"), post.getHeader("User-Agent"));
            HttpUrl original = HttpUrl.get(tag);
            HttpUrl requested = HttpUrl.get(resolved);
            assertNotNull(requested);
            assertEquals(33, original.querySize());
            assertEquals(34, requested.querySize());
            for (String key : original.queryParameterNames())
                assertEquals(key, original.queryParameterValues(key), requested.queryParameterValues(key));
            assertEquals("Suga Babies", requested.queryParameter("content_title"));
            assertEquals("Suga Babies", requested.queryParameter("channel_name"));
            assertEquals("", requested.queryParameter("skip"));
            assertEquals("", requested.queryParameter("gdpr_consent"));
            assertEquals(requested.queryParameter("requestId"), UUID.fromString(requested.queryParameter("requestId")).toString());
            assertTrue(requested.toString().contains("content_title=Suga%20Babies"));
            assertTrue(requested.toString().contains("app_store_url=https%3A%2F%2F"));
        }
    }
    @Test public void eachResolutionReplacesRequestIdAndUsesCurrentConsentWithoutMutatingTemplate() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            var template = WaterfallRequestTest.template();
            var provider = new WaterfallAdTagProvider(server.url("/resolve").toString(), template);
            String previous = null;
            for (String consent : new String[]{"current-consent", ""}) {
                server.enqueue(new MockResponse().setBody(response(server.url("/tag?requestId=old&requestId=older&slot=1").toString())));
                var future = new CompletableFuture<String>();
                provider.resolve(new RequestParams.Builder().setGdprConsent(consent).build(), result(future));
                HttpUrl resolved = HttpUrl.get(future.get(3, TimeUnit.SECONDS));
                assertEquals(1, resolved.queryParameterValues("requestId").size());
                assertNotEquals("old", resolved.queryParameter("requestId"));
                assertNotEquals(previous, resolved.queryParameter("requestId"));
                previous = resolved.queryParameter("requestId");
                var request = server.takeRequest(3, TimeUnit.SECONDS);
                assertNotNull(request);
                assertEquals(consent, JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject().get("gdprConsent").getAsString());
            }
            assertEquals("", template.getValues().get("gdprConsent"));
        }
    }
    @Test public void httpAndInvalidResponseFailuresDoNotProduceVastRequests() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            var provider = new WaterfallAdTagProvider(server.url("/resolve").toString(), WaterfallRequestTest.template());
            MockResponse[] failures = {new MockResponse().setResponseCode(503),
                new MockResponse().setBody("not json"), new MockResponse().setBody("{\"code\":400001}"),
                new MockResponse().setBody("{\"code\":100000,\"data\":{}}"),
                new MockResponse().setBody(response("file:///local.xml"))};
            for (int i = 0; i < failures.length; i++) {
                server.enqueue(failures[i]);
                var future = new CompletableFuture<String>();
                provider.resolve(null, result(future));
                String failure = future.get(3, TimeUnit.SECONDS);
                assertTrue(failure.startsWith("ERROR:"));
                if (i == 0) assertEquals("ERROR:301", failure);
            }
            assertEquals(failures.length, server.getRequestCount());
        }
    }
    @Test public void cancellationReleasesAnInFlightRequestWithoutInvokingCallbacks() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            var provider = new WaterfallAdTagProvider(server.url("/resolve").toString(), WaterfallRequestTest.template());
            var future = new CompletableFuture<String>();
            var pending = provider.resolveCancellable(null, result(future));
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS));
            pending.cancel();
            try { future.get(200, TimeUnit.MILLISECONDS); fail("Cancelled call delivered a callback"); }
            catch (TimeoutException expected) { }
        }
    }
    @Test public void fullResponseKeepsNativeMetadataAndUnmodifiedUrlForSdkEntry() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String tag = "https://vast.example/ad?requestId=old&requestId=older&sig=a%2Bb";
            String raw = "{\"code\":100000,\"data\":{\"adsUrl\":\"" + tag
                    + "\",\"beaconingType\":2,\"adType\":1,\"adPlatformCode\":\"0\","
                    + "\"adUnitID\":\"slot-008\",\"guaranteeLink\":\"https://vast.example/fallback\"}}";
            server.enqueue(new MockResponse().setBody(raw));
            var provider = new WaterfallAdTagProvider(server.url("/resolve").toString(), WaterfallRequestTest.template());
            var future = new CompletableFuture<ResolvedAd>();
            provider.resolve(null, new AdTagProvider.Callback() {
                @Override public void onResolved(String url) { future.completeExceptionally(new AssertionError("Full response callback required")); }
                @Override public void onResolvedAd(ResolvedAd input) { future.complete(input); }
                @Override public void onError(int code) { future.completeExceptionally(new AssertionError("error " + code)); }
            });
            var data = future.get(3, TimeUnit.SECONDS).toOriginalResponse().getData();
            assertEquals(tag, data.getAdsUrl());
            assertEquals("slot-008", data.getAdUnitID());
            assertEquals("0", data.getAdPlatformCode());
            assertEquals("https://vast.example/fallback", data.getGuaranteeLink());
        }
    }
    @Test public void unsuccessfulHttpStillConfirmsOneRequestBeforeProviderError() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));
            var provider = new WaterfallAdTagProvider(server.url("/resolve").toString(), WaterfallRequestTest.template());
            var state = new com.smart.android.tclad.runtime.TclSdkRequestState();
            var confirmed = new java.util.concurrent.atomic.AtomicInteger();
            var future = new CompletableFuture<Integer>();
            provider.resolve(null, new AdTagProvider.Callback() {
                @Override public void onHttpRequest() { state.begin(1L); }
                @Override public void onHttpResponse(int status) {
                    state.responded(2L, status);
                    if (state.takeRequestedReport() != null) confirmed.incrementAndGet();
                }
                @Override public void onResolved(String url) { future.completeExceptionally(new AssertionError("HTTP failure resolved a URL")); }
                @Override public void onError(int code) { future.complete(code); }
            });
            assertEquals(Integer.valueOf(301), future.get(3, TimeUnit.SECONDS));
            assertEquals(1, confirmed.get());
            assertEquals(503, state.diagnostics().get("sdkRequestHttpStatus"));
            assertEquals("REQUESTED", state.diagnostics().get("sdkRequestOutcome"));
        }
    }

}
