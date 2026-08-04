package com.smart.android.ad_app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.smart.android.hq008flow.Hq008AdCallback;
import com.smart.android.hq008flow.Hq008AdSession;
import com.smart.android.hq008flow.Hq008FlowSdk;
import com.tcl.ff.component.overseabase.base.constant.AdType;
import com.tcl.ff.component.overseabasebusiness.requestparams.RequestParams;
import com.tcl.ff.component.vastad.Ad;
import com.tcl.ff.component.vastad.Controller;
import com.tcl.ff.component.vastad.Initialization;
import com.tcl.ff.component.vastad.core.callbacks.AdStatusListener;

import java.util.Locale;

/**
 * hq008 Debug 专用联合验证页：UI 直接接 TCL 视频广告 SDK，控制链路接 Hq008FlowSdk。
 */
public final class Hq008FlowSdkTestActivity extends Activity {
    private static final String TAG = "Hq008FlowTest";
    private static final long TCL_INIT_POLL_MS = 500L;
    private static final int TCL_INIT_MAX_POLLS = 40;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private FrameLayout adContainer;
    private TextView statusView;
    private Hq008AdSession currentSession;
    private Controller currentController;

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

        Hq008FlowSdk.init(getApplicationContext(), BuildConfig.CHANNEL);
        appendStatus("Hq008FlowSdk 已初始化并启动，channel=" + BuildConfig.CHANNEL);
        waitForTclSdkReady(0);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Hq008FlowSdk.setAdCallback(adCallback);
        appendStatus("广告回调已设置");
    }

    @Override
    protected void onStop() {
        Hq008FlowSdk.clearAdCallback();
        finishSessionAsError("UI_STOPPED");
        releaseTclAd();
        appendStatus("广告回调已清除");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(14), dp(20), dp(14));
        root.setBackgroundColor(Color.rgb(20, 27, 40));

        TextView title = new TextView(this);
        title.setText("HQ008 Flow SDK + TCL 2.8.02 联合测试");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22f);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        adContainer = new FrameLayout(this);
        adContainer.setBackgroundColor(Color.BLACK);
        root.addView(adContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(185, 225, 255));
        statusView.setTextSize(14f);
        statusView.setPadding(0, dp(10), 0, 0);
        root.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(150)
        ));

        setContentView(root);
    }

    private void waitForTclSdkReady(int pollCount) {
        if (Initialization.isHasInit()) {
            appendStatus("TCL 广告 SDK 已初始化");
            return;
        }
        if (pollCount >= TCL_INIT_MAX_POLLS) {
            appendStatus("TCL 广告 SDK 初始化等待超时，请检查 tcl_app_key/partner_name/project_id");
            return;
        }
        if (pollCount == 0) {
            appendStatus("等待 Application 中的 TCL 广告 SDK 初始化完成...");
        }
        mainHandler.postDelayed(
                () -> waitForTclSdkReady(pollCount + 1),
                TCL_INIT_POLL_MS
        );
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
            appendStatus("TCL 广告请求异常：" + safeMessage(error));
            releaseTclAd();
        }
    }

    private RequestParams buildRequestParams() {
        return new RequestParams.Builder()
                .setAppCat("app")
                .setAppDomain(getPackageName())
                .setChannelName(BuildConfig.CHANNEL)
                .setContentLanguage(Locale.getDefault().getLanguage())
                .setContentTitle("HQ008 Flow SDK Test")
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

    private void appendStatus(String message) {
        Log.i(TAG, message);
        String previous = statusView.getText().toString();
        String next = previous.isEmpty() ? message : previous + "\n" + message;
        if (next.length() > 2_000) {
            next = next.substring(next.length() - 2_000);
        }
        statusView.setText(next);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }
}
