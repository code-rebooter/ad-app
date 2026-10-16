package com.smart.android.adsdk.universal.internal;

import static org.junit.Assert.*;
import com.google.gson.*;
import com.smart.android.adsdk.universal.AdError;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.Test;

public class MergedAuthorizationTest {
    @Test public void googleLinkPolicyAndIdentifiersComeFromOneAuthorizeRequest() throws Exception {
        try (MockWebServer server=new MockWebServer()) {
            String url="https://pubads.example.test/vast?correlator=&custom=a%2Bb&requestId=original";
            JsonObject data=new JsonObject();data.addProperty("authorized",true);
            JsonObject gam=new JsonObject();gam.addProperty("ad_tag_url",url);data.add("gam_config",gam);
            data.addProperty("request_id","server-id");data.addProperty("hidden_mode",false);data.addProperty("sound_mode",true);
            data.addProperty("next_request_seconds",120);gam.addProperty("ad_load_timeout_ms",4567);
            gam.addProperty("ad_startup_timeout_ms",8910);data.addProperty("ad_callback_timeout_seconds",30);
            JsonObject root=new JsonObject();root.addProperty("code",100000);root.add("data",data);
            server.enqueue(new MockResponse().setBody(root.toString()));
            Recorder recorder=new Recorder();client(server,false).resolve("google-channel","local-id",recorder);recorder.await();
            assertTrue(recorder.result.hasAd());assertEquals(1,server.getRequestCount());
            AdPlaybackConfig config=recorder.result.getConfig();
            assertEquals(url,config.getAdTagUrl());assertEquals("server-id",config.getRequestId());
            assertFalse(config.isHiddenMode());assertEquals(Boolean.TRUE,config.getSoundEnabled());assertEquals(120,config.getNextRequestSeconds());
            assertEquals(4567,config.getAdLoadTimeoutMs());assertEquals(8910,config.getAdStartupTimeoutMs());
            assertEquals(Long.valueOf(30000),recorder.flow.getAdCallbackTimeoutMs());
            JsonObject body=take(server);assertEquals("local-id",body.get("request_id").getAsString());
            assertEquals("persistent-uuid",body.get("rdid").getAsString());assertEquals("real-android-id",body.get("androidid").getAsString());
            ApiVersionContractTest.assertVersions(body);
        }
    }
    @Test public void backendNestedExampleKeepsAllOuterAuthorizationPolicies() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"code\":200,\"msg\":\"success\",\"data\":{"
                + "\"authorized\":true,\"request_id\":\"google-request-001\",\"client_ip\":\"203.0.113.10\","
                + "\"next_request_seconds\":300,\"ad_callback_timeout_seconds\":60,\"hidden_mode\":false,\"sound_mode\":true,"
                + "\"gam_config\":{\"ad_tag_url\":\"https://example.com/gam-vast\",\"ad_load_timeout_ms\":10000,\"ad_startup_timeout_ms\":5000}}}"));
            Recorder recorder = new Recorder();
            client(server, false).resolve("google", "local", recorder); recorder.await();
            assertTrue(recorder.result.hasAd());
            AdPlaybackConfig config = recorder.result.getConfig();
            assertEquals("https://example.com/gam-vast", config.getAdTagUrl());
            assertEquals(10000, config.getAdLoadTimeoutMs()); assertEquals(5000L, config.getAdStartupTimeoutMs());
            assertEquals("google-request-001", config.getRequestId()); assertFalse(config.isHiddenMode());
            assertEquals(Boolean.TRUE, config.getSoundEnabled()); assertEquals(300L, config.getNextRequestSeconds());
            assertEquals("203.0.113.10", recorder.flow.getClientIp());
            assertEquals(Long.valueOf(60000), recorder.flow.getAdCallbackTimeoutMs());
            assertEquals(1, server.getRequestCount()); take(server);
        }
    }
    @Test public void authorizedGoogleWithMissingLinkSkipsWithoutCallingOldResolve() throws Exception {
        try (MockWebServer server=new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"code\":100000,\"data\":{\"authorized\":true}}"));
            Recorder recorder=new Recorder();client(server,false).resolve("google","local",recorder);recorder.await();
            assertFalse(recorder.result.hasAd());assertNotNull(recorder.result.getError());
            assertEquals(1,recorder.authorized.get());assertEquals(1,server.getRequestCount());take(server);
        }
    }
    @Test public void denialDoesNotParseOrPlayEvenIfResponseContainsLink() throws Exception {
        try (MockWebServer server=new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"code\":100000,\"data\":{\"authorized\":false,\"gam_config\":{\"ad_tag_url\":\"https://example.test/vast\"}}}"));
            Recorder recorder=new Recorder();client(server,false).resolve("google","local",recorder);recorder.await();
            assertFalse(recorder.result.hasAd());assertEquals(0,recorder.authorized.get());assertEquals(1,server.getRequestCount());
        }
    }
    @Test public void resultEnvelopeAndLocalRequestIdFallbackStillWork() throws Exception {
        try (MockWebServer server=new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"code\":200,\"result\":{\"authorized\":true,\"ad_tag_url\":\"https://example.test/vast\",\"callback_timeout_seconds\":60}}"));
            Recorder recorder=new Recorder();client(server,false).resolve("google","local-id",recorder);recorder.await();
            assertTrue(recorder.result.hasAd());assertEquals("local-id",recorder.result.getConfig().getRequestId());
            assertEquals(Long.valueOf(60000),recorder.flow.getAdCallbackTimeoutMs());assertEquals(1,server.getRequestCount());
        }
    }
    @Test public void tclKeepsAuthorizationOnlyAndUploadsAndroidIdWithoutRdid() throws Exception {
        try (MockWebServer server=new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"code\":100000,\"data\":{\"authorized\":true}}"));
            Recorder recorder=new Recorder();client(server,true).resolve("tcl","local",recorder);recorder.await();
            assertTrue(recorder.result.hasAd());assertEquals("",recorder.result.getConfig().getAdTagUrl());
            JsonObject body=take(server);assertFalse(body.has("rdid"));assertEquals("android-id",body.get("androidid").getAsString());assertEquals("AA:BB:CC:DD:EE:FF",body.get("mac").getAsString());assertEquals(1,server.getRequestCount());
        }
    }
    @Test public void cancellationSuppressesDelayedAuthorizeResponse() throws Exception {
        try (MockWebServer server=new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"code\":100000,\"data\":{\"authorized\":true,\"ad_tag_url\":\"https://example.test/vast\"}}")
                .setBodyDelay(150,TimeUnit.MILLISECONDS));
            Recorder recorder=new Recorder();Cancellable call=client(server,false).resolve("google","local",recorder);
            assertNotNull(server.takeRequest(3,TimeUnit.SECONDS));call.cancel();
            assertFalse(recorder.done.await(300,TimeUnit.MILLISECONDS));assertEquals(0,recorder.authorized.get());assertEquals(1,server.getRequestCount());
        }
    }
    private static RemoteAdConfigClient client(MockWebServer server,boolean tcl) {
        Gson gson=new Gson();RemoteAdConfigClient client=new RemoteAdConfigClient(ApiVersionContractTest::host,new OkHttpClient(),gson,
            new RemoteAdConfigParser(gson),server.url("/").toString(),tcl);
        client.setIdentifierSource(new RemoteAdConfigClient.IdentifierSource() {
            @Override public String rdid() { return "persistent-uuid"; }
            @Override public String androidId() { return "real-android-id"; }
        });return client;
    }
    private static JsonObject take(MockWebServer server) throws Exception {
        RecordedRequest request=server.takeRequest(3,TimeUnit.SECONDS);assertNotNull(request);
        assertEquals("POST",request.getMethod());assertEquals("/api/v2/ad/sdk/authorize",request.getPath());
        return JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
    }
    private static final class Recorder implements RemoteAdConfigResolver.Callback {
        final CountDownLatch done=new CountDownLatch(1);final AtomicInteger authorized=new AtomicInteger();
        RemoteAdConfigResult result;AdError error;FlowAuthorizedConfig flow;
        @Override public void onAuthorizationResponse(FlowAuthorizedConfig config) { flow=config; }
        @Override public void onAuthorized(FlowAuthorizedConfig config) { authorized.incrementAndGet(); }
        @Override public void onResolved(RemoteAdConfigResult value) { result=value;done.countDown(); }
        @Override public void onError(AdError value) { error=value;done.countDown(); }
        void await() throws Exception { assertTrue(done.await(3,TimeUnit.SECONDS));assertNull(error);assertNotNull(result); }
    }
}
