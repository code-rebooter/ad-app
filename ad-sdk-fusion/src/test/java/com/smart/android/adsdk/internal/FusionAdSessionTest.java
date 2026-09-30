package com.smart.android.adsdk.internal;

import static org.junit.Assert.*;
import com.smart.android.adsdk.*;
import java.util.*;
import org.junit.Test;

public class FusionAdSessionTest {
    @Test public void cancellationIntentBeatsAlreadyQueuedGoogleCompletion() {
        Queue<Runnable> queue = new ArrayDeque<>();
        Fixture f = new Fixture(queue::add);
        f.session.start(); drain(queue);
        f.google.finish(AdResult.completed());
        f.session.release();
        drain(queue);
        assertEquals(0, f.tcl.starts);
        assertEquals(Arrays.asList("finished:CANCELLED"), f.events);
    }

    @Test public void cancellationIntentBeatsAlreadyQueuedFinalCompletion() {
        Queue<Runnable> queue = new ArrayDeque<>();
        Fixture f = new Fixture(queue::add);
        f.session.start(); drain(queue);
        f.google.finish(AdResult.completed()); drain(queue);
        f.tcl.finish(AdResult.completed()); f.session.release(); drain(queue);
        assertEquals(Arrays.asList("finished:CANCELLED"), f.events);
    }

    private static void drain(Queue<Runnable> queue) {
        Runnable r; while ((r = queue.poll()) != null) r.run();
    }

    @Test public void childFinishingBeforeItsQueuedCallbackDoesNotEndFusion() {
        Fixture f = new Fixture(); f.session.start();
        // Real child finish sets FINISHED before posting its listener callback.
        f.google.state = AdState.FINISHED;
        assertNotEquals(AdState.FINISHED, f.session.getState());
        f.google.finish(AdResult.completed());
        f.tcl.state = AdState.FINISHED;
        assertNotEquals(AdState.FINISHED, f.session.getState());
        f.tcl.finish(AdResult.completed());
        assertEquals(AdState.FINISHED, f.session.getState());
    }

    @Test public void serialPlaybackHasOnePublicLifecycle() {
        Fixture f = new Fixture();
        f.session.start();
        assertEquals(1, f.google.starts);
        assertEquals(0, f.tcl.starts);
        f.google.loaded(); f.google.started(); f.google.finish(AdResult.completed());
        assertEquals(Arrays.asList("loaded", "started"), f.events);
        assertEquals(1, f.tcl.starts);
        f.tcl.loaded(); f.tcl.started(); f.tcl.finish(AdResult.completed());
        assertEquals(Arrays.asList("loaded", "started", "finished:COMPLETED"), f.events);
        assertEquals(AdState.FINISHED, f.session.getState());
        assertSame(f.session, f.callbackSession);
    }

    @Test public void googleSuccessSurvivesTclFailure() {
        Fixture f = new Fixture(); f.session.start();
        f.google.finish(AdResult.completed()); f.tcl.finish(AdResult.error(nativeError("TCL", "7")));
        assertEquals(AdResultStatus.COMPLETED, f.result.getStatus());
        assertNull(f.result.getError());
    }

    @Test public void googleFailureDoesNotPreventTclSuccess() {
        Fixture f = new Fixture(); f.session.start();
        f.google.finish(AdResult.error(nativeError("IMA", "303")));
        assertEquals(1, f.tcl.starts);
        f.tcl.loaded(); f.tcl.started(); f.tcl.finish(AdResult.completed());
        assertEquals(Arrays.asList("loaded", "started", "finished:COMPLETED"), f.events);
    }

    @Test public void allFailuresKeepFirstOriginalErrorObject() {
        Fixture f = new Fixture(); f.session.start();
        AdError original = nativeError("IMA", "303");
        f.google.finish(AdResult.error(original)); f.tcl.finish(AdResult.error(nativeError("TCL", "7")));
        assertSame(original, f.result.getError());
        assertEquals("303", f.result.getError().getOriginalCode());
    }

    @Test public void legacySkippedWithErrorIsNotReclassified() {
        Fixture f = new Fixture(); f.session.start();
        AdResult original = AdResult.skipped("backend unavailable", nativeError("HTTP", "503"));
        f.google.finish(original); f.tcl.finish(AdResult.skipped("AUTHORIZE_DENIED"));
        assertSame(original, f.result);
        assertEquals(AdResultStatus.SKIPPED, f.result.getStatus());
    }

    @Test public void releaseStopsGoogleWithoutRequestingTcl() {
        Fixture f = new Fixture(); f.session.start(); f.session.release(); f.session.release();
        f.google.loaded(); f.google.started(); f.google.finish(AdResult.completed());
        assertTrue(f.google.released);
        assertEquals(0, f.tcl.starts);
        assertEquals(Arrays.asList("finished:CANCELLED"), f.events);
    }

    @Test public void cancellationOverridesEarlierPlaybackSuccess() {
        Fixture f = new Fixture(); f.session.start(); f.google.finish(AdResult.completed());
        f.session.release(); f.tcl.finish(AdResult.completed());
        assertTrue(f.tcl.released);
        assertEquals(AdResultStatus.CANCELLED, f.result.getStatus());
        assertEquals(1, f.events.size());
    }

    @Test public void staleGoogleEventsCannotFinishOrNotifyDuringTcl() {
        Fixture f = new Fixture(); f.session.start(); f.google.finish(AdResult.skipped("NO_AD"));
        f.google.loaded(); f.google.started(); f.google.finish(AdResult.completed());
        assertTrue(f.events.isEmpty()); assertEquals(1, f.tcl.starts);
        f.tcl.finish(AdResult.skipped("NO_AD"));
        assertEquals(Arrays.asList("finished:SKIPPED"), f.events);
    }

    @Test public void synchronousSkipCanAdvanceAndFinishSafely() {
        Fixture f = new Fixture();
        f.google.immediate = AdResult.skipped("disabled");
        f.tcl.immediate = AdResult.skipped("disabled");
        f.session.start(); f.session.start();
        assertEquals(1, f.google.starts); assertEquals(1, f.tcl.starts);
        assertEquals(Arrays.asList("finished:SKIPPED"), f.events);
    }

    @Test public void releaseFromLoadedCallbackPreventsStartingNextChannel() {
        Fixture f = new Fixture(); f.releaseWhenLoaded = true;
        f.session.start(); f.google.loaded(); f.google.finish(AdResult.completed());
        assertEquals(Arrays.asList("loaded", "finished:CANCELLED"), f.events);
        assertEquals(0, f.tcl.starts);
    }

    @Test public void callbacksThrowingDoNotBlockNextChannelOrFinish() {
        Fixture f = new Fixture(); f.throwWhenLoaded = true;
        f.session.start(); f.google.loaded(); f.google.started(); f.google.finish(AdResult.completed());
        f.tcl.finish(AdResult.completed());
        assertEquals(Arrays.asList("loaded", "started", "finished:COMPLETED"), f.events);
    }

    @Test public void controlsFollowActiveChildAndKeepGoogleMetadata() {
        Fixture f = new Fixture(); f.session.start();
        f.google.requestId = "google-server-id"; f.google.nextSeconds = 120;
        f.google.loaded(); f.google.started();
        f.session.pause(); f.session.resume(); f.session.setSoundEnabled(true);
        assertEquals(1, f.google.pauses); assertEquals(1, f.google.resumes); assertTrue(f.google.sound);
        f.google.finish(AdResult.completed());
        f.tcl.requestId = "tcl-server-id"; f.tcl.nextSeconds = 600;
        assertNotEquals(f.google.request.getRequestId(), f.tcl.request.getRequestId());
        assertTrue(f.tcl.request.isSoundEnabled());
        f.session.setSoundEnabled(false); assertFalse(f.tcl.sound);
        f.tcl.finish(AdResult.completed());
        assertEquals("google-server-id", f.session.getRequestId());
        assertEquals(120, f.session.getNextRequestSeconds());
    }

    @Test public void releaseBeforeStartNeverRequestsEitherProvider() {
        Fixture f = new Fixture(); f.session.release(); f.session.start();
        assertEquals(0, f.google.starts); assertEquals(0, f.tcl.starts);
        assertEquals(Arrays.asList("finished:CANCELLED"), f.events);
    }

    static AdError nativeError(String source, String code) {
        return new AdError(AdErrorCode.AD_LOAD_ERROR, AdErrorStage.PLAYER, "original message", null, source, code, null);
    }

    static final class Fixture implements AdListener {
        final Child google = new Child(), tcl = new Child();
        final List<String> events = new ArrayList<>();
        final FusionAdSession session;
        Fixture() { this(Runnable::run); }
        Fixture(CallbackDispatcher dispatcher) {
            session = new FusionAdSession(
                new AdRequest.Builder().setRequestId("host-request").build(), this, dispatcher,
                (request, listener) -> google.bind(request, listener),
                (request, listener) -> tcl.bind(request, listener));
        }
        boolean releaseWhenLoaded, throwWhenLoaded;
        AdResult result; AdSession callbackSession;
        public void onLoaded(AdSession s) {
            callbackSession = s; events.add("loaded");
            if (releaseWhenLoaded) s.release();
            if (throwWhenLoaded) throw new IllegalStateException("host exception");
        }
        public void onStarted(AdSession s) { callbackSession = s; events.add("started"); }
        public void onFinished(AdSession s, AdResult r) {
            callbackSession = s; result = r; events.add("finished:" + r.getStatus());
        }
    }

    static final class Child implements ChannelSession {
        AdListener listener; AdRequest request; AdResult immediate;
        int starts, pauses, resumes; boolean released, sound;
        String requestId; long nextSeconds; AdState state = AdState.RESOLVING_CONFIG;
        Child bind(AdRequest r, AdListener l) { request = r; listener = l; requestId = r.getRequestId(); return this; }
        public void start() { starts++; if (immediate != null) finish(immediate); }
        void loaded() { state = AdState.LOADING; listener.onLoaded(this); }
        void started() { state = AdState.PLAYING; listener.onStarted(this); }
        void finish(AdResult r) { state = AdState.FINISHED; listener.onFinished(this, r); }
        public AdState getState() { return state; }
        public String getRequestId() { return requestId; }
        public long getNextRequestSeconds() { return nextSeconds; }
        public void pause() { pauses++; state = AdState.PAUSED; }
        public void resume() { resumes++; state = AdState.PLAYING; }
        public void setSoundEnabled(boolean value) { sound = value; }
        public void release() { released = true; finish(AdResult.cancelled()); }
    }
}
