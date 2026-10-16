package com.smart.android.adsdk.universal.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Immutable customer registration and separate business channels from the key endpoint. */
final class KeyConfiguration {
    final String googleChannelId;
    final String tclChannelId;
    final TclIdentity tclIdentity;
    static final long DEFAULT_REFRESH_SECONDS = 86_400L;
    final long refreshIntervalSeconds;
    private final String json;

    private KeyConfiguration(JsonObject data) {
        JsonObject google = data.getAsJsonObject("google");
        JsonObject tcl = data.getAsJsonObject("tcl");
        googleChannelId = channel(google, "google");
        tclChannelId = channel(tcl, "tcl");
        tclIdentity = TclIdentity.from(tcl);
        refreshIntervalSeconds = refreshSeconds(data.get("refresh_interval_seconds"));
        JsonObject root = new JsonObject(); root.addProperty("code", 100000); root.add("data", data.deepCopy());
        json = root.toString();
    }

    static KeyConfiguration parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("code") || root.get("code").getAsBigDecimal().intValueExact() != 100000)
            throw new IllegalArgumentException("SDK key configuration failed");
        JsonObject data = root.getAsJsonObject("data");
        if (data == null) throw new IllegalArgumentException("Missing SDK key configuration");
        return new KeyConfiguration(data);
    }

    String toJson() { return json; }
    boolean sameRegistration(KeyConfiguration other) {
        return other != null && googleChannelId.equals(other.googleChannelId)
            && tclChannelId.equals(other.tclChannelId) && tclIdentity.sameValues(other.tclIdentity);
    }
    private static long refreshSeconds(JsonElement value) {
        try {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return DEFAULT_REFRESH_SECONDS;
            long seconds = value.getAsBigDecimal().longValueExact();
            return seconds > 0 && seconds <= Long.MAX_VALUE / 1000 ? seconds : DEFAULT_REFRESH_SECONDS;
        } catch (RuntimeException invalid) { return DEFAULT_REFRESH_SECONDS; }
    }

    private static String channel(JsonObject platform, String name) {
        JsonElement value = platform == null ? null : platform.get("channel_id");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
            || value.getAsString().trim().isEmpty())
            throw new IllegalArgumentException("Missing " + name + " channel_id");
        return value.getAsString().trim();
    }
}
