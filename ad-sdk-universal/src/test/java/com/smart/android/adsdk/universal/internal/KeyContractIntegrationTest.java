package com.smart.android.adsdk.universal.internal;

import static org.junit.Assert.*;
import com.google.gson.*;
import com.smart.android.adsdk.universal.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.Test;

public class KeyContractIntegrationTest {
    static String config(String packageName) {
        return config(packageName, "customer-google-101", "customer-tcl-909");
    }
    static String config(String packageName, String googleChannel, String tclChannel) {
        return "{\"code\":100000,\"data\":{\"google\":{\"channel_id\":\"" + googleChannel
            + "\"},\"tcl\":{\"channel_id\":\"" + tclChannel + "\",\"package_name\":\"" + packageName + "\",\"app_name\":\"registered-name\","
            + "\"version_name\":\"2.0.10\",\"version_code\":10,\"signature_md5\":\"d2a9b2a8a9e0af740267c0bc356dc1a4\","
            + "\"app_key\":\"native-tcl-key\",\"project_id\":213,\"partner_name\":\"registered-partner\"}}}";
    }
    @Test public void bootstrapSendsKeyAndRealHostVersionsAndNeedsNoOptionalResponseFields() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(config("customer.one")));
            CountDownLatch done = new CountDownLatch(1); AtomicReference<KeyConfiguration> result = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
            new KeyConfigClient(ApiVersionContractTest::hostVersions, new OkHttpClient(), server.url("/").toString()).request("customer-key", (identity, error) -> { result.set(identity); failure.set(error); done.countDown(); });
            assertTrue(done.await(3, TimeUnit.SECONDS)); assertNull(failure.get()); assertEquals("customer.one", result.get().tclIdentity.packageName);
            assertEquals("customer-google-101", result.get().googleChannelId); assertEquals("customer-tcl-909", result.get().tclChannelId);
            RecordedRequest request = server.takeRequest(3,TimeUnit.SECONDS); assertEquals("/api/v2/ad/sdk/config",request.getPath());
            JsonObject body = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject(); assertEquals(4,body.size()); assertEquals("customer-key",body.get("sdk_key").getAsString());
            ApiVersionContractTest.assertVersions(body);
            assertFalse(body.has("mac")); assertFalse(body.has("androidid"));
        }
    }
    @Test public void bootstrapVersionCollectionFailureNotifiesWithoutSendingRequest() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            AtomicInteger callbacks = new AtomicInteger();
            IllegalStateException expected = new IllegalStateException("host metadata unavailable");
            new KeyConfigClient(() -> { throw expected; }, new OkHttpClient(), server.url("/").toString())
                .request("customer-key", (configuration, failure) -> {
                    assertNull(configuration); assertSame(expected, failure); callbacks.incrementAndGet();
                });
            assertEquals(1, callbacks.get());
            assertEquals(0, server.getRequestCount());
        }
    }
    @Test public void invalidOrIncompleteConfigCannotBecomeAnotherCustomersIdentity() {
        for (String json : Arrays.asList("{\"code\":403,\"data\":{\"tcl\":{}}}", "{\"code\":100000}",
            config("customer.one").replace("\"app_key\":\"native-tcl-key\",", ""),
            config("customer.one").replace("213", "213.5"), config("customer.one").replace("d2a9b2a8a9e0af740267c0bc356dc1a4", "bad"))) {
            try { KeyConfiguration.parse(json); fail("Invalid config was accepted"); } catch (RuntimeException expected) {}
        }
        KeyConfiguration first = KeyConfiguration.parse(config("customer.one")), second = KeyConfiguration.parse(config("customer.two"));
        assertEquals("customer.one",first.tclIdentity.packageName); assertEquals("customer.two",second.tclIdentity.packageName); assertNotSame(first, second);
    }
    @Test public void bothChannelIdsAreRequiredStringsAndAreNotDerivedFromEachOther() {
        String valid = config("customer.one");
        for (String invalid : Arrays.asList(
            valid.replace("\"google\":{\"channel_id\":\"customer-google-101\"},", ""),
            valid.replace("\"channel_id\":\"customer-tcl-909\",", ""),
            valid.replace("\"customer-google-101\"", "null"),
            valid.replace("\"customer-tcl-909\"", "909"),
            config("customer.one", "", "customer-tcl-909"),
            config("customer.one", "customer-google-101", "   "))) {
            try { KeyConfiguration.parse(invalid); fail("Missing channel was accepted"); }
            catch (RuntimeException expected) {}
        }
        KeyConfiguration configured = KeyConfiguration.parse(config("customer.one", "arbitrary-google", "independent-tcl"));
        assertEquals("arbitrary-google", configured.googleChannelId);
        assertEquals("independent-tcl", configured.tclChannelId);
    }
    @Test public void configuredChannelsAreSentSeparatelyToCmpEndpointsUsingOriginalContract() throws Exception {
        KeyConfiguration registration = KeyConfiguration.parse(config("customer.one"));
        try (MockWebServer server = new MockWebServer()) {
            TclCmpBackendClient backend = new TclCmpBackendClient(new OkHttpClient(), new Gson(), server.url("/").toString(), DeviceInfo::empty, () -> 23);
            for (String channel : Arrays.asList(registration.googleChannelId, registration.tclChannelId)) {
                server.enqueue(new MockResponse().setBody("{\"code\":100000,\"data\":{\"consent_action\":\"MAYBE_LATER\"}}"));
                CountDownLatch requested = new CountDownLatch(1); AtomicReference<AdError> failure = new AtomicReference<>();
                backend.request(channel, false, (decision, error) -> { failure.set(error); requested.countDown(); });
                assertTrue(requested.await(3, TimeUnit.SECONDS)); assertNull(failure.get());
                RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS); assertEquals("/api/v2/ad/consent-popup", request.getPath());
                JsonObject requestBody = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
                assertEquals(channel, requestBody.get("channel_id").getAsString()); assertFalse(requestBody.has("sdk_key")); assertFalse(requestBody.has("provider"));
                server.enqueue(new MockResponse().setBody("{\"code\":100000}"));
                CountDownLatch reported = new CountDownLatch(1);
                backend.report(channel, "MAYBE_LATER", error -> { failure.set(error); reported.countDown(); });
                assertTrue(reported.await(3, TimeUnit.SECONDS)); assertNull(failure.get());
                RecordedRequest report = server.takeRequest(3, TimeUnit.SECONDS); assertEquals("/api/v2/ad/consent-report", report.getPath());
                JsonObject reportBody = JsonParser.parseString(report.getBody().readUtf8()).getAsJsonObject();
                assertEquals(channel, reportBody.get("channel_id").getAsString()); assertFalse(reportBody.has("sdk_key")); assertFalse(reportBody.has("provider"));
            }
        }
    }
    @Test public void nativeHttpSingletonAndAdCacheAreRestoredAfterSuccessAndFailure() {
        FakeHttp.a="http-before"; FakeAd ad=new FakeAd(); ad.b="ad-before";
        assertEquals("done",OriginalBoundary.withTransport(ad,FakeHttp.class,"supplied-google",() -> {
            assertEquals("supplied-google",FakeHttp.a); assertEquals("supplied-google",ad.b); return "done";
        }));
        assertEquals("http-before",FakeHttp.a); assertEquals("ad-before",ad.b);
        try { OriginalBoundary.withTransport(ad,FakeHttp.class,"second-google",() -> { throw new IllegalStateException("native failure"); }); fail(); }
        catch(IllegalStateException expected) {}
        assertEquals("http-before",FakeHttp.a); assertEquals("ad-before",ad.b);
    }
    @Test public void playBeforeAndDuringInitializationReportsErrorWithoutStartingPlayback() {
        List<KeyConfigClient.Callback> network = new ArrayList<>();
        AtomicInteger created = new AtomicInteger(), finished = new AtomicInteger();
        SdkRuntime runtime = new SdkRuntime(Runnable::run, (key, callback) -> network.add(callback),
            (context, http, base, config, registration) -> {
                created.incrementAndGet();
                throw new AssertionError("Playback components must not be created before valid configuration");
            });
        AdListener listener = new AdListener() {
            @Override public void onLoaded(AdSession session) { fail("Uninitialized SDK loaded an ad"); }
            @Override public void onStarted(AdSession session) { fail("Uninitialized SDK started an ad"); }
            @Override public void onFinished(AdSession session, AdResult result) {
                assertEquals(AdState.FINISHED, session.getState());
                assertEquals(AdResultStatus.ERROR, result.getStatus());
                assertEquals(AdErrorCode.INIT_NOT_CALLED, result.getError().getCode());
                finished.incrementAndGet();
            }
        };
        AdRequest request = new AdRequest.Builder().build();
        assertEquals(AdState.FINISHED, runtime.play(null, request, listener).getState());
        assertEquals(1, finished.get()); assertTrue(network.isEmpty());
        runtime.initialize(null, "pending-key", new SdkConfig.Builder().build(), new InitializationListener() {
            @Override public void onInitialized() { fail("Pending configuration cannot complete initialization"); }
            @Override public void onError(AdError error) { fail("No configuration result has been delivered"); }
        });
        assertEquals(AdState.FINISHED, runtime.play(null, request, listener).getState());
        assertEquals(2, finished.get()); assertEquals(1, network.size()); assertEquals(0, created.get());
    }
    @Test public void initializationCoalescesRetriesAfterFailureAndRejectsAnotherKeyAfterSuccess() {
        List<KeyConfigClient.Callback> network=new ArrayList<>(); AtomicInteger initialized=new AtomicInteger(),errors=new AtomicInteger(),created=new AtomicInteger(),plays=new AtomicInteger();
        AtomicReference<KeyConfiguration> installed=new AtomicReference<>();
        SdkRuntime runtime=new SdkRuntime(Runnable::run,(key, callback) -> network.add(callback), (context,http,base,config,registration) -> {
            installed.set(registration); created.incrementAndGet(); return (host,request,listener) -> { plays.incrementAndGet(); return null; };
        });
        InitializationListener listener=new InitializationListener() {
            @Override public void onInitialized() { assertTrue(runtime.isInitialized()); initialized.incrementAndGet(); }
            @Override public void onError(AdError error) { errors.incrementAndGet(); }
        };
        SdkConfig options=new SdkConfig.Builder().build();
        assertFalse(runtime.isInitialized());
        runtime.initialize(null,"one-key",options,listener); runtime.initialize(null,"one-key",options,listener);
        assertFalse(runtime.isInitialized());
        assertEquals(1,network.size()); runtime.initialize(null,"other-key",options,listener); assertEquals(1,errors.get());
        assertFalse(runtime.isInitialized());
        network.get(0).onResult(null,new IllegalStateException("invalid-key")); assertEquals(3,errors.get()); assertEquals(0,created.get());
        assertFalse(runtime.isInitialized());
        runtime.initialize(null,"one-key",options,listener); assertEquals(2,network.size());
        assertFalse(runtime.isInitialized());
        network.get(1).onResult(KeyConfiguration.parse(config("customer.one")),null); assertEquals(1,initialized.get()); assertEquals(1,created.get());
        assertTrue(runtime.isInitialized()); assertEquals(0, plays.get());
        assertEquals("customer.one",TclIdentityBridge.getPackageName()); assertEquals("D2A9B2A8A9E0AF740267C0BC356DC1A4",TclIdentityBridge.getSignatureMd5());
        runtime.initialize(null,"one-key",options,listener); assertEquals(2,initialized.get()); assertEquals(2,network.size());
        assertTrue(runtime.isInitialized());
        runtime.initialize(null,"other-key",options,listener); assertEquals(4,errors.get()); assertEquals("customer.one",TclIdentityBridge.getPackageName());
        assertTrue(runtime.isInitialized()); assertEquals(0, plays.get());
        assertEquals("customer-google-101", installed.get().googleChannelId); assertEquals("customer-tcl-909", installed.get().tclChannelId);
        String diagnostic=SdkLog.sanitize("request=one-key google=customer-google-101 tcl=customer-tcl-909");
        assertFalse(diagnostic.contains("one-key")); assertFalse(diagnostic.contains("customer-google-101")); assertFalse(diagnostic.contains("customer-tcl-909"));
    }
    static class FakeHttp { private static Object a; }
    static class FakeAd { private Object b; }
}
