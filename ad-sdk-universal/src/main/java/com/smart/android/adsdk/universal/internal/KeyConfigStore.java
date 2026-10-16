package com.smart.android.adsdk.universal.internal;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Configuration and its timestamp are stored atomically under a hash of the SDK key. */
final class KeyConfigStore implements KeyConfigRefresh.Store {
    private final SharedPreferences preferences;
    KeyConfigStore(Context context) {
        preferences = context.getSharedPreferences("universal_sdk_key_config", Context.MODE_PRIVATE);
    }
    @Override public KeyConfigRefresh.Entry load(String key) {
        String json = preferences.getString(cacheKey(key), null);
        if (json == null) return null;
        try {
            JsonObject entry = JsonParser.parseString(json).getAsJsonObject();
            long fetchedAt = entry.get("fetched_at_ms").getAsBigDecimal().longValueExact();
            KeyConfiguration config = KeyConfiguration.parse(entry.get("configuration").toString());
            return fetchedAt > 0 ? new KeyConfigRefresh.Entry(config, fetchedAt) : null;
        } catch (RuntimeException invalid) { return null; }
    }
    @Override public void save(String key, KeyConfigRefresh.Entry entry) {
        JsonObject json = new JsonObject();
        json.addProperty("fetched_at_ms", entry.fetchedAtMs);
        json.add("configuration", JsonParser.parseString(entry.configuration.toJson()));
        preferences.edit().putString(cacheKey(key), json.toString()).apply();
    }
    static String cacheKey(String key) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
