package com.smart.android.adsdk.internal;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

final class RemoteAdConfigParser {
    static final int DEFAULT_AD_LOAD_TIMEOUT_MS = 20_000;
    static final long DEFAULT_AD_STARTUP_TIMEOUT_MS = 35_000L;
    private static final int SUCCESS_CODE = 100_000;
    private static final int HTTP_STYLE_SUCCESS_CODE = 200;

    private final Gson gson;

    RemoteAdConfigParser(Gson gson) {
        this.gson = gson;
    }

    RemoteAdConfigResult parse(String responseBody) throws RemoteAdConfigParseException {
        try {
            JsonElement rootElement = JsonParser.parseString(responseBody);
            if (!rootElement.isJsonObject()) {
                throw new RemoteAdConfigParseException("ad config response must be a JSON object");
            }

            JsonObject root = rootElement.getAsJsonObject();
            JsonObject data = resolveDataObject(root);
            if (data == null) {
                return RemoteAdConfigResult.skipped("NO_CONFIG_DATA");
            }
            if (data.has("enabled") && !data.get("enabled").getAsBoolean()) {
                return RemoteAdConfigResult.skipped("CONFIG_DISABLED");
            }

            String adTagUrl = readString(data, "ad_tag_url").trim();
            if (adTagUrl.isEmpty()) {
                return RemoteAdConfigResult.skipped("NO_AD_TAG");
            }

            int adLoadTimeoutMs = readPositiveInt(
                data,
                "ad_load_timeout_ms",
                DEFAULT_AD_LOAD_TIMEOUT_MS
            );
            long adStartupTimeoutMs = readPositiveLong(
                data,
                "ad_startup_timeout_ms",
                DEFAULT_AD_STARTUP_TIMEOUT_MS
            );
            return RemoteAdConfigResult.withAd(
                new AdPlaybackConfig(adTagUrl, adLoadTimeoutMs, adStartupTimeoutMs)
            );
        } catch (RemoteAdConfigParseException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new RemoteAdConfigParseException("Unable to parse ad config response", error);
        }
    }

    private JsonObject resolveDataObject(JsonObject root) throws RemoteAdConfigParseException {
        if (!root.has("code")) {
            return root;
        }
        int code = root.get("code").getAsInt();
        if (code != SUCCESS_CODE && code != HTTP_STYLE_SUCCESS_CODE) {
            throw new RemoteAdConfigParseException("ad config business code was " + code);
        }
        JsonElement dataElement = root.get("data");
        if (dataElement == null || dataElement.isJsonNull()) {
            return null;
        }
        if (!dataElement.isJsonObject()) {
            throw new RemoteAdConfigParseException("ad config data must be a JSON object");
        }
        return dataElement.getAsJsonObject();
    }

    private String readString(JsonObject object, String fieldName) {
        JsonElement element = object.get(fieldName);
        if (element == null || element.isJsonNull()) {
            return "";
        }
        return element.getAsString();
    }

    private int readPositiveInt(JsonObject object, String fieldName, int defaultValue) {
        JsonElement element = object.get(fieldName);
        if (element == null || element.isJsonNull()) {
            return defaultValue;
        }
        int value = element.getAsInt();
        return value > 0 ? value : defaultValue;
    }

    private long readPositiveLong(JsonObject object, String fieldName, long defaultValue) {
        JsonElement element = object.get(fieldName);
        if (element == null || element.isJsonNull()) {
            return defaultValue;
        }
        long value = element.getAsLong();
        return value > 0L ? value : defaultValue;
    }
}
