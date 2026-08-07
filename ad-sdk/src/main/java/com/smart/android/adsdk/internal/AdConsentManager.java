package com.smart.android.adsdk.internal;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.FormError;
import com.google.android.ump.UserMessagingPlatform;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class AdConsentManager {
    enum ConsentAction {
        CHECK_ONLY,
        ACCEPT_ALL,
        REJECT,
        DEFER_WHEN_REQUIRED
    }

    static final class Result {
        final ConsentAction action;
        final boolean canRequestAds;
        final String errorMessage;
        final boolean deferred;
        final int consentStatus;
        final boolean formAvailable;
        final String privacyOptionsStatus;
        final String storedConsentSnapshot;
        final StoredConsentSnapshot storedConsentSnapshotData;

        Result(
            ConsentAction action,
            boolean canRequestAds,
            String errorMessage,
            boolean deferred,
            int consentStatus,
            boolean formAvailable,
            String privacyOptionsStatus,
            String storedConsentSnapshot,
            StoredConsentSnapshot storedConsentSnapshotData
        ) {
            this.action = action;
            this.canRequestAds = canRequestAds;
            this.errorMessage = errorMessage;
            this.deferred = deferred;
            this.consentStatus = consentStatus;
            this.formAvailable = formAvailable;
            this.privacyOptionsStatus = privacyOptionsStatus;
            this.storedConsentSnapshot = storedConsentSnapshot;
            this.storedConsentSnapshotData = storedConsentSnapshotData;
        }
    }

    static final class StoredConsentSnapshot {
        final String iabtcfGdprApplies;
        final int tcStringLength;
        final int purposeConsentsLength;
        final int vendorConsentsLength;
        final String consentModeValues;

        StoredConsentSnapshot() {
            this("", 0, 0, 0, "");
        }

        StoredConsentSnapshot(
            String iabtcfGdprApplies,
            int tcStringLength,
            int purposeConsentsLength,
            int vendorConsentsLength,
            String consentModeValues
        ) {
            this.iabtcfGdprApplies = iabtcfGdprApplies == null ? "" : iabtcfGdprApplies;
            this.tcStringLength = tcStringLength;
            this.purposeConsentsLength = purposeConsentsLength;
            this.vendorConsentsLength = vendorConsentsLength;
            this.consentModeValues = consentModeValues == null ? "" : consentModeValues;
        }

        String toLogString() {
            return "IABTCF_TCString_length=" + tcStringLength
                + ",IABTCF_gdprApplies=" + valueOrDefault(iabtcfGdprApplies, "unknown")
                + ",PurposeConsents_length=" + purposeConsentsLength
                + ",VendorConsents_length=" + vendorConsentsLength
                + ",UMP_consentModeValues=" + valueOrDefault(consentModeValues, "empty");
        }
    }

    interface Callback {
        void onResult(Result result);
    }

    private enum State {
        IDLE,
        STARTING_ACTIVITY,
        GATHERING_CONSENT,
        COMPLETE
    }

    private static final String TAG = "AdConsent";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final List<Callback> PENDING_CALLBACKS = new ArrayList<>();

    private static State state = State.IDLE;
    private static ConsentInformation consentInformation;
    private static WeakReference<Activity> hostActivityRef;
    private static boolean finishingActivity;
    private static ConsentAction activeAction = ConsentAction.ACCEPT_ALL;

    private AdConsentManager() {
    }

    static void requestConsent(Context context, ConsentAction action, Callback callback) {
        MAIN_HANDLER.post(() -> {
            Context appContext = applicationContext(context);
            ConsentInformation information = consentInformation;
            if (information == null) {
                information = UserMessagingPlatform.getConsentInformation(appContext);
                consentInformation = information;
            }

            if (state == State.COMPLETE && information.canRequestAds()) {
                callback.onResult(buildResult(appContext, action, null, false));
                return;
            }

            PENDING_CALLBACKS.add(callback);
            if (state != State.IDLE) {
                if (activeAction != action) {
                    Log.w(TAG, "已有 UMP action=" + activeAction + " 正在执行，本次 action=" + action + " 将复用当前流程");
                }
                return;
            }

            activeAction = action;
            state = State.STARTING_ACTIVITY;
            try {
                Intent intent = new Intent(appContext, AdConsentActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                appContext.startActivity(intent);
            } catch (RuntimeException error) {
                Log.e(TAG, "无法启动 UMP consent Activity", error);
                finishFlow(action, messageOrDefault(error, "Unable to start UMP consent Activity"), true, false);
            }
        });
    }

    static void runConsentFlow(Activity activity) {
        MAIN_HANDLER.post(() -> {
            if (state == State.GATHERING_CONSENT) {
                return;
            }

            hostActivityRef = new WeakReference<>(activity);
            finishingActivity = false;
            state = State.GATHERING_CONSENT;

            ConsentInformation information = consentInformation;
            if (information == null) {
                information = UserMessagingPlatform.getConsentInformation(activity.getApplicationContext());
                consentInformation = information;
            }
            ConsentRequestParameters requestParameters = new ConsentRequestParameters.Builder().build();
            ConsentAction action = activeAction;

            Log.i(TAG, "开始更新 UMP consent 信息，action=" + action);
            ConsentInformation finalInformation = information;
            information.requestConsentInfoUpdate(
                activity,
                requestParameters,
                () -> {
                    Log.i(
                        TAG,
                        "UMP consent 信息更新完成，status=" + finalInformation.getConsentStatus()
                            + "，canRequestAds=" + finalInformation.canRequestAds()
                            + "，formAvailable=" + finalInformation.isConsentFormAvailable()
                            + "，privacyOptions=" + finalInformation.getPrivacyOptionsRequirementStatus()
                    );
                    if (finalInformation.canRequestAds()) {
                        finishFlow(action, null, false, false);
                    } else {
                        switch (action) {
                            case CHECK_ONLY:
                                finishFlow(action, "UMP consent is required before ad request", true, false);
                                break;
                            case DEFER_WHEN_REQUIRED:
                                finishFlow(action, "UMP consent deferred by remote decision", true, true);
                                break;
                            case ACCEPT_ALL:
                            case REJECT:
                                loadAndShowSilentConsentForm(activity, action);
                                break;
                        }
                    }
                },
                requestError -> {
                    Log.w(
                        TAG,
                        "UMP consent 信息更新失败，canRequestAds=" + finalInformation.canRequestAds()
                            + "，error=" + requestError.getMessage()
                    );
                    finishFlow(action, requestError.getMessage(), !finalInformation.canRequestAds(), false);
                }
            );
        });
    }

    private static void loadAndShowSilentConsentForm(Activity activity, ConsentAction action) {
        ConsentInformation information = consentInformation;
        if (information == null) {
            information = UserMessagingPlatform.getConsentInformation(activity.getApplicationContext());
            consentInformation = information;
        }
        if (!information.isConsentFormAvailable()) {
            String message = "UMP consent form is unavailable after consent info update";
            Log.w(TAG, message + "，" + buildStoredConsentSnapshot(activity.getApplicationContext()));
            finishFlow(action, message, !information.canRequestAds(), false);
            return;
        }

        Log.i(TAG, "开始加载 UMP consent 表单用于静默完成用户操作，action=" + action);
        ConsentInformation finalInformation = information;
        UserMessagingPlatform.loadConsentForm(
            activity.getApplicationContext(),
            consentForm -> MAIN_HANDLER.post(() -> {
                Activity hostActivity = hostActivityRef == null ? null : hostActivityRef.get();
                if (state != State.GATHERING_CONSENT || hostActivity != activity) {
                    Log.w(TAG, "UMP consent 表单已加载，但宿主 Activity 已失效");
                    return;
                }
                Log.i(TAG, "UMP consent 表单加载完成，开始静默执行 action=" + action);
                SilentConsentFormRunner.showAndApplyDecisionSilently(
                    activity,
                    consentForm,
                    decisionModeFor(action),
                    result -> MAIN_HANDLER.post(() -> completeAfterConsentForm(
                        action,
                        result.formError,
                        result.localErrorMessage
                    ))
                );
            }),
            loadError -> MAIN_HANDLER.post(() -> {
                Log.w(
                    TAG,
                    "UMP consent 表单加载失败，canRequestAds=" + finalInformation.canRequestAds()
                        + "，error=" + loadError.getMessage()
                );
                finishFlow(action, loadError.getMessage(), !finalInformation.canRequestAds(), false);
            })
        );
    }

    private static SilentConsentFormRunner.DecisionMode decisionModeFor(ConsentAction action) {
        return action == ConsentAction.REJECT
            ? SilentConsentFormRunner.DecisionMode.REJECT
            : SilentConsentFormRunner.DecisionMode.ACCEPT_ALL;
    }

    static void onHostActivityDestroyed(Activity activity) {
        MAIN_HANDLER.post(() -> {
            Activity hostActivity = hostActivityRef == null ? null : hostActivityRef.get();
            if (hostActivity != activity) {
                return;
            }
            hostActivityRef.clear();
            hostActivityRef = null;
            if (!finishingActivity && state == State.GATHERING_CONSENT) {
                finishFlow(activeAction, "UMP consent Activity was destroyed before completion", true, false);
            }
        });
    }

    private static void completeAfterConsentForm(
        ConsentAction action,
        FormError formError,
        String localErrorMessage
    ) {
        ConsentInformation information = consentInformation;
        boolean canRequestAds = information != null && information.canRequestAds();
        Activity hostActivity = hostActivityRef == null ? null : hostActivityRef.get();
        String snapshot = hostActivity == null
            ? ""
            : buildStoredConsentSnapshot(hostActivity.getApplicationContext());
        String errorMessage = formError == null ? localErrorMessage : formError.getMessage();
        if (errorMessage == null) {
            Log.i(TAG, "UMP consent 静默流程结束，action=" + action + "，canRequestAds=" + canRequestAds + "，" + snapshot);
        } else {
            Log.w(
                TAG,
                "UMP consent 静默流程结束但返回错误，action=" + action
                    + "，canRequestAds=" + canRequestAds
                    + "，error=" + errorMessage
                    + "，" + snapshot
            );
        }
        finishFlow(action, errorMessage, errorMessage != null && !canRequestAds, false);
    }

    static String getConsentString(Context context) {
        Context appContext = applicationContext(context);
        SharedPreferences prefs = appContext.getSharedPreferences(
            appContext.getPackageName() + "_preferences",
            Context.MODE_PRIVATE
        );
        return prefs.getString("IABTCF_TCString", null);
    }

    static String buildStoredConsentSnapshotForLog(Context context) {
        return buildStoredConsentSnapshotData(applicationContext(context)).toLogString();
    }

    private static String buildStoredConsentSnapshot(Context context) {
        return buildStoredConsentSnapshotData(context).toLogString();
    }

    static StoredConsentSnapshot buildStoredConsentSnapshotData(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
            context.getPackageName() + "_preferences",
            Context.MODE_PRIVATE
        );
        String tcString = prefs.getString("IABTCF_TCString", null);
        Map<String, ?> allValues = prefs.getAll();
        Object gdprApplies = allValues.get("IABTCF_gdprApplies");
        String purposeConsents = prefs.getString("IABTCF_PurposeConsents", null);
        String vendorConsents = prefs.getString("IABTCF_VendorConsents", null);
        String consentModeValues = prefs.getString("UMP_consentModeValues", null);
        return new StoredConsentSnapshot(
            gdprApplies == null ? "" : gdprApplies.toString(),
            tcString == null ? 0 : tcString.length(),
            purposeConsents == null ? 0 : purposeConsents.length(),
            vendorConsents == null ? 0 : vendorConsents.length(),
            consentModeValues == null ? "" : consentModeValues
        );
    }

    private static Result buildResult(
        Context context,
        ConsentAction action,
        String errorMessage,
        boolean deferred
    ) {
        ConsentInformation information = consentInformation;
        StoredConsentSnapshot snapshotData = context == null
            ? new StoredConsentSnapshot()
            : buildStoredConsentSnapshotData(context);
        String privacyOptionsStatus = information == null
            ? ""
            : information.getPrivacyOptionsRequirementStatus().name();
        return new Result(
            action,
            information != null && information.canRequestAds(),
            errorMessage,
            deferred,
            information == null ? -1 : information.getConsentStatus(),
            information != null && information.isConsentFormAvailable(),
            privacyOptionsStatus,
            snapshotData.toLogString(),
            snapshotData
        );
    }

    private static void finishFlow(
        ConsentAction action,
        String errorMessage,
        boolean allowRetry,
        boolean deferred
    ) {
        Result result = buildResult(
            hostActivityRef == null || hostActivityRef.get() == null
                ? null
                : hostActivityRef.get().getApplicationContext(),
            action,
            errorMessage,
            deferred
        );
        state = allowRetry && !result.canRequestAds ? State.IDLE : State.COMPLETE;

        List<Callback> callbacks = new ArrayList<>(PENDING_CALLBACKS);
        PENDING_CALLBACKS.clear();
        for (Callback callback : callbacks) {
            callback.onResult(result);
        }

        finishingActivity = true;
        Activity hostActivity = hostActivityRef == null ? null : hostActivityRef.get();
        if (hostActivity != null && !hostActivity.isFinishing()) {
            hostActivity.finish();
        }
        if (hostActivityRef != null) {
            hostActivityRef.clear();
        }
        hostActivityRef = null;
    }

    private static Context applicationContext(Context context) {
        Context appContext = context.getApplicationContext();
        return appContext == null ? context : appContext;
    }

    private static String messageOrDefault(Throwable error, String defaultValue) {
        return error.getMessage() == null ? defaultValue : error.getMessage();
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
