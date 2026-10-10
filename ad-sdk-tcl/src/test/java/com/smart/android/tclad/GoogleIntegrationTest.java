package com.smart.android.tclad;

import static org.junit.Assert.*;
import com.google.gson.JsonParser;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.Test;

public final class GoogleIntegrationTest {
    private GoogleGamClient.Callback callback(CompletableFuture<GoogleGamClient.Config> future) {
        return new GoogleGamClient.Callback() {
            @Override public void onResolved(GoogleGamClient.Config config) { future.complete(config); }
            @Override public void onError(int code) { future.completeExceptionally(new IllegalStateException("code=" + code)); }
        };
    }
    @Test public void existingTwoFieldEndpointSuppliesGoogleTagBeforeOriginalPipeline() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String tag = server.url("/gampad/ads?iu=%2Fpublisher%2Fslot&sz=640x360&output=xml_vast4&correlator=&cust_params=genre%3DDrama%26language%3Den").toString();
            server.enqueue(new MockResponse().setBody("{\"code\":100000,\"data\":{\"ad_tag_url\":\"" + tag
                + "\",\"ad_load_timeout_ms\":12000,\"ad_startup_timeout_ms\":25000}}"));
            var future = new CompletableFuture<GoogleGamClient.Config>();
            new GoogleGamClient(server.url("/api/v2/ad/google-gam/resolve").toString())
                .resolve("GOOGLE_AD_TV_DESKTOP", "11:22:33:44:55:66", callback(future));
            var config = future.get(3, TimeUnit.SECONDS);
            assertNotNull(config);
            assertEquals(tag, config.getAdTagUrl());
            assertEquals(12000, config.getAdLoadTimeoutMs());
            assertEquals(25000, config.getAdStartupTimeoutMs());
            String resolved = new GoogleAdTagParameters.Builder().setApplication("com.example.player", "TV Player")
                .setAutoplay(true).setMuted(true).build().applyTo(config.getAdTagUrl());
            var post = server.takeRequest(3, TimeUnit.SECONDS);
            assertNotNull(post);
            assertEquals("POST", post.getMethod());
            assertEquals("/api/v2/ad/google-gam/resolve", post.getPath());
            var body = JsonParser.parseString(post.getBody().readUtf8()).getAsJsonObject();
            assertEquals(2, body.size());
            assertEquals("GOOGLE_AD_TV_DESKTOP", body.get("channel_id").getAsString());
            assertEquals("11:22:33:44:55:66", body.get("mac").getAsString());
            var url = HttpUrl.get(resolved); assertNotNull(url);
            assertTrue(Long.parseLong(url.queryParameter("correlator")) > 0);
            assertEquals("com.example.player", url.queryParameter("msid"));
            assertEquals("TV Player", url.queryParameter("an"));
            assertEquals("1", url.queryParameter("vpmute"));
            assertEquals("auto", url.queryParameter("vpa"));
            assertEquals("xml_vast4", url.queryParameter("output"));
            assertEquals("genre=Drama&language=en", url.queryParameter("cust_params"));
            assertNull(url.queryParameter("requestId"));
            assertNull(url.queryParameter("application"));
            assertEquals(1, server.getRequestCount());
        }
    }
    @Test public void correlatorIsPositiveRandomAndNotDuplicatedForEachNewOpportunity() {
        var parameters = new GoogleAdTagParameters.Builder().build();
        var ids = new HashSet<String>();
        for (int i = 0; i < 100; i++) {
            var url = HttpUrl.get(parameters.applyTo("https://pubads.g.doubleclick.net/gampad/ads?correlator=[correlator]&correlator=old"));
            assertEquals(1, url.queryParameterValues("correlator").size());
            assertTrue(Long.parseLong(url.queryParameter("correlator")) > 0);
            assertTrue(ids.add(url.queryParameter("correlator")));
        }
    }
    @Test public void currentConsentAdvertisingIdAndMutedStateReplaceStaleValuesWithCorrectEncoding() {
        String base = "https://pubads.g.doubleclick.net/gampad/ads?rdid=stale&gdpr_consent=old&vpmute=1&description_url=https%3A%2F%2Fcontent.example%2Fwatch%3Fid%3D1";
        var first = HttpUrl.get(new GoogleAdTagParameters.Builder().setMuted(false)
            .setAndroidAdvertisingId("00000000-0000-0000-0000-000000000001", false)
            .setGdprConsent(true, "current+consent/=value").build().applyTo(base));
        assertEquals("0", first.queryParameter("vpmute"));
        assertEquals("adid", first.queryParameter("idtype"));
        assertEquals("0", first.queryParameter("is_lat"));
        assertEquals("current+consent/=value", first.queryParameter("gdpr_consent"));
        assertEquals("https://content.example/watch?id=1", first.queryParameter("description_url"));
        var restricted = HttpUrl.get(new GoogleAdTagParameters.Builder().setAndroidAdvertisingId("hidden-id", true)
            .setGdprConsent(true, "").build().applyTo(base));
        assertNull(restricted.queryParameter("rdid"));
        assertEquals("1", restricted.queryParameter("is_lat"));
        assertNull(restricted.queryParameter("gdpr_consent"));
        var unknown = HttpUrl.get(new GoogleAdTagParameters.Builder().clearAdvertisingId().build().applyTo(base));
        assertNull(unknown.queryParameter("rdid"));
        assertNull(unknown.queryParameter("is_lat"));
    }
    @Test public void resolvesExistingDataResultAndBareFormatsAndDefaultsInvalidTimeouts() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String data = "{\"ad_tag_url\":\"https://pubads.g.doubleclick.net/gampad/ads\",\"ad_load_timeout_ms\":-1,\"ad_startup_timeout_ms\":999999999999}";
            for (String body : new String[]{data, "{\"code\":200,\"data\":" + data + "}", "{\"code\":100000,\"result\":" + data + "}"}) {
                server.enqueue(new MockResponse().setBody(body));
                var future = new CompletableFuture<GoogleGamClient.Config>();
                new GoogleGamClient(server.url("/resolve").toString()).resolve("channel", null, callback(future));
                var config = future.get(3, TimeUnit.SECONDS);
                assertEquals(20000, config.getAdLoadTimeoutMs());
                assertEquals(35000, config.getAdStartupTimeoutMs());
                var request = server.takeRequest(3, TimeUnit.SECONDS); assertNotNull(request);
                assertEquals("00:00:00:00:00:00", JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject().get("mac").getAsString());
            }
        }
    }
    @Test public void emptyTagIsNoAdAndMalformedResponsesAreErrors() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            var client = new GoogleGamClient(server.url("/resolve").toString());
            for (String body : new String[]{"{\"code\":100000,\"data\":null}", "{\"ad_tag_url\":\" \"}"}) {
                server.enqueue(new MockResponse().setBody(body));
                var future = new CompletableFuture<GoogleGamClient.Config>();
                client.resolve("channel", "mac", callback(future));
                assertNull(future.get(3, TimeUnit.SECONDS));
            }
            for (String body : new String[]{"invalid", "{\"code\":400001}", "{\"ad_tag_url\":\"file:///bad.xml\"}"}) {
                server.enqueue(new MockResponse().setBody(body));
                var future = new CompletableFuture<GoogleGamClient.Config>();
                client.resolve("channel", "mac", callback(future));
                try { future.get(3, TimeUnit.SECONDS); fail(); }
                catch (java.util.concurrent.ExecutionException expected) { assertEquals("code=900", expected.getCause().getMessage()); }
            }
        }
    }
    @Test public void cancellingResolveSuppressesLateCallbacks() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            var future = new CompletableFuture<GoogleGamClient.Config>();
            var call = new GoogleGamClient(server.url("/resolve").toString()).resolve("channel", "mac", callback(future));
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS));
            call.cancel();
            try { future.get(200, TimeUnit.MILLISECONDS); fail(); } catch (TimeoutException expected) { }
        }
    }
}
