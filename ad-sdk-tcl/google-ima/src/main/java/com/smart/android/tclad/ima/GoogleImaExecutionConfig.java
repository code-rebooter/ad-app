package com.smart.android.tclad.ima;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.provider.Settings;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.UUID;

/** Identity and backend options frozen for one ad attempt. */
public final class GoogleImaExecutionConfig {
    private final String requestId, channelId, patchEndpoint;
    private final boolean auditEnabled;
    private final long callbackTimeoutMs;
    private final boolean hiddenMode;
    private final Map<String, Object> patchParameters;
    private GoogleImaExecutionConfig(Builder builder) {
        requestId = requireText(builder.requestId);
        channelId = requireText(builder.channelId);
        patchEndpoint = builder.patchEndpoint;
        auditEnabled = builder.auditEnabled;
        hiddenMode = builder.hiddenMode;
        callbackTimeoutMs = builder.callbackTimeoutSeconds * 1_000L;
        Map<String, Object> parameters = new LinkedHashMap<>(builder.parameters);
        parameters.put("request_id", requestId);
        parameters.put("channel_id", channelId);
        parameters.put("sdk_version", "3.35.1");
        patchParameters = Collections.unmodifiableMap(parameters);
    }
    public static Builder builder(Context context, String requestId, String channelId) {
        return new Builder(context, requestId, channelId);
    }
    public String getRequestId() { return requestId; }
    public String getChannelId() { return channelId; }
    public String getPatchEndpoint() { return patchEndpoint; }
    public boolean isHiddenMode() { return hiddenMode; }
    public boolean isAuditEnabled() { return auditEnabled; }
    public long getCallbackTimeoutMs() { return callbackTimeoutMs; }
    public Map<String, Object> getPatchParameters() { return patchParameters; }
    private static String requireText(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Request/channel identity required");
        return value;
    }
    public static final class Builder {
        private final String requestId, channelId;
        private String patchEndpoint = "https://api.kytira.cc/api/v2/ad/google-gam/patch";
        private boolean auditEnabled = true;
        private long callbackTimeoutSeconds = 180L;
        private boolean hiddenMode;
        private final Map<String, Object> parameters = new LinkedHashMap<>();
        private Builder(Context context, String requestId, String channelId) {
            this.requestId = requestId;
            this.channelId = channelId;
            Context app = context.getApplicationContext();
            String androidId;
            try { androidId = Settings.Secure.getString(app.getContentResolver(), Settings.Secure.ANDROID_ID); }
            catch (RuntimeException ignored) { androidId = ""; }
            parameters.put("androidid", androidId == null ? "" : androidId);
            parameters.put("mac", mac());
            try {
                PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
                parameters.put("ad_version", Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode);
                parameters.put("ad_version_name", info.versionName == null ? "" : info.versionName);
            } catch (Exception ignored) {
                parameters.put("ad_version", 0);
                parameters.put("ad_version_name", "");
            }
            parameters.put("rdid", TclIdentityPersistence.readOrCreate(app));
        }
        public Builder callbackTimeoutSeconds(Long seconds) {
            callbackTimeoutSeconds = seconds != null && seconds >= 30L && seconds <= 600L ? seconds : 180L;
            return this;
        }
        public Builder hiddenMode(boolean hidden) { hiddenMode = hidden; return this; }
        public Builder auditEnabled(boolean enabled) { auditEnabled = enabled; return this; }
        public Builder patchEndpoint(String endpoint) {
            okhttp3.HttpUrl url = okhttp3.HttpUrl.get(endpoint);
            if (!url.isHttps()) throw new IllegalArgumentException("Patch endpoint must use HTTPS");
            patchEndpoint = endpoint;
            return this;
        }
        public Builder patchParameters(Map<String, ?> values) { parameters.putAll(values); return this; }
        public GoogleImaExecutionConfig build() { return new GoogleImaExecutionConfig(this); }
        private static String mac() {
            try {
                for (String name : new String[]{"eth0", "wlan0"}) {
                    NetworkInterface network = NetworkInterface.getByName(name);
                    byte[] address = network == null ? null : network.getHardwareAddress();
                    if (address == null || address.length != 6) continue;
                    StringBuilder value = new StringBuilder();
                    for (byte item : address) {
                        if (value.length() > 0) value.append(':');
                        value.append(String.format(java.util.Locale.US, "%02X", item & 255));
                    }
                    String result = value.toString();
                    if (!result.equals("02:00:00:00:00:00") && !result.equals("00:00:00:00:00:00")
                            && !result.equals("FF:FF:FF:FF:FF:FF")) return result;
                }
            } catch (Exception ignored) { }
            return "00:00:00:00:00:00";
        }
    }
}
