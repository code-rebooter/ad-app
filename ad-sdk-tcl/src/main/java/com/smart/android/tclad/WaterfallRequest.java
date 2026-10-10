package com.smart.android.tclad;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import com.google.gson.Gson;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** The 58-field request observed in TCL 2.8.02's non-live WATERFALL path. */
public final class WaterfallRequest {
    private final Map<String, Object> values;
    private WaterfallRequest(Map<String, Object> source) {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
    public Map<String, Object> getValues() { return values; }
    public String toJson() { return new Gson().toJson(values); }

    public static final class Builder {
        private final Map<String, Object> base = new LinkedHashMap<>();
        /** For hosts that supply their own identity/device collection. */
        public Builder() {
            for (String key : new String[]{"appPackage", "appVersion", "appKey", "DevicePartner", "asfm",
                "appName", "appBundle", "appStoreUrl", "userAgent", "deviceMake", "deviceModel", "did", "IFAType"})
                base.put(key, "");
            base.put("sdkVersion", "2.8.02");
            base.put("application", 0);
            base.put("playerWidth", 0);
            base.put("playerHeight", 0);
            base.put("minAdDuration", 5);
            base.put("maxAdDuration", 60);
            base.put("podDuration", 120);
            base.put("adType", 2);
            base.put("placement", 1);
            base.put("skip", 0);
            base.put("ap", 1);
            base.put("flags", 0);
        }
        /** Collects the real host identity and device; the host supplies its advertising ID. */
        @SuppressWarnings("deprecation")
        public Builder(Context context) {
            this();
            Context app = context.getApplicationContext();
            PackageManager manager = app.getPackageManager();
            String packageName = app.getPackageName();
            setAppPackage(packageName);
            setAppBundle(packageName);
            setAppName(manager.getApplicationLabel(app.getApplicationInfo()).toString());
            setDeviceMake(Build.MANUFACTURER);
            setDeviceModel(Build.MODEL);
            setUserAgent(System.getProperty("http.agent", ""));
            try {
                PackageInfo info = manager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
                setAppVersion(info.versionName);
                if (info.signatures != null && info.signatures.length > 0) {
                    byte[] digest = MessageDigest.getInstance("MD5").digest(info.signatures[0].toByteArray());
                    StringBuilder hex = new StringBuilder(32);
                    for (byte value : digest) hex.append(String.format(Locale.US, "%02X", value & 255));
                    setSignatureMd5(hex.toString());
                }
            } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException error) {
                throw new IllegalStateException("Cannot read host application identity", error);
            }
        }
        private Builder put(String key, String value) { if (value != null) base.put(key, value); return this; }
        public Builder setAppPackage(String v) { return put("appPackage", v); }
        public Builder setAppVersion(String v) { return put("appVersion", v); }
        public Builder setAppName(String v) { return put("appName", v); }
        public Builder setAppBundle(String v) { return put("appBundle", v); }
        public Builder setAppStoreUrl(String v) { return put("appStoreUrl", v); }
        public Builder setAppKey(String v) { return put("appKey", v); }
        public Builder setDevicePartner(String v) { return put("DevicePartner", v); }
        public Builder setSignatureMd5(String v) { return put("asfm", v); }
        public Builder setSdkVersion(String v) { return put("sdkVersion", v); }
        public Builder setApplication(int v) { base.put("application", v); return this; }
        public Builder setUserAgent(String v) { return put("userAgent", v); }
        public Builder setDeviceMake(String v) { return put("deviceMake", v); }
        public Builder setDeviceModel(String v) { return put("deviceModel", v); }
        public Builder setAdvertisingId(String id, String type) { put("did", id); return put("IFAType", type); }
        public Builder setPlayerSize(int width, int height) {
            if (width < 0 || height < 0) throw new IllegalArgumentException("Player size must be nonnegative");
            base.put("playerWidth", width); base.put("playerHeight", height); return this;
        }
        /** Seconds; defaults match the captured 5 / 60 / 120 configuration. */
        public Builder setAdDurations(int min, int max, int pod) {
            if (min < 0 || max < min || pod < max) throw new IllegalArgumentException("Invalid ad durations");
            base.put("minAdDuration", min); base.put("maxAdDuration", max); base.put("podDuration", pod); return this;
        }
        /** Snapshot safe to reuse for concurrent requests. Each resolution gets fresh times. */
        public WaterfallRequest build(RequestParams params) {
            Map<String, Object> result = new LinkedHashMap<>(params == null
                ? new RequestParams.Builder().build().getTypedValues() : params.getTypedValues());
            for (Map.Entry<String, Object> entry : base.entrySet()) {
                Object override = result.get(entry.getKey());
                if (override == null || override.toString().isEmpty()) result.put(entry.getKey(), entry.getValue());
            }
            result.put("cb", System.currentTimeMillis());
            result.put("timestamp", System.currentTimeMillis());
            return new WaterfallRequest(result);
        }
    }

    WaterfallRequest withParams(RequestParams params) {
        Map<String, Object> result = new LinkedHashMap<>(values);
        if (params != null) for (Map.Entry<String, Object> entry : params.getTypedValues().entrySet()) {
            if ((entry.getKey().equals("deviceMake") || entry.getKey().equals("deviceModel")
                || entry.getKey().equals("appStoreUrl")) && entry.getValue().toString().isEmpty()) continue;
            result.put(entry.getKey(), entry.getValue());
        }
        result.put("cb", System.currentTimeMillis());
        result.put("timestamp", System.currentTimeMillis());
        return new WaterfallRequest(result);
    }
}
