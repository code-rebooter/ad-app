package com.smart.android.hq008flow;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Immutable configuration captured from this attempt's authorize response. */
public final class Hq008Authorization {
    private final JsonObject values;
    private final String requestId;
    private final String channelId;
    private final long callbackTimeoutMs;
    private final boolean popupLogEnabled;
    private final boolean skipCmp;

    Hq008Authorization(JsonObject values, String requestId, String channelId,
            long callbackTimeoutMs, boolean popupLogEnabled, boolean skipCmp) {
        this.values = values.deepCopy();
        this.requestId = requestId;
        this.channelId = channelId;
        this.callbackTimeoutMs = callbackTimeoutMs;
        this.popupLogEnabled = popupLogEnabled;
        this.skipCmp = skipCmp;
    }
    public String getRequestId() { return requestId; }
    public String getChannelId() { return channelId; }
    public long getCallbackTimeoutMs() { return callbackTimeoutMs; }
    public boolean isPopupLogEnabled() { return popupLogEnabled; }
    public boolean isSkipCmp() { return skipCmp; }
    public boolean isHiddenMode() { return bool("hidden_mode", true); }
    public boolean isSoundEnabled() { return bool("sound_mode", false); }
    public String getClientIp() { return text("client_ip"); }
    public Integer getFloatingWidth() { return integer("floating_width", "floatingWidth"); }
    public Integer getFloatingHeight() { return integer("floating_height", "floatingHeight"); }
    public Integer getFloatingX() { return integer("floating_x", "floatingX"); }
    public Integer getFloatingY() { return integer("floating_y", "floatingY"); }
    public Integer getPosition() { return integer("position", "position"); }
    public String getRawJson() { return values.toString(); }
    public String getGamConfigJson() {
        JsonElement nested = values.get("gam_config");
        JsonObject config;
        if (nested != null && !nested.isJsonNull()) {
            if (!nested.isJsonObject()) return nested.toString();
            config = nested.getAsJsonObject().deepCopy();
        } else {
            if (!values.has("ad_tag_url")) return null;
            config = new JsonObject();
            for (String key : new String[]{"ad_tag_url", "ad_load_timeout_ms", "ad_startup_timeout_ms"}) {
                if (values.has(key)) config.add(key, values.get(key).deepCopy());
            }
        }
        if (!config.has("reported_sound_mode") && values.has("reported_sound_mode")) {
            config.add("reported_sound_mode", values.get("reported_sound_mode").deepCopy());
        }
        return config.toString();
    }
    private boolean bool(String key, boolean fallback) {
        try { return values.has(key) && !values.get(key).isJsonNull() ? values.get(key).getAsBoolean() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
    private String text(String key) {
        try { return values.has(key) ? values.get(key).getAsString() : ""; }
        catch (RuntimeException ignored) { return ""; }
    }
    private Integer integer(String key, String alternate) {
        try {
            JsonElement value = values.has(key) ? values.get(key) : values.get(alternate);
            return value == null || value.isJsonNull() ? null : value.getAsInt();
        } catch (RuntimeException ignored) { return null; }
    }
}
