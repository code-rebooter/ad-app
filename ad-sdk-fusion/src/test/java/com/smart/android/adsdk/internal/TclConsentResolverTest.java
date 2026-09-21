package com.smart.android.adsdk.internal;

import static org.junit.Assert.*;

import android.content.Context;
import com.google.gson.Gson;
import com.smart.android.adsdk.AdError;
import com.smart.android.adsdk.internal.tclcmp.TclCmpBridge;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
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
