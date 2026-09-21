package com.smart.android.adsdk.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;

final class AdResponseException extends IOException {
    final String source;
    final String originalCode;
    final String responseBody;

    AdResponseException(String source, String code, String message, String body) {
        super(message);
        this.source = source;
        this.originalCode = code;
        this.responseBody = body;
    }

    static AdResponseException http(int code, String statusMessage, String body) {
        return new AdResponseException("HTTP", String.valueOf(code), message(body, statusMessage), body);
    }

    static AdResponseException api(String body, String fallback) {
        return new AdResponseException("API", code(body), message(body, fallback), body);
    }

    static AdResponseException parsing(Throwable error, String body) {
        AdResponseException result = new AdResponseException("JSON", null, error.getMessage(), body);
        result.initCause(error);
        return result;
    }

    static String message(String body, String fallback) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            for (String container : new String[] {"data", "result"}) {
                JsonElement nested = root.get(container);
                if (nested != null && nested.isJsonObject()) {
                    String value = message(nested.getAsJsonObject());
                    if (value != null) return value;
                }
            }
            String value = message(root);
            if (value != null) return value;
        } catch (RuntimeException ignored) {}
        return body == null || body.isEmpty() ? fallback : body;
    }

    static String reason(String body, String fallback) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            for (String container : new String[] {"data", "result"}) {
                JsonElement nested = root.get(container);
                if (nested != null && nested.isJsonObject()) {
                    JsonElement value = nested.getAsJsonObject().get("reason");
                    if (value != null && value.isJsonPrimitive()) return value.getAsString();
                }
            }
            JsonElement value = root.get("reason");
            if (value != null && value.isJsonPrimitive()) return value.getAsString();
        } catch (RuntimeException ignored) {}
        return fallback;
    }

    private static String message(JsonObject object) {
        for (String key : new String[] {"reason", "message", "msg", "error"}) {
            JsonElement value = object.get(key);
            if (value != null && value.isJsonPrimitive() && !value.getAsString().isEmpty()) {
                return value.getAsString();
            }
        }
        return null;
    }

    private static String code(String body) {
        try {
            JsonElement code = JsonParser.parseString(body).getAsJsonObject().get("code");
            return code == null || code.isJsonNull() ? null : code.getAsString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
