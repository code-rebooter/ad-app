package com.smart.android.adsdk.universal.internal;

import static org.junit.Assert.*;

import android.content.Context;
import com.google.gson.Gson;
import com.smart.android.adsdk.universal.AdError;
import com.smart.android.adsdk.universal.internal.tclcmp.TclCmpBridge;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import com.google.gson.JsonParser;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class TclConsentResolverTest {
    @Test public void nativeRequestResponseDetailsReachFlowTrace() {
        NativeCmp cmp = new NativeCmp();
        Recorder listener = new Recorder();
        resolver(cmp).resolve(null, "G_TCL", listener);
        String data = "{\"request\":{\"appbundle\":\"registered.package\"},\"response\":{\"code\":200}}";
        cmp.bridge.trace("CMP_API_SUCCESS", "status", data);
        assertEquals(data, listener.traceData);
    }

    @Test public void startupRestoresTclStateWithoutStartingRemoteDecision() {
        NativeCmp cmp = new NativeCmp();
        resolver(cmp).initialize(null, "G_TCL");
        assertEquals(List.of("initialize"), cmp.events);
        assertNull(cmp.ready);
        assertNull(cmp.finished);
    }

    @Test public void authorizationWaitsForNativeCmpStateAndDecision() {
        NativeCmp cmp = new NativeCmp();
        Recorder listener = new Recorder();
        resolver(cmp).resolve(null, "GOOGLE_AD_TV_LOCKSCREEN_HQ002_TCL", listener);
        assertEquals(List.of("initialize", "wait"), cmp.events);
        assertEquals(0, listener.allowed);
        cmp.ready.run();
        assertEquals(List.of("initialize", "wait", "decision"), cmp.events);
        assertEquals(0, listener.allowed);
        cmp.finished.run();
        cmp.finished.run();
        assertEquals(1, listener.allowed);
        assertEquals(0, listener.errors);
    }

    @Test public void releaseWhileLoadingStateDoesNotStartDecisionOrAuthorize() {
        NativeCmp cmp = new NativeCmp();
        Recorder listener = new Recorder();
        Cancellable call = resolver(cmp).resolve(null, "G_TCL", listener);
        call.cancel();
        cmp.ready.run();
        assertEquals(List.of("initialize", "wait"), cmp.events);
        assertEquals(0, listener.allowed);
    }

    @Test public void lateNativeCompletionCannotAuthorizeCancelledRound() {
        NativeCmp cmp = new NativeCmp();
        Recorder listener = new Recorder();
        Cancellable call = resolver(cmp).resolve(null, "G_TCL", listener);
        cmp.ready.run();
        call.cancel();
        cmp.finished.run();
        assertEquals(0, listener.allowed);
    }

    @Test public void nativeInitializationFailureRetainsItsMessage() {
        NativeCmp cmp = new NativeCmp();
        cmp.failure = new IllegalStateException("native CMP initialization failed");
        Recorder listener = new Recorder();
        resolver(cmp).resolve(null, "G_TCL", listener);
        assertEquals(0, listener.allowed);
        assertEquals(1, listener.errors);
        assertEquals("native CMP initialization failed", listener.error.getMessage());
        assertSame(cmp.failure, listener.error.getCause());
    }

    @Test public void persistedConsentReportKeepsOriginalChannelAfterRegistrationChanged() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"code\":100000}"));
            NativeCmp cmp = new NativeCmp();
            TclConsentResolver resolver = new TclConsentResolver(new TclCmpBackendClient(new OkHttpClient(), new Gson(),
                server.url("/").toString(), DeviceInfo::empty, () -> 29), cmp, Runnable::run);
            resolver.initialize(null, "new-channel");
            assertEquals("new-channel", cmp.bridge.getChannelId());
            CountDownLatch done = new CountDownLatch(1);
            cmp.bridge.reportConsent("original-channel", "ACCEPT_ALL", error -> { assertNull(error); done.countDown(); });
            assertTrue(done.await(3, TimeUnit.SECONDS));
            RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
            assertEquals("/api/v2/ad/consent-report", request.getPath());
            assertEquals("original-channel", JsonParser.parseString(request.getBody().readUtf8())
                .getAsJsonObject().get("channel_id").getAsString());
        }
    }

    @Test public void cancellationCompletesOutstandingNativeDecisionAndReportCallbacksOnce() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"code\":100000}").setBodyDelay(200, TimeUnit.MILLISECONDS));
            server.enqueue(new MockResponse().setBody("{\"code\":100000}").setBodyDelay(200, TimeUnit.MILLISECONDS));
            NativeCmp cmp = new NativeCmp();
            TclConsentResolver resolver = new TclConsentResolver(new TclCmpBackendClient(new OkHttpClient(), new Gson(),
                server.url("/").toString(), DeviceInfo::empty, () -> 29), cmp, Runnable::run);
            Cancellable call = resolver.resolve(null, "channel", new Recorder());
            AtomicInteger decisions = new AtomicInteger(), reports = new AtomicInteger();
            cmp.bridge.requestDecision(null, false, decision -> { assertNull(decision); decisions.incrementAndGet(); });
            cmp.bridge.reportConsent("ACCEPT_ALL", error -> { assertEquals("Canceled", error); reports.incrementAndGet(); });
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS));
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS));
            call.cancel(); call.cancel();
            assertEquals(1, decisions.get()); assertEquals(1, reports.get());
        }
    }

    private TclConsentResolver resolver(NativeCmp cmp) {
        TclCmpBackendClient client = new TclCmpBackendClient(new OkHttpClient(), new Gson(),
            "https://api.kartna.cc/", DeviceInfo::empty, () -> 29);
        return new TclConsentResolver(client, cmp, Runnable::run);
    }

    private static final class NativeCmp implements TclConsentResolver.Manager {
        final List<String> events = new ArrayList<>();
        Runnable ready, finished;
        RuntimeException failure;
        TclCmpBridge bridge;
        public void initialize(Context context, TclCmpBridge bridge) {
            this.bridge = bridge;
            events.add("initialize");
            if (failure != null) throw failure;
        }
        public void whenReady(Runnable callback) { events.add("wait"); ready = callback; }
        public void applyDecision(Context context, Runnable callback) { events.add("decision"); finished = callback; }
        public boolean canContinue() { return true; }
        public String consentString() { return "native-tcl-consent"; }
    }

    private static final class Recorder implements ConsentResolver.Callback {
        int allowed, errors;
        AdError error;
        String traceData;
        public void onTrace(String event, String message, String data) { traceData = data; }
        public void onAllowed() { allowed++; }
        public void onBlocked(String reason) { fail(reason); }
        public void onError(AdError value) { errors++; error = value; }
    }
}
