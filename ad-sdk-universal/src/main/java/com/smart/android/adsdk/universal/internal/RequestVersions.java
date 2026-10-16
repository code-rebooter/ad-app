package com.smart.android.adsdk.universal.internal;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import com.smart.android.adsdk.universal.BuildConfig;
import java.util.Map;

/** Version metadata for our backend APIs; TCL's registered identity stays separate. */
final class RequestVersions {
    private final long hostVersionCode;
    private final String hostVersionName;

    RequestVersions(long hostVersionCode, String hostVersionName) {
        this.hostVersionCode = hostVersionCode;
        this.hostVersionName = hostVersionName == null ? "" : hostVersionName;
    }

    /** Read only package versions, without priming the advertising device-info cache. */
    @SuppressWarnings("deprecation")
    static RequestVersions collect(Context context) {
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        try {
            PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? info.getLongVersionCode() : info.versionCode;
            return new RequestVersions(code, info.versionName);
        } catch (PackageManager.NameNotFoundException failure) {
            throw new IllegalStateException("Unable to read host package versions", failure);
        }
    }

    static void appendTo(Map<String, Object> body, DeviceInfo host) {
        new RequestVersions(host == null ? 0L : host.versionCode, host == null ? "" : host.versionName)
            .appendTo(body);
    }

    void appendTo(Map<String, Object> body) {
        body.put("ad_version", hostVersionCode);
        body.put("ad_version_name", hostVersionName);
        body.put("sdk_version", BuildConfig.SDK_VERSION);
    }
}
