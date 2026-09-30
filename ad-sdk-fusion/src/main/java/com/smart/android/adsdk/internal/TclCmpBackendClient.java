package com.smart.android.adsdk.internal;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.smart.android.adsdk.AdError;
import com.smart.android.adsdk.AdErrorCode;
import com.smart.android.adsdk.AdErrorStage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** TCL's backend decision protocol; applying the decision belongs to the CMP manager. */
final class TclCmpBackendClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient http;
    private final Gson gson;
    private final String popupUrl;
    private final String reportUrl;
    private final Supplier<DeviceInfo> deviceInfo;
    private final IntSupplier androidSdk;

    TclCmpBackendClient(OkHttpClient http, Gson gson, String apiBaseUrl,
        Supplier<DeviceInfo> deviceInfo, IntSupplier androidSdk) {
        this.http = http;
        this.gson = gson;
        String baseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl : apiBaseUrl + "/";
        this.popupUrl = baseUrl + "api/v2/ad/consent-popup";
        this.reportUrl = baseUrl + "api/v2/ad/consent-report";
        this.deviceInfo = deviceInfo;
        this.androidSdk = androidSdk;
    }

    Cancellable request(String channel, boolean consentExpired, DecisionCallback callback) {
        Map<String, Object> body = commonBody(channel);
        body.put("consent_expired", consentExpired);
        return post(popupUrl, body, true, callback);
    }

    Cancellable report(String channel, String action, ReportCallback callback) {
        Map<String, Object> body = commonBody(channel);
        body.put("android_sdk_version", androidSdk.getAsInt());
        body.put("consent_action", action);
        return post(reportUrl, body, false, (decision, error) -> callback.onResult(error));
    }

    private Map<String, Object> commonBody(String channel) {
        DeviceInfo info = deviceInfo.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel_id", channel);
        body.put("mac", info == null || info.mac == null || info.mac.isBlank()
            ? "00:00:00:00:00:00" : info.mac);
        body.put("ad_version", info == null ? 0L : info.versionCode);
        return body;
    }

    private Cancellable post(String url, Map<String, Object> requestBody,
        boolean needsDecision, DecisionCallback callback) {
        Request request = new Request.Builder().url(url)
            .post(RequestBody.create(gson.toJson(requestBody), JSON)).build();
        Call call = http.newCall(request);
        call.enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException error) {
                if (!call.isCanceled()) {
                    callback.onResult(null, AdErrors.from(AdErrorCode.CONFIG_NETWORK_ERROR,
                        AdErrorStage.CONFIG, error, null));
                }
            }

            @Override public void onResponse(Call call, Response response) {
                String body = null;
                Decision decision = null;
                AdError error = null;
                try (Response closeableResponse = response) {
                    ResponseBody responseBody = closeableResponse.body();
                    body = responseBody == null ? "" : responseBody.string();
                    if (!closeableResponse.isSuccessful()) {
                        throw AdResponseException.http(closeableResponse.code(),
                            closeableResponse.message(), body);
                    }
                    JsonObject root = responseObject(body);
                    if (root.has("code")) {
                        int code = root.get("code").getAsInt();
                        if (code != 100_000 && code != 200) {
                            throw AdResponseException.api(body, "CMP request failed");
                        }
                    }
                    if (needsDecision) decision = parseDecision(root);
                } catch (AdResponseException failure) {
                    error = AdErrors.from(AdErrorCode.CONFIG_HTTP_ERROR,
                        AdErrorStage.CONFIG, failure, body);
                } catch (IOException failure) {
                    error = AdErrors.from(AdErrorCode.CONFIG_NETWORK_ERROR,
                        AdErrorStage.CONFIG, failure, body);
                } catch (RuntimeException failure) {
                    error = AdErrors.from(AdErrorCode.CONFIG_PARSE_ERROR,
                        AdErrorStage.CONFIG, AdResponseException.parsing(failure, body), body);
                }
                if (!call.isCanceled()) callback.onResult(decision, error);
            }
        });
        return call::cancel;
    }

    private static JsonObject responseObject(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) throw new IllegalArgumentException("CMP response must be an object");
        return root.getAsJsonObject();
    }

    private static Decision parseDecision(JsonObject root) {
        JsonElement data = root.has("code") ? root.get("data") : root;
        if (data == null || !data.isJsonObject()) {
            throw new IllegalArgumentException("CMP decision data must be an object");
        }
        JsonObject object = data.getAsJsonObject();
        JsonElement actionValue = object.get("consent_action");
        String action = actionValue == null || actionValue.isJsonNull() ? null : actionValue.getAsString();
        if (!isSupportedAction(action)) {
            throw new IllegalArgumentException("CMP decision action is missing or unsupported");
        }
        Payload payload = null;
        if ("SAVE_SETTINGS".equals(action)) {
            JsonElement payloadValue = object.get("consent_payload");
            if (payloadValue == null || !payloadValue.isJsonObject()) {
                throw new IllegalArgumentException("CMP SAVE_SETTINGS requires consent_payload");
            }
            JsonObject values = payloadValue.getAsJsonObject();
            payload = new Payload(readIds(values, "purpose_consent_ids"),
                readIds(values, "purpose_li_ids"), readIds(values, "custom_purpose_consent_ids"),
                readIds(values, "custom_purpose_li_ids"), readIds(values, "special_feature_ids"),
                readIds(values, "vendor_consent_ids"), readIds(values, "vendor_li_ids"));
        }
        return new Decision(action, payload);
    }

    private static boolean isSupportedAction(String action) {
        return "ACCEPT_ALL".equals(action) || "REJECT".equals(action)
            || "SAVE_SETTINGS".equals(action) || "MAYBE_LATER".equals(action)
            || "SKIP_ALREADY_DECIDED".equals(action);
    }

    private static List<Integer> readIds(JsonObject payload, String name) {
        JsonElement values = payload.get(name);
        if (values == null || values.isJsonNull()) return Collections.emptyList();
        List<Integer> ids = new ArrayList<>();
        for (JsonElement value : values.getAsJsonArray()) {
            ids.add(value.getAsBigDecimal().intValueExact());
        }
        return Collections.unmodifiableList(ids);
    }

    interface DecisionCallback {
        void onResult(Decision decision, AdError error);
    }

    interface ReportCallback {
        void onResult(AdError error);
    }

    static final class Decision {
        final String action;
        final Payload payload;

        Decision(String action, Payload payload) {
            this.action = action;
            this.payload = payload;
        }
    }

    static final class Payload {
        final List<Integer> purposeConsentIds;
        final List<Integer> purposeLiIds;
        final List<Integer> customPurposeConsentIds;
        final List<Integer> customPurposeLiIds;
        final List<Integer> specialFeatureIds;
        final List<Integer> vendorConsentIds;
        final List<Integer> vendorLiIds;

        Payload(List<Integer> purposeConsentIds, List<Integer> purposeLiIds,
            List<Integer> customPurposeConsentIds, List<Integer> customPurposeLiIds,
            List<Integer> specialFeatureIds, List<Integer> vendorConsentIds, List<Integer> vendorLiIds) {
            this.purposeConsentIds = purposeConsentIds;
            this.purposeLiIds = purposeLiIds;
            this.customPurposeConsentIds = customPurposeConsentIds;
            this.customPurposeLiIds = customPurposeLiIds;
            this.specialFeatureIds = specialFeatureIds;
            this.vendorConsentIds = vendorConsentIds;
            this.vendorLiIds = vendorLiIds;
        }
    }
}
