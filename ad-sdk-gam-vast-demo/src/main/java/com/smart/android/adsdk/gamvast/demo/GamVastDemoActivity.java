package com.smart.android.adsdk.gamvast.demo;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.smart.android.adsdk.AdError;
import com.smart.android.adsdk.AdListener;
import com.smart.android.adsdk.AdRequest;
import com.smart.android.adsdk.AdResult;
import com.smart.android.adsdk.AdSession;
import com.smart.android.adsdk.AdSdk;
import com.smart.android.adsdk.AdState;
import com.smart.android.adsdk.InitializationListener;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class GamVastDemoActivity extends Activity {
    private static final String TAG = "GamVastDemo";
    private static final long REQUEST_INTERVAL_MS = 60_000L;
    private static final int MAX_LOG_LINES = 160;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private FrameLayout adContainer;
    private TextView statusView;
    private TextView logView;
    private AdSession session;
    private Runnable nextRequestRunnable;
    private boolean initialized;
    private boolean requestInFlight;
    private boolean destroyed;
    private int requestSequence;
    private long requestStartedAtMs;
    private int logLineCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        log("onCreate");
        updateStatus("INITIALIZING");
        log("config: uid=android.uid.system, adAppId=ca-app-pub-3199037222330432~4539227372"
            + ", channel=AD_TV_HGS001, interval=" + (REQUEST_INTERVAL_MS / 1000L) + "s");
        AdSdk.initialize(getApplicationContext(), new InitializationListener() {
            @Override
            public void onInitialized() {
                initialized = true;
                log("SDK initialized");
                updateStatus("READY");
                requestAd("initial");
            }

            @Override
            public void onError(AdError error) {
                initialized = false;
                logError("SDK initialize failed", error);
                updateStatus("INIT_ERROR");
            }
        });
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        log("onDestroy: stop timer and release current session");
        cancelNextRequest();
        AdSession activeSession = session;
        session = null;
        requestInFlight = false;
        if (activeSession != null) {
            activeSession.release();
        }
        super.onDestroy();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        adContainer = new FrameLayout(this);
        adContainer.setBackgroundColor(Color.BLACK);
        root.addView(adContainer, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        FrameLayout logPanel = new FrameLayout(this);
        logPanel.setBackgroundColor(0xCC101010);
        FrameLayout.LayoutParams logPanelParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(285),
            Gravity.BOTTOM
        );

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(16f);
        statusView.setGravity(Gravity.CENTER_VERTICAL);
        statusView.setPadding(dp(12), dp(6), dp(12), dp(6));
        statusView.setBackgroundColor(0xFF252525);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(38),
            Gravity.TOP
        );
        logPanel.addView(statusView, statusParams);

        ScrollView logScrollView = new ScrollView(this);
        logScrollView.setFillViewport(true);
        logScrollView.setBackgroundColor(0xCC101010);
        logView = new TextView(this);
        logView.setTextColor(0xFFE0E0E0);
        logView.setTextSize(12f);
        logView.setGravity(Gravity.TOP | Gravity.START);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setPadding(dp(10), dp(8), dp(10), dp(10));
        logScrollView.addView(logView, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        FrameLayout.LayoutParams logScrollParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        );
        logScrollParams.topMargin = dp(38);
        logPanel.addView(logScrollView, logScrollParams);

        root.addView(logPanel, logPanelParams);
        setContentView(root);
    }

    private void requestAd(String trigger) {
        if (destroyed) {
            return;
        }
        if (!initialized) {
            log("request skipped: SDK is not initialized, trigger=" + trigger);
            updateStatus("NOT_READY");
            return;
        }
        if (requestInFlight) {
            AdState state = session == null ? null : session.getState();
            log("request skipped: previous request still active, state=" + state
                + ", trigger=" + trigger);
            updateStatus("BUSY " + state);
            return;
        }

        requestInFlight = true;
        requestSequence += 1;
        requestStartedAtMs = SystemClock.elapsedRealtime();
        String requestId = "gam-vast-demo-" + requestSequence + "-" + requestStartedAtMs;
        log("request#" + requestSequence + " START, trigger=" + trigger
            + ", requestId=" + requestId
            + ", childCountBefore=" + adContainer.getChildCount());
        updateStatus("REQUESTING #" + requestSequence);
        adContainer.removeAllViews();

        try {
            session = AdSdk.play(
                adContainer,
                new AdRequest.Builder()
                    .setSoundEnabled(true)
                    .setRequestId(requestId)
                    .build(),
                new AdListener() {
                    @Override
                    public void onLoaded(AdSession callbackSession) {
                        log("request#" + requestSequence + " onLoaded"
                            + ", state=" + callbackSession.getState()
                            + ", childCount=" + adContainer.getChildCount()
                            + ", elapsed=" + elapsedMs() + "ms");
                        updateStatus("LOADED #" + requestSequence);
                    }

                    @Override
                    public void onStarted(AdSession callbackSession) {
                        log("request#" + requestSequence + " onStarted"
                            + ", state=" + callbackSession.getState()
                            + ", childCount=" + adContainer.getChildCount()
                            + ", elapsed=" + elapsedMs() + "ms");
                        updateStatus("PLAYING #" + requestSequence);
                    }

                    @Override
                    public void onFinished(AdSession callbackSession, AdResult result) {
                        long elapsed = elapsedMs();
                        log("request#" + requestSequence + " onFinished"
                            + ", state=" + callbackSession.getState()
                            + ", status=" + result.getStatus()
                            + ", reason=" + valueOrEmpty(result.getReason())
                            + ", error=" + errorMessage(result.getError())
                            + ", elapsed=" + elapsed + "ms"
                            + ", childCount=" + adContainer.getChildCount());
                        if (result.getError() != null) {
                            logError("request#" + requestSequence + " finished with error", result.getError());
                        }
                        finishCurrentRequest();
                        updateStatus("FINISHED " + result.getStatus()
                            + " #" + requestSequence);
                    }
                }
            );
            log("request#" + requestSequence + " AdSdk.play returned"
                + ", state=" + (session == null ? null : session.getState()));
        } catch (RuntimeException error) {
            logException("request#" + requestSequence + " AdSdk.play threw", error);
            requestInFlight = false;
            session = null;
            scheduleNextRequest("play threw");
        }
    }

    private void finishCurrentRequest() {
        if (destroyed) {
            return;
        }
        requestInFlight = false;
        session = null;
        log("request#" + requestSequence + " RELEASED; next request will wait "
            + (REQUEST_INTERVAL_MS / 1000L) + "s");
        scheduleNextRequest("previous finished");
    }

    private void scheduleNextRequest(String reason) {
        if (destroyed || !initialized) {
            return;
        }
        cancelNextRequest();
        nextRequestRunnable = () -> {
            nextRequestRunnable = null;
            if (requestInFlight) {
                log("timer fired but request is still active; no overlap");
                updateStatus("BUSY - NO OVERLAP");
                return;
            }
            requestAd("timer");
        };
        mainHandler.postDelayed(nextRequestRunnable, REQUEST_INTERVAL_MS);
        log("next request scheduled in " + (REQUEST_INTERVAL_MS / 1000L)
            + "s, reason=" + reason);
    }

    private void cancelNextRequest() {
        if (nextRequestRunnable != null) {
            mainHandler.removeCallbacks(nextRequestRunnable);
            nextRequestRunnable = null;
        }
    }

    private long elapsedMs() {
        return Math.max(0L, SystemClock.elapsedRealtime() - requestStartedAtMs);
    }

    private void updateStatus(String status) {
        if (statusView == null) {
            return;
        }
        statusView.setText("GAM VAST Demo  |  " + status
            + "  |  inFlight=" + requestInFlight
            + "  |  count=" + requestSequence);
    }

    private void log(String message) {
        String line = "[" + timeFormat.format(new Date()) + "] " + message;
        Log.i(TAG, line);
        if (logView == null) {
            return;
        }
        if (logLineCount >= MAX_LOG_LINES) {
            CharSequence current = logView.getText();
            int newline = current.toString().indexOf('\n');
            logView.setText(newline >= 0 ? current.subSequence(newline + 1, current.length()) : "");
            logLineCount -= 1;
        }
        logView.append(line);
        logView.append("\n");
        logLineCount += 1;
        logView.post(() -> {
            ViewParentHelper.scrollToBottom(logView);
        });
    }

    private void logError(String message, AdError error) {
        log(message + ": code=" + (error == null ? null : error.getCode())
            + ", stage=" + (error == null ? null : error.getStage())
            + ", message=" + (error == null ? null : error.getMessage()));
    }

    private void logException(String message, Throwable error) {
        log(message + ": " + error.getClass().getSimpleName()
            + ": " + valueOrEmpty(error.getMessage()));
        Log.e(TAG, message, error);
    }

    private String errorMessage(AdError error) {
        if (error == null) {
            return "";
        }
        return error.getCode() + "/" + error.getStage() + "/" + valueOrEmpty(error.getMessage());
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class ViewParentHelper {
        private ViewParentHelper() {
        }

        static void scrollToBottom(TextView logView) {
            if (logView.getParent() instanceof android.widget.ScrollView) {
                android.widget.ScrollView scrollView =
                    (android.widget.ScrollView) logView.getParent();
                scrollView.fullScroll(View.FOCUS_DOWN);
            }
        }
    }
}
