package com.smart.android.adsdk.universal.internal;

import static org.junit.Assert.*;
import com.smart.android.adsdk.universal.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class SdkConfigurationUpdateTest {
    @Test public void currentRoundRetainsItsSnapshotAndNextRoundUsesNewRegistration() {
        Rig rig=new Rig(); rig.initialize(); rig.calls.get(0).onResult(KeyConfigRefreshTest.config("one",1),null);
        StubSession first=rig.play(); assertEquals("one",first.registration.tclIdentity.packageName);
        rig.time.advance(1000); rig.calls.get(1).onResult(KeyConfigRefreshTest.config("two",10),null);
        assertEquals("one",TclIdentityBridge.getPackageName()); assertEquals("one",first.registration.tclIdentity.packageName);
        first.finish(); StubSession second=rig.play();
        assertEquals("two",second.registration.tclIdentity.packageName); assertEquals("two",TclIdentityBridge.getPackageName());
        assertEquals(2,rig.calls.size()); second.finish();
        rig.play().finish(); assertEquals(2,rig.calls.size()); // Multiple rounds don't refetch registration.
    }
    @Test public void restartUsesPersistedChannelsWithoutNetworkAndPreservesRefreshDeadline() {
        Rig rig=new Rig(); rig.initialize(); rig.calls.get(0).onResult(KeyConfigRefreshTest.config("one",120),null);
        rig.time.advance(90000);
        KeyConfigRefreshTest.FakeTime newTime=new KeyConfigRefreshTest.FakeTime();newTime.now=rig.time.now;
        Rig restarted=new Rig(rig.store,newTime); restarted.initialize();
        assertEquals(1,restarted.ready.get()); assertEquals(0,restarted.calls.size());
        assertEquals("customer-google-101",restarted.play().registration.googleChannelId);
        newTime.advance(30000); assertEquals(1,restarted.calls.size());
    }
    @Test public void failedRefreshOrComponentBuildKeepsReadyWorkingConfiguration() {
        Rig rig=new Rig();rig.initialize();rig.calls.get(0).onResult(KeyConfigRefreshTest.config("one",1),null);
        rig.time.advance(1000);rig.calls.get(1).onResult(null,new IllegalStateException("Offline"));
        assertTrue(rig.runtime.isInitialized());assertEquals(0,rig.errors.get());
        rig.time.advance(60000);rig.reject=true;rig.calls.get(2).onResult(KeyConfigRefreshTest.config("bad",1),null);
        assertEquals("one",rig.play().registration.tclIdentity.packageName);
        assertEquals("one",rig.store.entries.get("key").configuration.tclIdentity.packageName);
    }
    @Test public void cacheInitializationFailureCanBeRetriedAndFailedAttemptIgnoresLateResponse() {
        Rig rig=new Rig();rig.initialize();rig.calls.get(0).onResult(null,new IllegalStateException("Invalid key"));
        assertFalse(rig.runtime.isInitialized());assertEquals(1,rig.errors.get());
        rig.initialize();rig.calls.get(0).onResult(KeyConfigRefreshTest.config("stale",1),null);
        assertFalse(rig.runtime.isInitialized());
        rig.calls.get(1).onResult(KeyConfigRefreshTest.config("fresh",1),null);
        assertEquals(1,rig.ready.get());assertEquals("fresh",rig.play().registration.tclIdentity.packageName);
    }
    @Test public void updatingOnlyIntervalReusesPlaybackComponents() {
        Rig rig=new Rig();rig.initialize();rig.calls.get(0).onResult(KeyConfigRefreshTest.config("one",1),null);
        rig.time.advance(1000);rig.calls.get(1).onResult(KeyConfigRefreshTest.config("one",20),null);
        assertEquals(1,rig.builds.get());assertEquals(1,rig.ready.get());
        rig.time.advance(19000);assertEquals(2,rig.calls.size());
        rig.time.advance(1000);assertEquals(3,rig.calls.size());
    }
    @Test public void nativeWorkMustFinishOnceBeforeRegistrationCanBeReplaced() {
        assertTrue(RegistrationWork.isIdle());Cancellable first=RegistrationWork.begin(),second=RegistrationWork.begin();
        assertFalse(RegistrationWork.isIdle());first.cancel();first.cancel();assertFalse(RegistrationWork.isIdle());
        second.cancel();assertTrue(RegistrationWork.isIdle());
    }
    @Test public void oldChannelsRemainRedactedAfterRefreshForDelayedReports() {
        SdkLog.redactChannels("previous-google", "previous-tcl");
        SdkLog.redactChannels("current-google", "current-tcl");
        String text=SdkLog.sanitize("previous-google previous-tcl current-google current-tcl");
        assertFalse(text.contains("previous-"));assertFalse(text.contains("current-"));
    }
    private static final class Rig {
        final KeyConfigRefreshTest.MemoryStore store;final KeyConfigRefreshTest.FakeTime time;
        final List<KeyConfigClient.Callback> calls=new ArrayList<>();
        final AtomicInteger ready=new AtomicInteger(),errors=new AtomicInteger(),builds=new AtomicInteger();
        final SdkRuntime runtime; boolean reject;
        Rig() { this(new KeyConfigRefreshTest.MemoryStore(),new KeyConfigRefreshTest.FakeTime()); }
        Rig(KeyConfigRefreshTest.MemoryStore store,KeyConfigRefreshTest.FakeTime time) {
            this.store=store;this.time=time;
            runtime=new SdkRuntime(Runnable::run,(key,callback) -> calls.add(callback),(context,http,base,config,registration) -> {
                if(reject) throw new IllegalStateException("Invalid components");
                builds.incrementAndGet();return (host,request,listener) -> new StubSession(registration,listener);
            },store,() -> time.now,time);
        }
        void initialize() {
            runtime.initialize(null,"key",new SdkConfig.Builder().build(),new InitializationListener() {
                @Override public void onInitialized() { ready.incrementAndGet(); }
                @Override public void onError(AdError error) { errors.incrementAndGet(); }
            });
        }
        StubSession play() {
            return (StubSession)runtime.play(null,new AdRequest.Builder().build(),new AdListener() {
                @Override public void onLoaded(AdSession session) {}
                @Override public void onStarted(AdSession session) {}
                @Override public void onFinished(AdSession session,AdResult result) {}
            });
        }
    }
    private static final class StubSession implements AdSession {
        final KeyConfiguration registration;final AdListener listener;
        StubSession(KeyConfiguration registration,AdListener listener) { this.registration=registration;this.listener=listener; }
        void finish() { listener.onFinished(this,AdResult.completed()); }
        @Override public AdState getState() { return AdState.PLAYING; }
        @Override public void pause() {} @Override public void resume() {} @Override public void release() {}
        @Override public void setSoundEnabled(boolean value) {}
    }
}
