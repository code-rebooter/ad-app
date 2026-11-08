package com.smart.android.hq008flow.internal;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ReportWorker extends Worker {
    public ReportWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }
    @NonNull @Override public Result doWork() {
        String key = getInputData().getString("report_key");
        if (key == null) return Result.failure();
        FlowApiClient client = null;
        try {
            String raw = DurableReports.store(getApplicationContext()).read(key);
            if (raw == null) return Result.success();
            JsonObject saved = JsonParser.parseString(raw).getAsJsonObject();
            client = new FlowApiClient(saved.get("base_url").getAsString());
            CountDownLatch done = new CountDownLatch(1);
            AtomicBoolean success = new AtomicBoolean();
            client.reportJson(saved.get("payload").toString(), (response, error) -> { success.set(error == null); done.countDown(); });
            if (!done.await(60L, TimeUnit.SECONDS) || !success.get()) return Result.retry();
            // A same-key newer body remains for the appended successor.
            DurableReports.store(getApplicationContext()).acknowledge(key, raw);
            return Result.success();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return Result.retry();
        } catch (Exception error) {
            SdkLog.w("Hq008FlowReport", "上报失败，保留重试：" + key, error);
            return Result.retry();
        } finally { if (client != null) client.cancelAll(); }
    }
}
