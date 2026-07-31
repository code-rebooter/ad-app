package com.smart.android.adsdk.internal;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import com.google.android.ump.ConsentForm;
import com.google.android.ump.FormError;
import com.google.android.ump.UserMessagingPlatform;
import com.smart.android.adsdk.R;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class SilentConsentFormRunner {
    private static final String TAG = "AdConsentRunner";
    private static final long AUTO_CLICK_INITIAL_DELAY_MS = 250L;
    private static final long AUTO_CLICK_INTERVAL_MS = 350L;
    private static final long AUTO_CLICK_TIMEOUT_MS = 15_000L;
    private static final long PRIVACY_OPTIONS_WEBVIEW_SCAN_INTERVAL_MS = 120L;
    private static final long PRIVACY_OPTIONS_RETRY_DELAY_MS = 750L;
    private static final int PRIVACY_OPTIONS_MAX_RETRY_COUNT = 5;
    private static final int STRONG_CLICK_SCORE = 100;
    private static final int MAX_LOG_VALUE_LENGTH = 2_000;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static String autoDecisionScript;

    enum DecisionMode {
        ACCEPT_ALL,
        REJECT
    }

    static final class Result {
        final FormError formError;
        final String localErrorMessage;

        Result(FormError formError, String localErrorMessage) {
            this.formError = formError;
            this.localErrorMessage = localErrorMessage;
        }
    }

    interface ResultCallback {
        void onComplete(Result result);
    }

    private interface ClickScoreCallback {
        void onResult(Integer clickScore);
    }

    private SilentConsentFormRunner() {
    }

    @SuppressWarnings("deprecation")
    static void prepareHostActivity(Activity activity) {
        activity.overridePendingTransition(0, 0);
        suppressWindow(activity.getWindow());
    }

    static void showAndAcceptAllSilently(
        Activity activity,
        ConsentForm consentForm,
        ResultCallback onComplete
    ) {
        showAndApplyDecisionSilently(activity, consentForm, DecisionMode.ACCEPT_ALL, onComplete);
    }

    static void showAndApplyDecisionSilently(
        Activity activity,
        ConsentForm consentForm,
        DecisionMode decisionMode,
        ResultCallback onComplete
    ) {
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicBoolean strongClickIssued = new AtomicBoolean(false);
        WebView webView = findConsentWebView(consentForm);
        if (webView == null) {
            Log.w(TAG, "无法找到 UMP 内部 WebView，无法静默执行表单操作");
            onComplete.onComplete(new Result(null, "Unable to find UMP consent WebView"));
            return;
        }

        prepareWebView(webView);
        AtomicReference<Runnable> autoClickRunnable = new AtomicReference<>();
        Runnable timeoutRunnable = () -> {
            if (completed.compareAndSet(false, true)) {
                Log.w(TAG, "UMP 静默表单自动点击超时");
                removeCallback(autoClickRunnable.get());
                dismissDialogIfPresent(consentForm);
                onComplete.onComplete(new Result(null, "UMP silent consent auto-click timed out"));
            }
        };

        ResultCallback completeOnce = result -> {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            removeCallback(autoClickRunnable.get());
            MAIN_HANDLER.removeCallbacks(timeoutRunnable);
            onComplete.onComplete(result);
        };

        try {
            prepareHostActivity(activity);
            consentForm.show(activity, formError -> {
                String message = formError == null ? "" : valueOrEmpty(formError.getMessage());
                Log.i(TAG, "UMP 静默表单 dismiss 回调，formError=" + message);
                completeOnce.onComplete(new Result(formError, null));
            });
            suppressConsentFormSurface(activity, consentForm, webView);
        } catch (RuntimeException error) {
            Log.e(TAG, "UMP 静默表单 show 失败", error);
            completeOnce.onComplete(new Result(null, messageOrClassName(error)));
            return;
        }

        Runnable autoClick = new Runnable() {
            private int attempt;

            @Override
            public void run() {
                if (completed.get()) {
                    return;
                }
                attempt += 1;
                injectAutoClickScript(webView, decisionMode, attempt, clickScore -> {
                    if (clickScore != null && clickScore >= STRONG_CLICK_SCORE) {
                        strongClickIssued.set(true);
                    }
                    if (!completed.get() && !strongClickIssued.get()) {
                        MAIN_HANDLER.postDelayed(this, AUTO_CLICK_INTERVAL_MS);
                    }
                });
            }
        };
        autoClickRunnable.set(autoClick);

        MAIN_HANDLER.postDelayed(timeoutRunnable, AUTO_CLICK_TIMEOUT_MS);
        MAIN_HANDLER.postDelayed(autoClick, AUTO_CLICK_INITIAL_DELAY_MS);
    }

    static void showPrivacyOptionsAndApplyDecisionSilently(
        Activity activity,
        DecisionMode decisionMode,
        ResultCallback onComplete
    ) {
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicBoolean strongClickIssued = new AtomicBoolean(false);
        AtomicBoolean autoClickStarted = new AtomicBoolean(false);
        int[] privacyOptionsRetryCount = new int[] { 0 };
        AtomicReference<Runnable> autoClickRunnable = new AtomicReference<>();
        AtomicReference<Runnable> scanRunnable = new AtomicReference<>();
        AtomicReference<WebView> activeWebView = new AtomicReference<>();

        Runnable timeoutRunnable = () -> {
            if (completed.compareAndSet(false, true)) {
                Log.w(TAG, "UMP privacy options 静默自动点击超时");
                removeCallback(autoClickRunnable.get());
                removeCallback(scanRunnable.get());
                onComplete.onComplete(new Result(null, "UMP silent privacy options auto-click timed out"));
            }
        };

        ResultCallback completeOnce = result -> {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            removeCallback(autoClickRunnable.get());
            removeCallback(scanRunnable.get());
            MAIN_HANDLER.removeCallbacks(timeoutRunnable);
            onComplete.onComplete(result);
        };

        Runnable scan = new Runnable() {
            @Override
            public void run() {
                if (completed.get()) {
                    return;
                }
                WebView webView = findPrivacyOptionsWebView(activity);
                if (webView != null) {
                    if (activeWebView.get() != webView) {
                        activeWebView.set(webView);
                        prepareWebView(webView);
                        Log.i(TAG, "已找到 UMP privacy options WebView，开始静默执行 decision=" + decisionMode);
                    }
                    suppressPrivacyOptionsSurface(activity, webView);
                    startPrivacyOptionsAutoClick(
                        webView,
                        decisionMode,
                        completed,
                        strongClickIssued,
                        autoClickStarted,
                        autoClickRunnable
                    );
                }
                MAIN_HANDLER.postDelayed(this, PRIVACY_OPTIONS_WEBVIEW_SCAN_INTERVAL_MS);
            }
        };
        scanRunnable.set(scan);

        final class PrivacyOptionsLauncher {
            void launch() {
                try {
                    prepareHostActivity(activity);
                    UserMessagingPlatform.showPrivacyOptionsForm(activity, formError -> {
                        String errorMessage = formError == null ? "" : valueOrEmpty(formError.getMessage());
                        if (!completed.get()
                            && containsIgnoreCase(errorMessage, "loading")
                            && privacyOptionsRetryCount[0] < PRIVACY_OPTIONS_MAX_RETRY_COUNT) {
                            privacyOptionsRetryCount[0] += 1;
                            Log.w(
                                TAG,
                                "UMP privacy options 仍在加载，准备重试 retry="
                                    + privacyOptionsRetryCount[0]
                                    + " error="
                                    + errorMessage
                            );
                            MAIN_HANDLER.postDelayed(() -> {
                                if (!completed.get()) {
                                    launch();
                                }
                            }, PRIVACY_OPTIONS_RETRY_DELAY_MS);
                            return;
                        }

                        String message = formError == null ? "" : valueOrEmpty(formError.getMessage());
                        Log.i(TAG, "UMP privacy options dismiss 回调，formError=" + message);
                        completeOnce.onComplete(new Result(formError, null));
                    });
                } catch (RuntimeException error) {
                    Log.e(TAG, "UMP privacy options show 失败", error);
                    completeOnce.onComplete(new Result(null, messageOrClassName(error)));
                }
            }
        }

        try {
            new PrivacyOptionsLauncher().launch();
        } catch (RuntimeException error) {
            Log.e(TAG, "UMP privacy options 调用失败", error);
            completeOnce.onComplete(new Result(null, messageOrClassName(error)));
        }

        MAIN_HANDLER.postDelayed(timeoutRunnable, AUTO_CLICK_TIMEOUT_MS);
        MAIN_HANDLER.post(scan);
    }

    private static void startPrivacyOptionsAutoClick(
        WebView webView,
        DecisionMode decisionMode,
        AtomicBoolean completed,
        AtomicBoolean strongClickIssued,
        AtomicBoolean autoClickStarted,
        AtomicReference<Runnable> autoClickRunnable
    ) {
        if (!autoClickStarted.compareAndSet(false, true)) {
            return;
        }
        Runnable autoClick = new Runnable() {
            private int attempt;

            @Override
            public void run() {
                if (completed.get()) {
                    return;
                }
                attempt += 1;
                injectAutoClickScript(webView, decisionMode, attempt, clickScore -> {
                    if (clickScore != null && clickScore >= STRONG_CLICK_SCORE) {
                        strongClickIssued.set(true);
                    }
                    if (!completed.get() && !strongClickIssued.get()) {
                        MAIN_HANDLER.postDelayed(this, AUTO_CLICK_INTERVAL_MS);
                    }
                });
            }
        };
        autoClickRunnable.set(autoClick);
        MAIN_HANDLER.postDelayed(autoClick, AUTO_CLICK_INITIAL_DELAY_MS);
    }

    private static void prepareWebView(WebView webView) {
        webView.setAlpha(0f);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setFocusable(false);
        webView.setFocusableInTouchMode(false);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                String message = consoleMessage == null ? null : consoleMessage.message();
                if (message != null && message.startsWith("AdConsentSilent:")) {
                    Log.d(TAG, limit(message, MAX_LOG_VALUE_LENGTH));
                    return true;
                }
                return super.onConsoleMessage(consoleMessage);
            }
        });
    }

    private static void suppressConsentFormSurface(
        Activity activity,
        ConsentForm consentForm,
        WebView webView
    ) {
        prepareHostActivity(activity);
        Dialog dialog = findDialog(consentForm);
        suppressWindow(dialog == null ? null : dialog.getWindow());
        webView.setAlpha(0f);
    }

    private static void suppressPrivacyOptionsSurface(Activity activity, WebView webView) {
        prepareHostActivity(activity);
        webView.setAlpha(0f);
        for (View root : findWindowRootViews()) {
            if (root == webView || containsDescendant(root, webView)) {
                try {
                    root.setAlpha(0f);
                    root.setBackgroundColor(Color.TRANSPARENT);
                } catch (RuntimeException error) {
                    Log.w(TAG, "隐藏 UMP privacy options 根视图失败：" + error.getMessage());
                }
            }
        }
    }

    private static void suppressWindow(Window window) {
        if (window == null) {
            return;
        }
        try {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            );
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.alpha = 0f;
            attributes.dimAmount = 0f;
            attributes.windowAnimations = 0;
            window.setAttributes(attributes);
            window.getDecorView().setAlpha(0f);
        } catch (RuntimeException error) {
            Log.w(TAG, "隐藏 UMP 宿主窗口失败：" + error.getMessage());
        }
    }

    private static void injectAutoClickScript(
        WebView webView,
        DecisionMode decisionMode,
        int attempt,
        ClickScoreCallback onResult
    ) {
        String template = loadAutoDecisionScript(webView);
        if (template.isEmpty()) {
            onResult.onResult(null);
            return;
        }
        String script = template.replace("%%DECISION_MODE%%", decisionMode.name());
        try {
            webView.evaluateJavascript(script, rawValue -> {
                String value = normalizeJsResult(rawValue);
                Log.d(TAG, "UMP 静默表单自动点击 mode=" + decisionMode + " attempt=" + attempt + " result=" + value);
                onResult.onResult(parseClickScore(value));
            });
        } catch (RuntimeException error) {
            Log.w(TAG, "UMP 静默表单 JS 注入失败：" + error.getMessage());
            onResult.onResult(null);
        }
    }

    private static Integer parseClickScore(String value) {
        if (value == null || !value.startsWith("clicked:")) {
            return null;
        }
        int start = "clicked:".length();
        int end = value.indexOf(':', start);
        String score = end == -1 ? value.substring(start) : value.substring(start, end);
        try {
            return Integer.parseInt(score);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static WebView findConsentWebView(ConsentForm consentForm) {
        Object fromMethod = invokeNoArgMethod(consentForm, "zzc");
        if (fromMethod instanceof WebView) {
            return (WebView) fromMethod;
        }
        return findFieldValue(consentForm, WebView.class);
    }

    private static WebView findPrivacyOptionsWebView(Activity activity) {
        List<WebView> webViews = new ArrayList<>();
        collectWebViews(activity.getWindow() == null ? null : activity.getWindow().getDecorView(), webViews);
        for (View root : findWindowRootViews()) {
            collectWebViews(root, webViews);
        }
        List<WebView> distinctWebViews = distinct(webViews);
        for (int index = distinctWebViews.size() - 1; index >= 0; index -= 1) {
            WebView webView = distinctWebViews.get(index);
            String urls = safeWebViewUrl(webView) + " " + safeOriginalWebViewUrl(webView);
            if (containsIgnoreCase(urls, "fundingchoices") || containsIgnoreCase(urls, "consent")) {
                return webView;
            }
        }
        return distinctWebViews.isEmpty() ? null : distinctWebViews.get(distinctWebViews.size() - 1);
    }

    private static void collectWebViews(View view, List<WebView> out) {
        if (view == null) {
            return;
        }
        if (view instanceof WebView) {
            out.add((WebView) view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            for (int index = 0; index < viewGroup.getChildCount(); index += 1) {
                collectWebViews(viewGroup.getChildAt(index), out);
            }
        }
    }

    private static boolean containsDescendant(View root, View target) {
        if (root == null) {
            return false;
        }
        if (root == target) {
            return true;
        }
        if (root instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) root;
            for (int index = 0; index < viewGroup.getChildCount(); index += 1) {
                if (containsDescendant(viewGroup.getChildAt(index), target)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<View> findWindowRootViews() {
        try {
            Class<?> type = Class.forName("android.view.WindowManagerGlobal");
            Method getInstance = type.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object instance = getInstance.invoke(null);
            Field viewsField = type.getDeclaredField("mViews");
            viewsField.setAccessible(true);
            Object views = viewsField.get(instance);
            List<View> result = new ArrayList<>();
            if (views instanceof List<?>) {
                for (Object view : (List<?>) views) {
                    if (view instanceof View) {
                        result.add((View) view);
                    }
                }
            } else if (views instanceof Object[]) {
                for (Object view : (Object[]) views) {
                    if (view instanceof View) {
                        result.add((View) view);
                    }
                }
            }
            return result;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.d(TAG, "读取 WindowManagerGlobal 根视图失败：" + error.getMessage());
            return new ArrayList<>();
        }
    }

    private static Dialog findDialog(ConsentForm consentForm) {
        return findFieldValue(consentForm, Dialog.class);
    }

    private static void dismissDialogIfPresent(ConsentForm consentForm) {
        try {
            Dialog dialog = findDialog(consentForm);
            if (dialog != null) {
                dialog.dismiss();
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "关闭 UMP 静默 Dialog 失败：" + error.getMessage());
        }
    }

    private static Object invokeNoArgMethod(Object target, String methodName) {
        Method method = findMethod(target.getClass(), methodName);
        if (method == null) {
            return null;
        }
        try {
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String methodName) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(methodName) && method.getParameterTypes().length == 0) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static <T> T findFieldValue(Object target, Class<T> expectedType) {
        Class<?> current = target.getClass();
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                Object value = readField(target, field);
                if (expectedType.isInstance(value)) {
                    return expectedType.cast(value);
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Object readField(Object target, Field field) {
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (IllegalAccessException | RuntimeException error) {
            return null;
        }
    }

    private static String loadAutoDecisionScript(WebView webView) {
        if (autoDecisionScript != null) {
            return autoDecisionScript;
        }
        try (InputStream input = webView.getResources().openRawResource(R.raw.ad_auto_decision_script);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            autoDecisionScript = output.toString(StandardCharsets.UTF_8.name());
            return autoDecisionScript;
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "读取 UMP 静默脚本失败：" + error.getMessage());
            autoDecisionScript = "";
            return autoDecisionScript;
        }
    }

    private static void removeCallback(Runnable runnable) {
        if (runnable != null) {
            MAIN_HANDLER.removeCallbacks(runnable);
        }
    }

    private static String normalizeJsResult(String rawValue) {
        String value = rawValue == null ? "" : trimQuotes(rawValue);
        value = value.replace("\\n", " ");
        value = value.replace("\\\"", "\"");
        return limit(value, MAX_LOG_VALUE_LENGTH);
    }

    private static String trimQuotes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '"') {
            start += 1;
        }
        while (end > start && value.charAt(end - 1) == '"') {
            end -= 1;
        }
        return value.substring(start, end);
    }

    private static String safeWebViewUrl(WebView webView) {
        try {
            return valueOrEmpty(webView.getUrl());
        } catch (RuntimeException error) {
            return "";
        }
    }

    private static String safeOriginalWebViewUrl(WebView webView) {
        try {
            return valueOrEmpty(webView.getOriginalUrl());
        } catch (RuntimeException error) {
            return "";
        }
    }

    private static <T> List<T> distinct(List<T> values) {
        Set<T> set = new LinkedHashSet<>(values);
        return new ArrayList<>(set);
    }

    private static boolean containsIgnoreCase(String value, String query) {
        return value != null && value.toLowerCase(Locale.US).contains(query.toLowerCase(Locale.US));
    }

    private static String messageOrClassName(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String limit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
