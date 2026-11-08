package com.smart.android.hq008flow.internal;

import android.content.Context;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Persist the complete request/channel payload before scheduling network delivery. */
public final class DurableReports {
    static final String PREFERENCES = "hq008_flow_sdk_reports";
    private final Context context;
    private final String baseUrl;
    public DurableReports(Context context, String baseUrl) {
        this.context = context.getApplicationContext();
        this.baseUrl = baseUrl;
    }
    public void enqueue(Map<String, Object> payload) {
        String identity = baseUrl + ":" + payload.get("request_id") + ":"
                + payload.get("event_type") + ":" + payload.get("message");
        String key = "hq008-sdk-report-" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
        JsonObject saved = new JsonObject();
        saved.addProperty("base_url", baseUrl);
        saved.add("payload", new Gson().toJsonTree(payload));
        try {
            store(context).put(key, saved.toString());
        } catch (RuntimeException error) {
            SdkLog.w("Hq008FlowReport", "上报保存失败，立即发送：" + key, error);
            new FlowApiClient(baseUrl).report(payload);
            return;
        }
        schedule(key);
    }
    public void resumePending() {
        for (String key : store(context).keys()) schedule(key);
    }
    static ReportQueueStore store(Context context) {
        return ReportQueueStore.open(context, PREFERENCES);
    }
    private void schedule(String key) {
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(ReportWorker.class)
                .setInputData(new Data.Builder().putString("report_key", key).build())
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS).build();
        try { WorkManager.getInstance(context).enqueueUniqueWork(key, ExistingWorkPolicy.APPEND_OR_REPLACE, work); }
        catch (RuntimeException error) { SdkLog.w("Hq008FlowReport", "上报已保存，等待重新调度", error); }
    }
}
