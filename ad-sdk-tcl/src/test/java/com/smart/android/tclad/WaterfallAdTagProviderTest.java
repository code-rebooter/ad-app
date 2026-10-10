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
}
