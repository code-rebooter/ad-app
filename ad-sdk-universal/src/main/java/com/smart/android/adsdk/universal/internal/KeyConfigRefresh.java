package com.smart.android.adsdk.universal.internal;

/** One independently scheduled configuration request, with persistent successful-fetch time. */
final class KeyConfigRefresh {
    interface Clock { long nowMs(); }
    interface Store {
        Entry load(String key);
        void save(String key, Entry entry);
    }
    static final class Entry {
        final KeyConfiguration configuration;
        final long fetchedAtMs;
        Entry(KeyConfiguration configuration, long fetchedAtMs) {
            this.configuration = configuration; this.fetchedAtMs = fetchedAtMs;
        }
    }
    interface Listener {
        /** Must prepare a usable snapshot before returning. Throwing keeps the last good cache. */
        void onConfiguration(KeyConfiguration configuration);
        void onFailure(Throwable failure, boolean hasCache);
    }
    private static final long CHECK_INTERVAL_MS = 60_000L;
    private final String key;
    private final Store store;
    private final Clock clock;
    private final TimeoutScheduler scheduler;
    private final SdkRuntime.ConfigLoader loader;
    private final CallbackDispatcher dispatcher;
    private final Listener listener;
    private Entry current;
    private Cancellable timer;
    private boolean started, fetching, stopped;
    private long dueAtMs;
    private long retryMs = CHECK_INTERVAL_MS;
    private long sequence;

    KeyConfigRefresh(String key, Store store, Clock clock, TimeoutScheduler scheduler,
                     SdkRuntime.ConfigLoader loader, CallbackDispatcher dispatcher, Listener listener) {
        this.key = key; this.store = store; this.clock = clock; this.scheduler = scheduler;
        this.loader = loader; this.dispatcher = dispatcher; this.listener = listener;
    }
    synchronized void start() {
        if (started || stopped) return;
        started = true;
        try {
            Entry saved = store.load(key);
            if (saved != null && saved.configuration != null && saved.fetchedAtMs > 0) {
                fetching = true;
                try { listener.onConfiguration(saved.configuration); }
                finally { fetching = false; }
                current = saved;
                // A clock correction backwards must not postpone refresh indefinitely.
                dueAtMs = saved.fetchedAtMs > clock.nowMs() ? 0 : expires(saved);
            }
        } catch (RuntimeException | LinkageError ignored) { /* Corrupt/unusable cache: fetch afresh. */ }
        check();
    }
    synchronized void check() {
        if (!started || stopped || fetching) return;
        if (timer != null) { timer.cancel(); timer = null; }
        if (clock.nowMs() < dueAtMs) { schedule(); return; }
        fetching = true;
        long call = ++sequence;
        try {
            loader.request(key, (configuration, error) -> dispatcher.dispatch(() -> complete(call, configuration, error)));
        } catch (RuntimeException | LinkageError error) { complete(call, null, error); }
    }
    private synchronized void complete(long call, KeyConfiguration configuration, Throwable error) {
        if (stopped || !fetching || call != sequence) return;
        if (error == null) {
            try {
                if (configuration == null) throw new IllegalArgumentException("Empty SDK configuration");
                listener.onConfiguration(configuration);
                current = new Entry(configuration, clock.nowMs());
                dueAtMs = expires(current); retryMs = CHECK_INTERVAL_MS;
                try { store.save(key, current); }
                catch (RuntimeException ignored) { /* Memory configuration remains usable. */ }
            } catch (RuntimeException | LinkageError invalid) { error = invalid; }
        }
        if (error != null) {
            dueAtMs = add(clock.nowMs(), retryMs);
            retryMs = Math.min(retryMs * 2, 3_600_000L);
            listener.onFailure(error, current != null);
        }
        fetching = false;
        if (!stopped) schedule();
    }
    private void schedule() {
        long remaining = dueAtMs > clock.nowMs() ? dueAtMs - clock.nowMs() : 1;
        timer = scheduler.schedule(this::check, Math.min(remaining, CHECK_INTERVAL_MS));
    }
    synchronized void stop() {
        stopped = true; ++sequence;
        if (timer != null) { timer.cancel(); timer = null; }
    }
    private static long expires(Entry entry) {
        return add(entry.fetchedAtMs, entry.configuration.refreshIntervalSeconds * 1000L);
    }
    private static long add(long time, long delay) {
        return time > Long.MAX_VALUE - delay ? Long.MAX_VALUE : time + delay;
    }
}
