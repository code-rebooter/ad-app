package com.smart.android.adsdk.universal.internal;

import static org.junit.Assert.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.*;
import org.junit.Test;

public class KeyConfigRefreshTest {
    static KeyConfiguration config(String customer, long seconds) {
        JsonObject root = JsonParser.parseString(KeyContractIntegrationTest.config(customer)).getAsJsonObject();
        root.getAsJsonObject("data").addProperty("refresh_interval_seconds", seconds);
        return KeyConfiguration.parse(root.toString());
    }
    @Test public void intervalAcceptsPositiveIntegerAndDefaultsInvalidInputs() {
        String base = KeyContractIntegrationTest.config("one");
        assertEquals(86400, KeyConfiguration.parse(base).refreshIntervalSeconds);
        for (String value : Arrays.asList("null", "0", "-1", "1.25", "\"3600\"", "true", "[]", "{}", "9223372036854775807", "9223372036854775808")) {
            JsonObject root = JsonParser.parseString(base).getAsJsonObject();
            root.getAsJsonObject("data").add("refresh_interval_seconds", JsonParser.parseString(value));
            assertEquals(value, 86400, KeyConfiguration.parse(root.toString()).refreshIntervalSeconds);
        }
        assertEquals(3600, config("one",3600).refreshIntervalSeconds);
        assertEquals(604800, config("one",604800).refreshIntervalSeconds);
        assertEquals(3600, KeyConfiguration.parse(config("one",3600).toJson()).refreshIntervalSeconds);
    }
    @Test public void idleTimerRefreshesWithoutPlayAndUsesChangedIntervalFromSuccessfulResponse() {
        Rig rig = new Rig(); rig.start(); assertEquals(1, rig.calls.size());
        rig.succeed(0, config("one",120));
        rig.time.advance(119999); assertEquals(1, rig.calls.size());
        rig.time.advance(1); assertEquals(2, rig.calls.size());
        rig.succeed(1, config("two",5));
        rig.time.advance(4999); assertEquals(2, rig.calls.size());
        rig.time.advance(1); assertEquals(3, rig.calls.size());
        assertEquals("two", rig.received.get(1).tclIdentity.packageName);
    }
    @Test public void freshCacheSurvivesProcessRestartAndDoesNotResetSuccessfulFetchTime() {
        Rig rig = new Rig(); rig.start(); rig.succeed(0, config("one",120));
        long savedAt = rig.store.entries.get("key").fetchedAtMs;
        rig.refresh.stop(); rig.time.advance(90000);
        Rig restarted = new Rig(rig.store, rig.time); restarted.start();
        assertEquals(0,restarted.calls.size()); assertEquals(1,restarted.received.size());
        assertEquals(savedAt,restarted.store.entries.get("key").fetchedAtMs);
        restarted.time.advance(30000); assertEquals(1,restarted.calls.size());
    }
    @Test public void expiredCacheStaysAvailableDuringFailureAndRetriesWithBackoff() {
        Rig rig = new Rig(); rig.store.entries.put("key", new KeyConfigRefresh.Entry(config("cached",5), 1));
        rig.start(); assertEquals("cached",rig.received.get(0).tclIdentity.packageName);
        rig.fail(0); assertEquals(Collections.singletonList(true),rig.failures);
        rig.time.advance(59999); assertEquals(1,rig.calls.size());
        rig.time.advance(1); assertEquals(2,rig.calls.size()); rig.fail(1);
        rig.time.advance(60000); assertEquals(2,rig.calls.size());
        rig.refresh.check(); assertEquals(2,rig.calls.size());
        rig.time.advance(60000); assertEquals(3,rig.calls.size());
        assertEquals("cached",rig.store.entries.get("key").configuration.tclIdentity.packageName);
        rig.succeed(2,config("recovered",10)); rig.time.advance(10000); assertEquals(4,rig.calls.size());
        rig.fail(3); rig.time.advance(60000); assertEquals(5,rig.calls.size());
    }
    @Test public void inFlightAndDuplicateResponsesCannotOverwriteLastSuccess() {
        Rig rig = new Rig(); rig.start();
        rig.refresh.check(); rig.time.advance(100000); assertEquals(1,rig.calls.size());
        rig.succeed(0,config("one",1)); rig.succeed(0,config("duplicate",1));
        assertEquals(1,rig.received.size());
        rig.time.advance(1000); assertEquals(2,rig.calls.size());
        rig.succeed(0,config("stale",1)); assertEquals(1,rig.received.size());
        rig.succeed(1,config("two",2)); assertEquals("two",rig.store.entries.get("key").configuration.tclIdentity.packageName);
    }
    @Test public void stopIgnoresLateCallbacksAndCancelsScheduledRefresh() {
        Rig rig = new Rig(); rig.start(); rig.refresh.stop();
        rig.succeed(0,config("late",1)); rig.time.advance(1000000);
        assertTrue(rig.received.isEmpty()); assertTrue(rig.store.entries.isEmpty()); assertEquals(1,rig.calls.size());
    }
    @Test public void sleepAndClockCorrectionCauseSingleRefreshWithoutCatchupBurst() {
        Rig rig = new Rig(); rig.start(); rig.succeed(0,config("one",3600));
        rig.time.now += 30L*86400000; // Wall clock advances while Handler callbacks are suspended.
        rig.refresh.check(); rig.refresh.check(); assertEquals(2,rig.calls.size());
        rig.succeed(1,config("two",3600)); rig.refresh.check(); assertEquals(2,rig.calls.size());
        rig.refresh.stop(); rig.time.now -= 100000;
        Rig corrected = new Rig(rig.store,rig.time); corrected.start();
        assertEquals(1,corrected.received.size()); assertEquals(1,corrected.calls.size());
    }
    @Test public void cacheIsPartitionedByKeyAndRejectedRefreshCannotPoisonIt() {
        Rig rig = new Rig(); rig.store.entries.put("another-key",new KeyConfigRefresh.Entry(config("other",120),rig.time.now));
        rig.start(); assertTrue(rig.received.isEmpty());
        rig.succeed(0,config("one",1)); rig.time.advance(1000);
        rig.reject = true; rig.succeed(1,config("rejected",20));
        assertEquals("one",rig.store.entries.get("key").configuration.tclIdentity.packageName);
        assertEquals("other",rig.store.entries.get("another-key").configuration.tclIdentity.packageName);
        assertEquals(Collections.singletonList(true),rig.failures);
        assertNotEquals(KeyConfigStore.cacheKey("key"),KeyConfigStore.cacheKey("another-key"));
        assertEquals(64,KeyConfigStore.cacheKey("key").length());
    }
    @Test public void missingCacheFailureAndCorruptCacheDoNotProduceReadyConfiguration() {
        Rig rig = new Rig(); rig.start(); rig.fail(0);
        assertEquals(Collections.singletonList(false),rig.failures); assertTrue(rig.received.isEmpty());
        Rig corrupt = new Rig(); corrupt.store.loadFailure = true; corrupt.start();
        assertEquals(1,corrupt.calls.size()); assertTrue(corrupt.received.isEmpty());
    }
    @Test public void startupListenerReentrancyDoesNotTriggerDuplicateFetch() {
        Rig rig = new Rig(); rig.store.entries.put("key",new KeyConfigRefresh.Entry(config("cached",120),rig.time.now));
        rig.reenter = true; rig.start(); assertTrue(rig.calls.isEmpty());
        Rig fresh = new Rig(); fresh.reenter=true; fresh.start(); fresh.succeed(0,config("one",120));
        assertEquals(1,fresh.calls.size());
    }
    @Test public void intervalChangesDoNotInvalidateRegistrationAndIdentityChangesDo() {
        KeyConfiguration first = config("one",1), intervalOnly = config("one",2), changed = config("two",1);
        assertTrue(first.sameRegistration(intervalOnly)); assertFalse(first.sameRegistration(changed));
        assertEquals(first.tclIdentity.cacheKey(),intervalOnly.tclIdentity.cacheKey());
        assertNotEquals(first.tclIdentity.cacheKey(),changed.tclIdentity.cacheKey());
        assertFalse(first.sameRegistration(KeyConfiguration.parse(KeyContractIntegrationTest.config("one","new-google","customer-tcl-909"))));
    }
    static final class MemoryStore implements KeyConfigRefresh.Store {
        final Map<String,KeyConfigRefresh.Entry> entries = new HashMap<>(); boolean loadFailure;
        @Override public KeyConfigRefresh.Entry load(String key) { if (loadFailure) throw new IllegalArgumentException("Corrupt storage"); return entries.get(key); }
        @Override public void save(String key, KeyConfigRefresh.Entry entry) {
            entries.put(key,new KeyConfigRefresh.Entry(KeyConfiguration.parse(entry.configuration.toJson()),entry.fetchedAtMs));
        }
    }
    static final class FakeTime implements TimeoutScheduler {
        long now = 1_000_000; final List<Task> tasks = new ArrayList<>();
        @Override public long nowMs() { return now; }
        @Override public Cancellable schedule(Runnable action,long delay) {
            Task task = new Task(now+delay,action); tasks.add(task); return () -> task.cancelled=true;
        }
        void advance(long ms) {
            long target=now+ms;
            while (true) {
                Task next=tasks.stream().filter(t -> !t.cancelled && t.when<=target).min(Comparator.comparingLong(t -> t.when)).orElse(null);
                if (next==null) break;
                tasks.remove(next); now=Math.max(now,next.when); next.action.run();
            }
            now=target;
        }
        static final class Task { final long when; final Runnable action; boolean cancelled; Task(long when,Runnable action) { this.when=when;this.action=action; } }
    }
    private static final class Rig implements KeyConfigRefresh.Listener {
        final MemoryStore store; final FakeTime time;
        final List<KeyConfigClient.Callback> calls=new ArrayList<>();
        final List<KeyConfiguration> received=new ArrayList<>(); final List<Boolean> failures=new ArrayList<>();
        final KeyConfigRefresh refresh; boolean reject,reenter;
        Rig() { this(new MemoryStore(),new FakeTime()); }
        Rig(MemoryStore store,FakeTime time) {
            this.store=store;this.time=time;
            refresh=new KeyConfigRefresh("key",store,() -> time.now,time,(key,callback) -> calls.add(callback),Runnable::run,this);
        }
        void start() { refresh.start(); }
        void succeed(int index,KeyConfiguration config) { calls.get(index).onResult(config,null); }
        void fail(int index) { calls.get(index).onResult(null,new IllegalStateException("Offline")); }
        @Override public void onConfiguration(KeyConfiguration config) {
            if (reject) throw new IllegalArgumentException("Unusable components");
            received.add(config); if (reenter) refresh.check();
        }
        @Override public void onFailure(Throwable error,boolean hasCache) { failures.add(hasCache); }
    }
}
