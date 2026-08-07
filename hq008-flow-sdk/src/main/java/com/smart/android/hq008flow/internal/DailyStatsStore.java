package com.smart.android.hq008flow.internal;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

public final class DailyStatsStore {
    private static final String TAG = "Hq008FlowStats";
    private static final String PREFS_NAME = "hq008_flow_daily_metrics_v2";
    private static final String TIMEZONE_ID = "Asia/Shanghai";
    private static final String KEY_CURRENT_DAY = "current_day";
    private static final String KEY_SCREENSAVER_START = "screensaver_start_total";
    private static final String KEY_SCREENSAVER_STOP = "screensaver_stop_total";
    private static final String KEY_AUTHORIZED_CALLBACK = "authorized_callback_total";
    private static final String KEY_FINAL_STATUS_INDEX = "final_status_index";
    private static final String KEY_FINAL_STATUS_TOTAL_PREFIX = "final_status_total_";
    private static final String PAIR_SEPARATOR = "\n";

    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_TIMEOUT = "TIMEOUT";
    public static final String STATUS_NO_AD_CALLBACK = "NO_AD_CALLBACK";
    public static final String STATUS_FLOW_STOPPED = "FLOW_STOPPED";

    private final SharedPreferences preferences;
    private final String channelId;
    private final Object lock = new Object();
    private final SimpleDateFormat dayFormat;

    public DailyStatsStore(Context context, String channelId) {
        this.preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.channelId = channelId;
        this.dayFormat = new SimpleDateFormat("yyyyMMdd", Locale.US);
        this.dayFormat.setTimeZone(TimeZone.getTimeZone(TIMEZONE_ID));
    }

    public void recordScreensaverStart(long nowMs) {
        incrementSimple(KEY_SCREENSAVER_START, nowMs);
    }

    public void recordScreensaverStop(long nowMs) {
        incrementSimple(KEY_SCREENSAVER_STOP, nowMs);
    }

    public void recordAuthorizedCallback(long nowMs) {
        incrementSimple(KEY_AUTHORIZED_CALLBACK, nowMs);
    }

    public Snapshot recordFinalStatus(String status, String message, long nowMs) {
        Snapshot snapshot;
        synchronized (lock) {
            ensureDayLocked(nowMs);
            String resolvedStatus = normalize(status, STATUS_FAILED);
            String resolvedMessage = normalize(message, resolvedStatus);
            String encodedPair = encodePair(resolvedStatus, resolvedMessage);
            Set<String> index = new LinkedHashSet<>(
                    preferences.getStringSet(scopedKey(KEY_FINAL_STATUS_INDEX), Collections.emptySet())
            );
            index.add(encodedPair);
            long total = preferences.getLong(finalStatusTotalKey(encodedPair), 0L) + 1L;
            preferences.edit()
                    .putStringSet(scopedKey(KEY_FINAL_STATUS_INDEX), index)
                    .putLong(finalStatusTotalKey(encodedPair), total)
                    .apply();
            snapshot = buildSnapshotLocked(resolvedStatus, resolvedMessage);
        }
        logSnapshot(snapshot);
        return snapshot;
    }

    private void incrementSimple(String key, long nowMs) {
        Snapshot snapshot;
        synchronized (lock) {
            ensureDayLocked(nowMs);
            preferences.edit()
                    .putLong(scopedKey(key), preferences.getLong(scopedKey(key), 0L) + 1L)
                    .apply();
            snapshot = buildSnapshotLocked(null, null);
        }
        logSnapshot(snapshot);
    }

    private void ensureDayLocked(long nowMs) {
        String today = formatDay(nowMs);
        String storedDay = preferences.getString(scopedKey(KEY_CURRENT_DAY), null);
        if (today.equals(storedDay)) {
            return;
        }
        SharedPreferences.Editor editor = preferences.edit();
        Set<String> existingFinalStatuses = preferences.getStringSet(
                scopedKey(KEY_FINAL_STATUS_INDEX),
                Collections.emptySet()
        );
        for (String encodedPair : existingFinalStatuses) {
            editor.remove(finalStatusTotalKey(encodedPair));
        }
        editor.putString(scopedKey(KEY_CURRENT_DAY), today)
                .putLong(scopedKey(KEY_SCREENSAVER_START), 0L)
                .putLong(scopedKey(KEY_SCREENSAVER_STOP), 0L)
                .putLong(scopedKey(KEY_AUTHORIZED_CALLBACK), 0L)
                .putStringSet(scopedKey(KEY_FINAL_STATUS_INDEX), new LinkedHashSet<>())
                .apply();
    }

    private Snapshot buildSnapshotLocked(String currentFinalStatus, String currentFinalMessage) {
        String day = preferences.getString(scopedKey(KEY_CURRENT_DAY), "");
        List<String> encodedPairs = new ArrayList<>(
                preferences.getStringSet(scopedKey(KEY_FINAL_STATUS_INDEX), Collections.emptySet())
        );
        Collections.sort(encodedPairs);
        List<FinalStatusTotal> finalStatusTotals = new ArrayList<>();
        for (String encodedPair : encodedPairs) {
            String[] pair = decodePair(encodedPair);
            if (pair == null) {
                continue;
            }
            finalStatusTotals.add(new FinalStatusTotal(
                    pair[0],
                    pair[1],
                    preferences.getLong(finalStatusTotalKey(encodedPair), 0L)
            ));
        }
        return new Snapshot(
                day,
                TIMEZONE_ID,
                preferences.getLong(scopedKey(KEY_SCREENSAVER_START), 0L),
                preferences.getLong(scopedKey(KEY_SCREENSAVER_STOP), 0L),
                preferences.getLong(scopedKey(KEY_AUTHORIZED_CALLBACK), 0L),
                currentFinalStatus,
                currentFinalMessage,
                finalStatusTotals
        );
    }

    private void logSnapshot(Snapshot snapshot) {
        if (!SdkLog.isEnabled()) {
            return;
        }
        SdkLog.i(
                TAG,
                "daily metrics day=" + snapshot.day
                        + " timezone=" + snapshot.timezone
                        + " channel=" + channelId
                        + " screensaverStart=" + snapshot.screensaverStartTotal
                        + " screensaverStop=" + snapshot.screensaverStopTotal
                        + " authorizedCallback=" + snapshot.authorizedCallbackTotal
                        + " currentFinalStatus=" + snapshot.currentFinalStatus
                        + " currentFinalMessage=" + snapshot.currentFinalMessage
        );
    }

    private String formatDay(long nowMs) {
        synchronized (dayFormat) {
            return dayFormat.format(new Date(nowMs));
        }
    }

    private String normalize(String value, String fallback) {
        String resolved = value == null ? "" : value.trim();
        return resolved.isEmpty() ? fallback : resolved;
    }

    private String encodePair(String status, String message) {
        String raw = status + PAIR_SEPARATOR + message;
        return Base64.encodeToString(raw.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private String[] decodePair(String encodedPair) {
        try {
            String raw = new String(Base64.decode(encodedPair, Base64.NO_WRAP), StandardCharsets.UTF_8);
            int separator = raw.indexOf(PAIR_SEPARATOR);
            if (separator < 0) {
                return null;
            }
            return new String[] {
                    raw.substring(0, separator),
                    raw.substring(separator + PAIR_SEPARATOR.length())
            };
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String scopedKey(String key) {
        return channelId + "_" + key;
    }

    private String finalStatusTotalKey(String encodedPair) {
        return scopedKey(KEY_FINAL_STATUS_TOTAL_PREFIX + encodedPair);
    }

    public static final class Snapshot {
        public final String day;
        public final String timezone;
        public final long screensaverStartTotal;
        public final long screensaverStopTotal;
        public final long authorizedCallbackTotal;
        public final String currentFinalStatus;
        public final String currentFinalMessage;
        public final List<FinalStatusTotal> finalStatusTotals;

        Snapshot(
                String day,
                String timezone,
                long screensaverStartTotal,
                long screensaverStopTotal,
                long authorizedCallbackTotal,
                String currentFinalStatus,
                String currentFinalMessage,
                List<FinalStatusTotal> finalStatusTotals
        ) {
            this.day = day;
            this.timezone = timezone;
            this.screensaverStartTotal = screensaverStartTotal;
            this.screensaverStopTotal = screensaverStopTotal;
            this.authorizedCallbackTotal = authorizedCallbackTotal;
            this.currentFinalStatus = currentFinalStatus;
            this.currentFinalMessage = currentFinalMessage;
            this.finalStatusTotals = finalStatusTotals;
        }
    }

    public static final class FinalStatusTotal {
        public final String status;
        public final String message;
        public final long total;

        FinalStatusTotal(String status, String message, long total) {
            this.status = status;
            this.message = message;
            this.total = total;
        }
    }
}
