package com.smart.android.hq008flowdemo;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.smart.android.hq008flow.Hq008AdCallback;
import com.smart.android.hq008flow.Hq008AdSession;
import com.smart.android.hq008flow.Hq008FlowConfig;
import com.smart.android.hq008flow.Hq008FlowSdk;
import com.tcl.ff.component.overseabase.base.constant.AdReportSwitchConfig;
import com.tcl.ff.component.overseabase.base.constant.AdType;
import com.tcl.ff.component.overseabasebusiness.requestparams.RequestParams;
import com.tcl.ff.component.vastad.Ad;
import com.tcl.ff.component.vastad.Controller;
import com.tcl.ff.component.vastad.Initialization;
import com.tcl.ff.component.vastad.MediaAdInitListener;
import com.tcl.ff.component.vastad.core.callbacks.AdStatusListener;

import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String TAG = "Hq008FlowDemo";
    private static final String DEMO_CHANNEL_ID = "TCL_FFA_TEST";
    private static final long TCL_INIT_POLL_MS = 500L;
    private static final int TCL_INIT_MAX_POLLS = 40;
    private static final long DEFAULT_FALLBACK_INTERVAL_SECONDS = 600L;
    private static final long DEFAULT_CALLBACK_TIMEOUT_SECONDS = 180L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private FrameLayout adContainer;
    private TextView statusView;
    private Hq008AdSession currentSession;
    private Controller currentController;
    private boolean destroyed;

    private final Hq008AdCallback adCallback = new Hq008AdCallback() {
        @Override
        public void onAdAuthorized(Hq008AdSession session) {
            currentSession = session;
            appendStatus("flow-control 与 authorize 已通过，开始请求 TCL 广告");
            requestTclAd();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        initializeFlowSdk();
        initializeTclSdk();
        waitForTclSdkReady(0);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Hq008FlowSdk.setAdCallback(adCallback);
        Hq008FlowSdk.start();
        appendStatus("广告回调已设置");
    }

    @Override
    protected void onStop() {
        finishSessionAsError("UI_STOPPED");
        Hq008FlowSdk.stop();
        Hq008FlowSdk.clearAdCallback();
        releaseTclAd();
        appendStatus("广告回调已清除");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(14), dp(20), dp(14));
        root.setBackgroundColor(Color.rgb(20, 27, 40));

        TextView title = new TextView(this);
        title.setText("HQ008 Flow SDK + TCL 2.8.02 Standalone Demo");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22f);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView subtitle = new TextView(this);
        subtitle.setText("channel=" + DEMO_CHANNEL_ID
                + "  baseUrl=" + BuildConfig.FLOW_API_BASE_URL
                + "  package=" + getPackageName());
        subtitle.setTextColor(Color.rgb(173, 202, 230));
        subtitle.setTextSize(13f);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        subtitleParams.topMargin = dp(6);
        root.addView(subtitle, subtitleParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.START);

        actions.addView(makeButton("重启流程", view -> restartFlow()));
        actions.addView(makeButton("清空日志", view -> statusView.setText("")));

        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        actionsParams.topMargin = dp(14);
        root.addView(actions, actionsParams);

        adContainer = new FrameLayout(this);
        adContainer.setBackgroundColor(Color.BLACK);
        adContainer.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        containerParams.topMargin = dp(14);
        root.addView(adContainer, containerParams);

        ScrollView statusScroll = new ScrollView(this);
        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(185, 225, 255));
        statusView.setTextSize(13f);
        statusView.setPadding(0, dp(10), 0, 0);
        statusScroll.addView(statusView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        root.addView(statusScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(180)
        ));

        setContentView(root);
    }

    private void initializeFlowSdk() {
        Hq008FlowConfig config = Hq008FlowConfig.builder(DEMO_CHANNEL_ID)
                .appName(BuildConfig.FLOW_APP_NAME)
                .adSdkVersion(BuildConfig.TCL_AD_SDK_VERSION)
                .apiBaseUrl(BuildConfig.FLOW_API_BASE_URL)
                .initialDelaySeconds(0L)
                .fallbackIntervalSeconds(DEFAULT_FALLBACK_INTERVAL_SECONDS)
                .adCallbackTimeoutSeconds(DEFAULT_CALLBACK_TIMEOUT_SECONDS)
                .build();
        Hq008FlowSdk.initialize(getApplicationContext(), config);
        appendStatus("Hq008FlowSdk 已初始化，channel=" + config.getChannelId()
                + "，baseUrl=" + config.getApiBaseUrl());
    }

    private void initializeTclSdk() {
        try {
            Ad.get().setEnableLog(true);
            if (!Initialization.isHasInit()) {
                AdReportSwitchConfig switchConfig = new AdReportSwitchConfig();
                switchConfig.setPrivacyAgreed(true);
                switchConfig.setUxpEnabled(true);
                switchConfig.setErrorStatisticsEnabled(true);
                Initialization.init(getApplicationContext(), switchConfig, new MediaAdInitListener() {
                    @Override
                    public void onInitComplete() {
                        appendStatus("TCL SDK 初始化回调完成");
                    }
                });
                appendStatus("已发起 TCL Initialization.init()");
            } else {
                appendStatus("TCL SDK 已由当前进程初始化");
            }
        } catch (Throwable error) {
            appendStatus("TCL SDK 初始化异常: " + safeMessage(error));
            Log.e(TAG, "initializeTclSdk", error);
        }
    }

    private void waitForTclSdkReady(int pollCount) {
        if (destroyed) {
            return;
        }
        if (Initialization.isHasInit()) {
            appendStatus("TCL SDK ready=true");
            return;
        }
        if (pollCount >= TCL_INIT_MAX_POLLS) {
            appendStatus("TCL SDK 初始化等待超时，请检查 tcl_app_key/partner_name/project_id");
            return;
        }
        if (pollCount == 0) {
            appendStatus("等待 TCL SDK 初始化完成...");
        }
        mainHandler.postDelayed(() -> waitForTclSdkReady(pollCount + 1), TCL_INIT_POLL_MS);
    }

    private void restartFlow() {
        initializeFlowSdk();
        Hq008FlowSdk.setAdCallback(adCallback);
        Hq008FlowSdk.start();
        appendStatus("广告回调已重新设置");
    }

    private void requestTclAd() {
        if (!Initialization.isHasInit()) {
            finishSessionAsError("TCL_SDK_NOT_INITIALIZED");
            appendStatus("请求终止：TCL 广告 SDK 未初始化");
            return;
        }

        releaseTclAd();
        try {
            Ad.get()
                    .begin(getApplicationContext())
                    .lazyLoad()
                    .setAdType(AdType.WATERFALL)
                    .setVolume(1f)
                    .setRequestParams(buildRequestParams())
                    .listen(new AdStatusListener() {
                        @Override
                        public void onAdLoaded(Controller controller) {
                            runOnUiThread(() -> {
                                if (currentSession == null) {
                                    controller.release();
                                    return;
                                }
                                currentController = controller;
                                currentSession.loaded();
                                appendStatus("TCL onAdLoaded -> session.loaded()");
                                try {
                                    controller.start(adContainer);
                                } catch (Throwable error) {
                                    finishSessionAsError("CONTROLLER_START_ERROR: " + safeMessage(error));
                                    releaseTclAd();
                                }
                            });
                        }

                        @Override
                        public void onAdStartPlay() {
                            runOnUiThread(() -> {
                                if (currentSession != null) {
                                    currentSession.started();
                                    appendStatus("TCL onAdStartPlay -> session.started()");
                                }
                            });
                        }

                        @Override
                        public void onAdStartPlay(double progress) {
                            runOnUiThread(() -> {
                                if (currentSession != null) {
                                    currentSession.started(progress);
                                    appendStatus("TCL onAdStartPlay(" + progress + ") -> session.started(progress)");
                                }
                            });
                        }

                        @Override
                        public void onAdFinished() {
                            runOnUiThread(() -> {
                                if (currentSession != null) {
                                    currentSession.completed();
                                    currentSession = null;
                                }
                                appendStatus("TCL onAdFinished -> session.completed()");
                                releaseTclAd();
                            });
                        }

                        @Override
                        public void onAdError(int errorCode) {
                            runOnUiThread(() -> {
                                if (currentSession != null) {
                                    currentSession.failed(errorCode, "TCL_AD_ERROR_" + errorCode);
                                    currentSession = null;
                                }
                                appendStatus("TCL onAdError(" + errorCode + ") -> session.failed()");
                                releaseTclAd();
                            });
                        }

                        @Override
                        public void onContainerSizeError() {
                            runOnUiThread(() -> {
                                finishSessionAsError("TCL_CONTAINER_SIZE_ERROR");
                                appendStatus("TCL onContainerSizeError -> session.failed()");
                                releaseTclAd();
                            });
                        }
                    })
                    .start();
            appendStatus("已调用 TCL Ad.start()，等待广告回调");
        } catch (Throwable error) {
            finishSessionAsError("TCL_REQUEST_ERROR: " + safeMessage(error));
            appendStatus("TCL 广告请求异常: " + safeMessage(error));
            releaseTclAd();
        }
    }

    private RequestParams buildRequestParams() {
        return new RequestParams.Builder()
                .setAppCat("app")
                .setAppDomain(getPackageName())
                .setChannelName(DEMO_CHANNEL_ID)
                .setContentLanguage(Locale.getDefault().getLanguage())
                .setContentTitle("HQ008 Flow SDK Standalone Demo")
                .setDevice("android")
                .setDeviceLanguage(Locale.getDefault().toLanguageTag())
                .setDeviceMake(Build.MANUFACTURER == null ? "" : Build.MANUFACTURER)
                .setDeviceModel(Build.MODEL == null ? "" : Build.MODEL)
                .build();
    }

    private void finishSessionAsError(String message) {
        if (currentSession != null) {
            currentSession.failed(message);
            currentSession = null;
        }
    }

    private void releaseTclAd() {
        Controller controller = currentController;
        currentController = null;
        if (controller == null) {
            adContainer.removeAllViews();
            return;
        }
        try {
            controller.stop(adContainer);
        } catch (Throwable error) {
            Log.w(TAG, "controller.stop failed", error);
        }
        try {
            controller.release();
        } catch (Throwable error) {
            Log.w(TAG, "controller.release failed", error);
        }
        adContainer.removeAllViews();
    }

    private Button makeButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.rightMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private void appendStatus(String message) {
        Log.i(TAG, message);
        String previous = statusView.getText() == null ? "" : statusView.getText().toString();
        String next = previous.isEmpty() ? message : previous + "\n" + message;
        if (next.length() > 4_000) {
            next = next.substring(next.length() - 4_000);
        }
        statusView.setText(next);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }
}
