package com.smart.android.hq008flow.internal;

import android.content.Context;
import android.content.SharedPreferences;

public final class ScheduleStore {
    private static final String PREFS_NAME = "hq008_flow_schedule";
    private static final long MIN_SERVER_INTERVAL_SECONDS = 10L;
    private static final long MAX_SERVER_INTERVAL_SECONDS = 24L * 60L * 60L;

    private final SharedPreferences preferences;
    private final String channelId;
    private final long fallbackIntervalSeconds;

    public ScheduleStore(Context context, String channelId, long fallbackIntervalSeconds) {
        this.preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.channelId = channelId;
        this.fallbackIntervalSeconds = fallbackIntervalSeconds;
    }

    public void markTriggered(long nowMs) {
        preferences.edit().putLong(lastTriggerKey(), nowMs).apply();
    }

    public void updateServerInterval(long seconds) {
        if (seconds >= MIN_SERVER_INTERVAL_SECONDS && seconds <= MAX_SERVER_INTERVAL_SECONDS) {
            preferences.edit().putLong(serverIntervalKey(), seconds).apply();
        } else {
            preferences.edit().remove(serverIntervalKey()).apply();
        }
    }

    public long effectiveIntervalSeconds() {
        long value = preferences.getLong(serverIntervalKey(), 0L);
        if (value >= MIN_SERVER_INTERVAL_SECONDS && value <= MAX_SERVER_INTERVAL_SECONDS) {
            return value;
        }
        return fallbackIntervalSeconds;
    }

    public long initialDelayMs(long nowMs, long configuredInitialDelaySeconds) {
        long initialDelayMs = configuredInitialDelaySeconds * 1_000L;
        long lastTriggered = preferences.getLong(lastTriggerKey(), 0L);
        if (lastTriggered <= 0L) {
            return initialDelayMs;
        }
        long elapsed = nowMs - lastTriggered;
        if (elapsed < 0L) {
            return initialDelayMs;
        }
        long remaining = effectiveIntervalSeconds() * 1_000L - elapsed;
        return remaining <= 0L ? initialDelayMs : remaining;
    }

    private String lastTriggerKey() {
        return "last_trigger_" + channelId;
    }

    private String serverIntervalKey() {
        return "server_interval_" + channelId;
    }
}
