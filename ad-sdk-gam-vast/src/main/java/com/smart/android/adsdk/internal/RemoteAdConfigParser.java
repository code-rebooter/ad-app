package com.smart.android.adsdk.internal;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.HttpUrl;

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
        return parse(responseBody, null);
    }

    RemoteAdConfigResult parse(
        String responseBody,
        FlowAuthorizedConfig flowConfig
    ) throws RemoteAdConfigParseException {
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

            String adTagUrl = normalizeAdTagUrl(readString(data, "ad_tag_url").trim());
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
                new AdPlaybackConfig(
                    adTagUrl,
                    adLoadTimeoutMs,
                    adStartupTimeoutMs,
                    flowConfig == null ? null : flowConfig.getRequestId(),
                    flowConfig != null && flowConfig.isHiddenMode(),
                    flowConfig == null ? null : flowConfig.isSoundEnabled(),
                    flowConfig == null ? 0L : flowConfig.getNextRequestSeconds()
                )
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

    private String normalizeAdTagUrl(String adTagUrl) {
        HttpUrl url = HttpUrl.parse(adTagUrl);
        if (url == null || !isGamAdTagUrl(url)) {
            return adTagUrl;
        }
        String correlator = url.queryParameter("correlator");
        if (correlator != null && !correlator.trim().isEmpty() && !isMacroValue(correlator)) {
            return adTagUrl;
        }
        HttpUrl.Builder builder = url.newBuilder();
        builder.removeAllQueryParameters("correlator");
        builder.addQueryParameter(
            "correlator",
            String.valueOf(System.currentTimeMillis()) + Math.abs(System.nanoTime())
        );
        return builder.build().toString();
    }

    private boolean isGamAdTagUrl(HttpUrl url) {
        return "pubads.g.doubleclick.net".equalsIgnoreCase(url.host())
            && "/gampad/ads".equals(url.encodedPath());
    }

    private boolean isMacroValue(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("[")
            || trimmed.startsWith("%%")
            || trimmed.contains("CACHEBUST")
            || trimmed.contains("CORRELATOR");
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
