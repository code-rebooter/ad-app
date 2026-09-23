package com.smart.android.adsdk.internal;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class SystemMaintenanceGuard {
    private static final String TAG = "AdSystemJobGuard";
    private static final String ENABLED_METADATA =
        "com.smart.android.adsdk.DISABLE_SYSTEM_MAINTENANCE_JOBS";
    private static final int[] JOB_IDS = {800, 801, 808};
    private static ScheduledExecutorService executor;

    private SystemMaintenanceGuard() {
    }

    static boolean isEnabled(Context context) {
        if (Process.myUid() != Process.SYSTEM_UID || Build.VERSION.SDK_INT < 24) {
            return false;
        }
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                context.getPackageName(), PackageManager.GET_META_DATA);
            return info.metaData != null && info.metaData.getBoolean(ENABLED_METADATA, false);
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            Log.w(TAG, "Cannot read maintenance guard configuration", error);
            return false;
        }
    }

    static void start(Context context, String reason) {
        if (!isEnabled(context)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        beginPolling(appContext == null ? context : appContext);
        try {
            context.startService(new Intent(context, SystemMaintenanceGuardService.class));
            Log.i(TAG, "Guard requested source=" + reason + " uid=" + Process.myUid());
        } catch (RuntimeException error) {
            Log.w(TAG, "Guard service unavailable; process guard remains active", error);
        }
    }

    static synchronized void beginPolling(Context context) {
        if (executor != null || !isEnabled(context)) {
            return;
        }
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) {
            Log.e(TAG, "JobScheduler unavailable");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(action -> {
            Thread thread = new Thread(action, "ad-system-job-guard");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(new Runnable() {
            private int checks;
            private int failures;

            @Override
            public void run() {
                try {
                    for (int id : JOB_IDS) {
                        JobInfo job = scheduler.getPendingJob(id);
                        if (job == null) {
                            continue;
                        }
                        String service = job.getService().getClassName();
                        boolean expectedService = id == 808
                            ? "com.android.server.MountServiceIdler".equals(service)
                            : "com.android.server.pm.BackgroundDexOptService".equals(service)
                                || "com.android.server.pm.BackgroundDexOptJobService".equals(service);
                        if (!"android".equals(job.getService().getPackageName()) || !expectedService) {
                            if (checks % 60 == 0) {
                                Log.w(TAG, "Leaving unrelated job " + id + " " + job.getService());
                            }
                            continue;
                        }
                        scheduler.cancel(id);
                        Log.i(TAG, "Cancelled system job=" + id + " service=" + service
                            + " pendingAfter=" + (scheduler.getPendingJob(id) != null));
                    }
                    if (checks++ % 60 == 0) {
                        Log.i(TAG, "Guard active jobs=800,801,808 interval=5s");
                    }
                    failures = 0;
                } catch (RuntimeException error) {
                    if (failures++ % 60 == 0) {
                        Log.e(TAG, "Maintenance job cancellation failed", error);
                    }
                }
            }
        }, 0L, 5L, TimeUnit.SECONDS);
    }
}
