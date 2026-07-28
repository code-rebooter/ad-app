package com.smart.android.ad_app

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import com.smart.android.ad_app.AdLocalLog as Log
import android.view.Window
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.google.android.ump.ConsentForm
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

internal object GoogleUmpSilentConsentFormRunner {
    private const val TAG = "GoogleUmpSilentRunner"
    private const val AUTO_CLICK_INITIAL_DELAY_MS = 250L
    private const val AUTO_CLICK_INTERVAL_MS = 350L
    private const val AUTO_CLICK_TIMEOUT_MS = 15_000L
    private const val PRIVACY_OPTIONS_WEBVIEW_SCAN_INTERVAL_MS = 120L
    private const val PRIVACY_OPTIONS_RETRY_DELAY_MS = 750L
    private const val PRIVACY_OPTIONS_MAX_RETRY_COUNT = 5
    private const val STRONG_CLICK_SCORE = 100
    private const val MAX_LOG_VALUE_LENGTH = 2_000

    private val mainHandler = Handler(Looper.getMainLooper())

    enum class DecisionMode {
        ACCEPT_ALL,
        REJECT
    }

    data class Result(
        val formError: FormError? = null,
        val localErrorMessage: String? = null
    )

    fun prepareHostActivity(activity: Activity) {
        activity.overridePendingTransition(0, 0)
        suppressWindow(activity.window)
    }

    fun showAndAcceptAllSilently(
        activity: Activity,
        consentForm: ConsentForm,
        onComplete: (Result) -> Unit
    ) {
        showAndApplyDecisionSilently(
            activity = activity,
            consentForm = consentForm,
            decisionMode = DecisionMode.ACCEPT_ALL,
            onComplete = onComplete
        )
    }

    fun showAndApplyDecisionSilently(
        activity: Activity,
        consentForm: ConsentForm,
        decisionMode: DecisionMode,
        onComplete: (Result) -> Unit
    ) {
        val completed = AtomicBoolean(false)
        val strongClickIssued = AtomicBoolean(false)
        val webView = findConsentWebView(consentForm)
        if (webView == null) {
            Log.w(TAG, "无法找到 UMP 内部 WebView，无法静默执行表单操作")
            onComplete(Result(localErrorMessage = "Unable to find UMP consent WebView"))
            return
        }

        prepareWebView(webView)

        var autoClickRunnable: Runnable? = null
        val timeoutRunnable = Runnable {
            if (completed.compareAndSet(false, true)) {
                Log.w(TAG, "UMP 静默表单自动点击超时")
                autoClickRunnable?.let { mainHandler.removeCallbacks(it) }
                dismissDialogIfPresent(consentForm)
                onComplete(Result(localErrorMessage = "UMP silent consent auto-click timed out"))
            }
        }

        fun completeOnce(result: Result) {
            if (!completed.compareAndSet(false, true)) {
                return
            }
            autoClickRunnable?.let { mainHandler.removeCallbacks(it) }
            mainHandler.removeCallbacks(timeoutRunnable)
            onComplete(result)
        }

        runCatching {
            prepareHostActivity(activity)
            consentForm.show(activity) { formError ->
                Log.i(TAG, "UMP 静默表单 dismiss 回调，formError=${formError?.message.orEmpty()}")
                completeOnce(Result(formError = formError))
            }
            suppressConsentFormSurface(activity, consentForm, webView)
        }.onFailure { error ->
            Log.e(TAG, "UMP 静默表单 show 失败", error)
            completeOnce(
                Result(
                    localErrorMessage = error.message ?: error.javaClass.simpleName
                )
            )
            return
        }

        autoClickRunnable = object : Runnable {
            private var attempt = 0

            override fun run() {
                if (completed.get()) {
                    return
                }
                attempt += 1
                injectAutoClickScript(webView, decisionMode, attempt) { clickScore ->
                    if (clickScore != null && clickScore >= STRONG_CLICK_SCORE) {
                        strongClickIssued.set(true)
                    }
                    if (!completed.get() && !strongClickIssued.get()) {
                        mainHandler.postDelayed(this, AUTO_CLICK_INTERVAL_MS)
                    }
                }
            }
        }

        mainHandler.postDelayed(timeoutRunnable, AUTO_CLICK_TIMEOUT_MS)
        mainHandler.postDelayed(autoClickRunnable, AUTO_CLICK_INITIAL_DELAY_MS)
    }

    fun showPrivacyOptionsAndApplyDecisionSilently(
        activity: Activity,
        decisionMode: DecisionMode,
        onComplete: (Result) -> Unit
    ) {
        val completed = AtomicBoolean(false)
        val strongClickIssued = AtomicBoolean(false)
        val autoClickStarted = AtomicBoolean(false)
        var privacyOptionsRetryCount = 0
        var autoClickRunnable: Runnable? = null
        var scanRunnable: Runnable? = null
        var activeWebView: WebView? = null

        val timeoutRunnable = Runnable {
            if (completed.compareAndSet(false, true)) {
                Log.w(TAG, "UMP privacy options 静默自动点击超时")
                autoClickRunnable?.let { mainHandler.removeCallbacks(it) }
                scanRunnable?.let { mainHandler.removeCallbacks(it) }
                onComplete(Result(localErrorMessage = "UMP silent privacy options auto-click timed out"))
            }
        }

        fun completeOnce(result: Result) {
            if (!completed.compareAndSet(false, true)) {
                return
            }
            autoClickRunnable?.let { mainHandler.removeCallbacks(it) }
            scanRunnable?.let { mainHandler.removeCallbacks(it) }
            mainHandler.removeCallbacks(timeoutRunnable)
            onComplete(result)
        }

        fun startAutoClick(webView: WebView) {
            if (!autoClickStarted.compareAndSet(false, true)) {
                return
            }
            autoClickRunnable = object : Runnable {
                private var attempt = 0

                override fun run() {
                    if (completed.get()) {
                        return
                    }
                    attempt += 1
                    injectAutoClickScript(webView, decisionMode, attempt) { clickScore ->
                        if (clickScore != null && clickScore >= STRONG_CLICK_SCORE) {
                            strongClickIssued.set(true)
                        }
                        if (!completed.get() && !strongClickIssued.get()) {
                            mainHandler.postDelayed(this, AUTO_CLICK_INTERVAL_MS)
                        }
                    }
                }
            }
            mainHandler.postDelayed(autoClickRunnable, AUTO_CLICK_INITIAL_DELAY_MS)
        }

        scanRunnable = object : Runnable {
            override fun run() {
                if (completed.get()) {
                    return
                }
                val webView = findPrivacyOptionsWebView(activity)
                if (webView != null) {
                    if (activeWebView !== webView) {
                        activeWebView = webView
                        prepareWebView(webView)
                        Log.i(TAG, "已找到 UMP privacy options WebView，开始静默执行 decision=$decisionMode")
                    }
                    suppressPrivacyOptionsSurface(activity, webView)
                    startAutoClick(webView)
                }
                mainHandler.postDelayed(this, PRIVACY_OPTIONS_WEBVIEW_SCAN_INTERVAL_MS)
            }
        }

        fun launchPrivacyOptionsForm() {
            runCatching {
                prepareHostActivity(activity)
                UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
                    val errorMessage = formError?.message.orEmpty()
                    if (!completed.get() &&
                        errorMessage.contains("loading", ignoreCase = true) &&
                        privacyOptionsRetryCount < PRIVACY_OPTIONS_MAX_RETRY_COUNT
                    ) {
                        privacyOptionsRetryCount += 1
                        Log.w(
                            TAG,
                            "UMP privacy options 仍在加载，准备重试 retry=$privacyOptionsRetryCount error=$errorMessage"
                        )
                        mainHandler.postDelayed({
                            if (!completed.get()) {
                                launchPrivacyOptionsForm()
                            }
                        }, PRIVACY_OPTIONS_RETRY_DELAY_MS)
                        return@showPrivacyOptionsForm
                    }

                    Log.i(TAG, "UMP privacy options dismiss 回调，formError=${formError?.message.orEmpty()}")
                    completeOnce(Result(formError = formError))
                }
            }.onFailure { error ->
                Log.e(TAG, "UMP privacy options show 失败", error)
                completeOnce(
                    Result(
                        localErrorMessage = error.message ?: error.javaClass.simpleName
                    )
                )
            }
        }

        runCatching {
            launchPrivacyOptionsForm()
        }.onFailure { error ->
            Log.e(TAG, "UMP privacy options 调用失败", error)
            completeOnce(Result(localErrorMessage = error.message ?: error.javaClass.simpleName))
        }

        mainHandler.postDelayed(timeoutRunnable, AUTO_CLICK_TIMEOUT_MS)
        mainHandler.post(scanRunnable)
    }

    private fun prepareWebView(webView: WebView) {
        webView.alpha = 0f
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.isFocusable = false
        webView.isFocusableInTouchMode = false
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val message = consoleMessage.message()
                if (message.startsWith("CodexUmpSilent:")) {
                    Log.d(TAG, message.take(MAX_LOG_VALUE_LENGTH))
                    return true
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }
    }

    private fun suppressConsentFormSurface(
        activity: Activity,
        consentForm: ConsentForm,
        webView: WebView
    ) {
        prepareHostActivity(activity)
        suppressWindow(findDialog(consentForm)?.window)
        webView.alpha = 0f
    }

    private fun suppressPrivacyOptionsSurface(
        activity: Activity,
        webView: WebView
    ) {
        prepareHostActivity(activity)
        webView.alpha = 0f
        findWindowRootViews()
            .filter { root -> root === webView || containsDescendant(root, webView) }
            .forEach { root ->
                runCatching {
                    root.alpha = 0f
                    root.setBackgroundColor(Color.TRANSPARENT)
                }.onFailure { error ->
                    Log.w(TAG, "隐藏 UMP privacy options 根视图失败：${error.message}")
                }
            }
    }

    private fun suppressWindow(window: Window?) {
        if (window == null) {
            return
        }
        runCatching {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            )
            val attributes = window.attributes
            attributes.alpha = 0f
            attributes.dimAmount = 0f
            attributes.windowAnimations = 0
            window.attributes = attributes
            window.decorView.alpha = 0f
        }.onFailure { error ->
            Log.w(TAG, "隐藏 UMP 宿主窗口失败：${error.message}")
        }
    }

    private fun injectAutoClickScript(
        webView: WebView,
        decisionMode: DecisionMode,
        attempt: Int,
        onResult: (Int?) -> Unit
    ) {
        val script = AUTO_DECISION_SCRIPT.replace("%%DECISION_MODE%%", decisionMode.name)
        runCatching {
            webView.evaluateJavascript(script) { rawValue ->
                val value = rawValue
                    ?.trim('"')
                    ?.replace("\\n", " ")
                    ?.replace("\\\"", "\"")
                    ?.take(MAX_LOG_VALUE_LENGTH)
                    .orEmpty()
                Log.d(TAG, "UMP 静默表单自动点击 mode=$decisionMode attempt=$attempt result=$value")
                onResult(parseClickScore(value))
            }
        }.onFailure { error ->
            Log.w(TAG, "UMP 静默表单 JS 注入失败：${error.message}")
            onResult(null)
        }
    }

    private fun parseClickScore(value: String): Int? {
        if (!value.startsWith("clicked:")) {
            return null
        }
        return value
            .removePrefix("clicked:")
            .substringBefore(":")
            .toIntOrNull()
    }

    private fun findConsentWebView(consentForm: ConsentForm): WebView? {
        return invokeNoArgMethod(consentForm, "zzc") as? WebView
            ?: findFieldValue(consentForm, WebView::class.java)
    }

    private fun findPrivacyOptionsWebView(activity: Activity): WebView? {
        val webViews = mutableListOf<WebView>()
        collectWebViews(activity.window?.decorView, webViews)
        findWindowRootViews().forEach { root ->
            collectWebViews(root, webViews)
        }
        return webViews
            .distinct()
            .lastOrNull { webView ->
                val url = runCatching { webView.url.orEmpty() }.getOrDefault("")
                val originalUrl = runCatching { webView.originalUrl.orEmpty() }.getOrDefault("")
                "$url $originalUrl".contains("fundingchoices", ignoreCase = true) ||
                    "$url $originalUrl".contains("consent", ignoreCase = true)
            }
            ?: webViews.distinct().lastOrNull()
    }

    private fun collectWebViews(view: View?, out: MutableList<WebView>) {
        if (view == null) {
            return
        }
        if (view is WebView) {
            out += view
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectWebViews(view.getChildAt(index), out)
            }
        }
    }

    private fun containsDescendant(root: View?, target: View): Boolean {
        if (root == null) {
            return false
        }
        if (root === target) {
            return true
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                if (containsDescendant(root.getChildAt(index), target)) {
                    return true
                }
            }
        }
        return false
    }

    private fun findWindowRootViews(): List<View> {
        return runCatching {
            val type = Class.forName("android.view.WindowManagerGlobal")
            val instance = type.getDeclaredMethod("getInstance").let { method ->
                method.isAccessible = true
                method.invoke(null)
            }
            val views = type.getDeclaredField("mViews").let { field ->
                field.isAccessible = true
                field.get(instance)
            }
            when (views) {
                is List<*> -> views.filterIsInstance<View>()
                is Array<*> -> views.filterIsInstance<View>()
                else -> emptyList()
            }
        }.onFailure { error ->
            Log.d(TAG, "读取 WindowManagerGlobal 根视图失败：${error.message}")
        }.getOrDefault(emptyList())
    }

    private fun findDialog(consentForm: ConsentForm): Dialog? {
        return findFieldValue(consentForm, Dialog::class.java)
    }

    private fun dismissDialogIfPresent(consentForm: ConsentForm) {
        runCatching {
            findDialog(consentForm)?.dismiss()
        }.onFailure { error ->
            Log.w(TAG, "关闭 UMP 静默 Dialog 失败：${error.message}")
        }
    }

    private fun invokeNoArgMethod(target: Any, methodName: String): Any? {
        return findMethod(target.javaClass, methodName)?.let { method ->
            runCatching {
                method.isAccessible = true
                method.invoke(target)
            }.getOrNull()
        }
    }

    private fun findMethod(type: Class<*>, methodName: String): Method? {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull {
                it.name == methodName && it.parameterTypes.isEmpty()
            }?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun <T> findFieldValue(target: Any, expectedType: Class<T>): T? {
        var current: Class<*>? = target.javaClass
        while (current != null) {
            current.declaredFields.forEach { field ->
                val value = readField(target, field)
                if (expectedType.isInstance(value)) {
                    return expectedType.cast(value)
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun readField(target: Any, field: Field): Any? {
        return runCatching {
            field.isAccessible = true
            field.get(target)
        }.getOrNull()
    }

    private const val AUTO_DECISION_SCRIPT = """
(function () {
  var decisionMode = '%%DECISION_MODE%%';

  function toText(value) {
    return (value || '').toString().replace(/\s+/g, ' ').trim();
  }

  function describe(element) {
    var attrs = [
      element.innerText,
      element.textContent,
      element.value,
      element.getAttribute && element.getAttribute('aria-label'),
      element.getAttribute && element.getAttribute('title'),
      element.getAttribute && element.getAttribute('name'),
      element.getAttribute && element.getAttribute('id'),
      element.getAttribute && element.getAttribute('class'),
      element.getAttribute && element.getAttribute('data-testid')
    ];
    return toText(attrs.filter(Boolean).join(' '));
  }

  function collect(root, out) {
    if (!root || !root.querySelectorAll) {
      return;
    }
    var nodes = root.querySelectorAll(
      'button,[role="button"],[role="link"],input[type="button"],input[type="submit"],a,[onclick],[jsaction],[tabindex]'
    );
    for (var i = 0; i < nodes.length; i++) {
      out.push(nodes[i]);
    }
    var all = root.querySelectorAll('*');
    for (var j = 0; j < all.length; j++) {
      if (all[j].shadowRoot) {
        collect(all[j].shadowRoot, out);
      }
    }
  }

  function collectAll(root, out) {
    if (!root || !root.querySelectorAll) {
      return;
    }
    var nodes = root.querySelectorAll('*');
    for (var i = 0; i < nodes.length; i++) {
      out.push(nodes[i]);
      if (nodes[i].shadowRoot) {
        collectAll(nodes[i].shadowRoot, out);
      }
    }
  }

  function queryDeep(selector) {
    var matches = [];
    function query(root) {
      if (!root || !root.querySelectorAll) {
        return;
      }
      var nodes = root.querySelectorAll(selector);
      for (var i = 0; i < nodes.length; i++) {
        matches.push(nodes[i]);
      }
      var all = root.querySelectorAll('*');
      for (var j = 0; j < all.length; j++) {
        if (all[j].shadowRoot) {
          query(all[j].shadowRoot);
        }
      }
    }
    query(document);
    return matches;
  }

  function visible(element) {
    if (!element || element.disabled || element.getAttribute('aria-disabled') === 'true') {
      return false;
    }
    var current = element;
    while (current && current.nodeType === Node.ELEMENT_NODE) {
      var currentStyle = window.getComputedStyle(current);
      if (!currentStyle ||
          currentStyle.display === 'none' ||
          currentStyle.visibility === 'hidden' ||
          currentStyle.opacity === '0' ||
          currentStyle.pointerEvents === 'none') {
        return false;
      }
      current = current.parentElement || (current.getRootNode && current.getRootNode().host);
    }
    var rect = element.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0;
  }

  function actionable(element) {
    if (!element || !element.tagName) {
      return false;
    }
    var tag = element.tagName.toLowerCase();
    var role = (element.getAttribute('role') || '').toLowerCase();
    if (tag === 'button' || tag === 'a' || tag === 'input' || role === 'button') {
      return true;
    }
    if (element.onclick || element.getAttribute('onclick') || element.getAttribute('jsaction')) {
      return true;
    }
    var className = (element.getAttribute('class') || '').toLowerCase();
    if (hasAny(className, [
      'fc-button', 'fc-cta', 'fc-confirm-choices', 'fc-save-continue',
      'fc-cta-do-not-consent', 'fc-cta-manage-options'
    ])) {
      return true;
    }
    var style = window.getComputedStyle(element);
    return !!style && style.cursor === 'pointer';
  }

  function hasAny(text, words) {
    for (var i = 0; i < words.length; i++) {
      if (text.indexOf(words[i]) !== -1) {
        return true;
      }
    }
    return false;
  }

  function scrollForMoreTargets() {
    var scrolled = false;
    var nodes = [];
    var all = [];
    collectAll(document, all);
    for (var i = 0; i < all.length; i++) {
      var candidate = all[i];
      if (!candidate || !candidate.scrollHeight || !candidate.clientHeight) {
        continue;
      }
      if (candidate.scrollHeight <= candidate.clientHeight + 4) {
        continue;
      }
      if (!visibleForScroll(candidate)) {
        continue;
      }
      nodes.push(candidate);
    }
    nodes.push(document.scrollingElement, document.documentElement, document.body);
    nodes = nodes
      .filter(Boolean)
      .sort(function (left, right) {
        return (right.scrollHeight - right.clientHeight) - (left.scrollHeight - left.clientHeight);
      })
      .slice(0, 8);
    for (var j = 0; j < nodes.length; j++) {
      var node = nodes[j];
      if (!node || !node.scrollHeight || !node.clientHeight) {
        continue;
      }
      if (node.scrollHeight <= node.clientHeight + 4) {
        continue;
      }
      var before = node.scrollTop || 0;
      var next = Math.min(before + Math.max(240, Math.floor(node.clientHeight * 0.75)), node.scrollHeight);
      node.scrollTop = next;
      if ((node.scrollTop || 0) !== before) {
        scrolled = true;
      }
    }
    return scrolled;
  }

  function visibleForScroll(element) {
    var current = element;
    while (current && current.nodeType === Node.ELEMENT_NODE) {
      var currentStyle = window.getComputedStyle(current);
      if (!currentStyle || currentStyle.display === 'none' || currentStyle.visibility === 'hidden') {
        return false;
      }
      current = current.parentElement || (current.getRootNode && current.getRootNode().host);
    }
    var rect = element.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0;
  }

  function forcePreferenceScrollToEnd() {
    var scrolled = false;
    var selectors = [
      '.fc-data-preferences-dialog',
      '.fc-dialog-scrollable-content',
      '.fc-scrollable-content',
      '.fc-preference-container',
      '.fc-preferences-container',
      '[class*="data-preferences"]',
      '[class*="scrollable"]'
    ];
    var targets = [];
    for (var i = 0; i < selectors.length; i++) {
      targets = targets.concat(queryDeep(selectors[i]));
    }
    targets.push(document.scrollingElement, document.documentElement, document.body);
    for (var j = 0; j < targets.length; j++) {
      var target = targets[j];
      if (!target || !target.scrollHeight || !target.clientHeight) {
        continue;
      }
      if (target.scrollHeight <= target.clientHeight + 4) {
        continue;
      }
      var before = target.scrollTop || 0;
      target.scrollTop = target.scrollHeight;
      if ((target.scrollTop || 0) !== before) {
        scrolled = true;
      }
    }
    return scrolled;
  }

  function clickElement(element, clickScore, reason) {
    var label = describe(element).slice(0, 160);
    element.scrollIntoView && element.scrollIntoView({ block: 'center', inline: 'center' });
    element.focus && element.focus();
    element.click();
    console.log('CodexUmpSilent: clicked score=' + clickScore + ' reason=' + reason + ' label=' + label);
    return 'clicked:' + clickScore + ':' + reason + ':' + label;
  }

  function clickBySelectors(selectors, clickScore, reason) {
    for (var i = 0; i < selectors.length; i++) {
      var matches = queryDeep(selectors[i]);
      for (var j = 0; j < matches.length; j++) {
        var element = matches[j];
        if (visible(element) && actionable(element)) {
          return clickElement(element, clickScore, reason);
        }
      }
    }
    return null;
  }

  function isDataPreferencesPage() {
    var bodyText = document.body ? describe(document.body).toLowerCase() : '';
    return !!queryDeep('.fc-data-preferences-back, .fc-confirm-choices, .fc-save-continue, [class*="data-preferences"]').length ||
      hasAny(bodyText, ['data preferences', 'manage your data', 'vendors want your permission']);
  }

  function clearRejectPreferences() {
    var selectors = [
      'input.fc-preference-consent:checked',
      'input.fc-preference-legitimate-interest:checked',
      'input[type="checkbox"]:checked',
      'input[type="radio"]:checked[value="true"]',
      '[aria-checked="true"][role="checkbox"]',
      '[aria-checked="true"][role="switch"]',
      '[class*="preference"][aria-checked="true"]'
    ];
    var clicked = 0;
    var seen = [];
    for (var i = 0; i < selectors.length; i++) {
      var matches = queryDeep(selectors[i]);
      for (var j = 0; j < matches.length; j++) {
        var element = matches[j];
        if (seen.indexOf(element) !== -1 || element.disabled || element.getAttribute('aria-disabled') === 'true') {
          continue;
        }
        seen.push(element);
        element.click();
        clicked += 1;
      }
    }
    if (clicked > 0) {
      console.log('CodexUmpSilent: unchecked reject preferences count=' + clicked);
    }
    return clicked;
  }

  function setAcceptPreferences() {
    var selectors = [
      'input.fc-preference-consent:not(:checked)',
      'input.fc-preference-legitimate-interest:not(:checked)',
      'input[type="checkbox"]:not(:checked)',
      'input[type="radio"][value="true"]:not(:checked)',
      '[aria-checked="false"][role="checkbox"]',
      '[aria-checked="false"][role="switch"]',
      '[class*="preference"][aria-checked="false"]'
    ];
    var clicked = 0;
    var seen = [];
    for (var i = 0; i < selectors.length; i++) {
      var matches = queryDeep(selectors[i]);
      for (var j = 0; j < matches.length; j++) {
        var element = matches[j];
        if (seen.indexOf(element) !== -1 || element.disabled || element.getAttribute('aria-disabled') === 'true') {
          continue;
        }
        seen.push(element);
        element.click();
        clicked += 1;
      }
    }
    if (clicked > 0) {
      console.log('CodexUmpSilent: checked accept preferences count=' + clicked);
    }
    return clicked;
  }

  function score(element) {
    var text = describe(element).toLowerCase();
    if (!text) {
      return -1000;
    }
    var rejectWords = [
      'reject all', 'reject', 'decline', 'decline all', 'disagree',
      'do not consent', 'do not agree', 'refuse', 'refuse all',
      'do-not-consent', 'fc-cta-do-not-consent',
      'ablehnen', 'nicht zustimmen', 'tout refuser', 'refuser',
      'rechazar', 'rechazar todo', 'rifiuta', 'rifiuta tutto',
      'non accetto', 'weigeren', 'alles weigeren'
    ];
    var manageWords = [
      'manage options', 'manage choices', 'options', 'settings',
      'privacy options', 'more options', 'customize',
      'manage-options', 'fc-cta-manage-options',
      'gérer les options', 'gerer les options', 'paramètres',
      'parametres', 'preferencias', 'impostazioni', 'einstellungen'
    ];
    var confirmWords = [
      'confirm choices', 'confirm my choices', 'save choices',
      'save settings', 'confirm selection', 'submit choices',
      'confirm-choices', 'fc-confirm-choices', 'save-continue',
      'fc-save-continue', 'fc-save-choices', 'fc-save-settings',
      'done', 'confirm', 'continue',
      'confirmer mes choix', 'enregistrer mes choix', 'valider',
      'guardar opciones', 'confirmar opciones', 'conferma scelte',
      'auswahl bestätigen', 'speichern'
    ];
    var acceptStrongest = [
      'accept all', 'accept everything', 'allow all', 'agree to all', 'consent to all',
      'alle akzeptieren', 'alles akzeptieren', 'tout accepter', 'accepter tout',
      'aceptar todo', 'accetta tutto', 'aceitar tudo', 'alles accepteren',
      'akceptuj wszystko', 'accepter alle', 'acceptera alla', 'godta alle'
    ];
    var acceptStrong = [
      'i agree', 'agree', 'accept', 'allow', 'confirm',
      'akzeptieren', 'zustimmen', 'einverstanden', "j'accepte", 'j accepte',
      'accepter', 'aceptar', 'accetta', 'accetto', 'aceitar', 'accepteren',
      'akceptuj', 'zgadzam'
    ];
    var fallbackWords = [
      'continue', 'proceed', 'done', 'ok', 'got it',
      'weiter', 'fortfahren', 'continuer', 'continuar', 'avanti'
    ];

    if (decisionMode === 'REJECT') {
      var rejectScore = 0;
      if (hasAny(text, [
        'back', 'go back', 'close',
        'retour', 'zurück', 'zuruck', 'atrás', 'atras', 'indietro',
        'fc-dialog-header-back', 'fc-data-preferences-back'
      ])) {
        return -1000;
      }
      if (hasAny(text, acceptStrongest) || hasAny(text, acceptStrong)) {
        return -1000;
      }
      if (hasAny(text, ['list of partners', 'partners list', 'vendors list'])) {
        return -1000;
      }
      if (hasAny(text, rejectWords)) {
        rejectScore += 220;
      }
      if (hasAny(text, confirmWords)) {
        rejectScore += 130;
      }
      if (hasAny(text, manageWords)) {
        rejectScore += 80;
      }
      if (element.tagName === 'BUTTON' || element.getAttribute('role') === 'button') {
        rejectScore += 10;
      }
      return rejectScore;
    }

    if (hasAny(text, rejectWords)) {
      return -1000;
    }
    if (hasAny(text, ['list of partners', 'partners list', 'vendors list'])) {
      return -1000;
    }
    var scoreValue = 0;
    if (hasAny(text, acceptStrongest)) {
      scoreValue += 200;
    }
    if (hasAny(text, acceptStrong)) {
      scoreValue += 120;
    }
    if (hasAny(text, fallbackWords)) {
      scoreValue += 40;
    }
    if (text.length <= 80 && hasAny(text, ['consent', 'i consent'])) {
      scoreValue += 120;
    }
    if (hasAny(text, ['manage', 'options', 'settings', 'preferences', 'privacy', 'more'])) {
      scoreValue -= 80;
    }
    if (element.tagName === 'BUTTON' || element.getAttribute('role') === 'button') {
      scoreValue += 10;
    }
    return scoreValue;
  }

  try {
    if (decisionMode === 'ACCEPT_ALL') {
      var directAccept = clickBySelectors([
        '.fc-cta-consent',
        '.fc-cta-accept-all',
        '[class*="accept-all"]',
        '[aria-label*="Accept all" i]',
        '[aria-label*="Agree" i]',
        '[aria-label*="Tout accepter" i]',
        '[aria-label*="Accepter tout" i]',
        '[aria-label*="Alle akzeptieren" i]'
      ], 240, 'direct_accept_all');
      if (directAccept) {
        return directAccept;
      }

      if (isDataPreferencesPage()) {
        var accepted = setAcceptPreferences();
        if (accepted > 0) {
          return 'clicked:95:set_accept_preferences:' + accepted;
        }
        forcePreferenceScrollToEnd();
        var directConfirmAccept = clickBySelectors([
          '.fc-confirm-choices',
          '.fc-save-continue',
          '.fc-save-choices',
          '.fc-save-settings',
          '[class*="confirm-choices"]',
          '[class*="save-continue"]',
          '[class*="save-choices"]',
          '[aria-label*="Confirm" i]',
          '[aria-label*="Save" i]',
          '[aria-label*="Enregistrer" i]',
          '[aria-label*="Confirmer" i]'
        ], 170, 'direct_confirm_accept_choices');
        if (directConfirmAccept) {
          return directConfirmAccept;
        }
      }
    }

    if (decisionMode === 'REJECT') {
      var directReject = clickBySelectors([
        '.fc-cta-do-not-consent',
        '[class*="do-not-consent"]',
        '[aria-label*="Reject" i]',
        '[aria-label*="Decline" i]',
        '[aria-label*="Refuse" i]',
        '[aria-label*="Tout refuser" i]',
        '[aria-label*="Ablehnen" i]'
      ], 240, 'direct_reject');
      if (directReject) {
        return directReject;
      }

      if (isDataPreferencesPage()) {
        var cleared = clearRejectPreferences();
        if (cleared > 0) {
          return 'clicked:95:cleared_reject_preferences:' + cleared;
        }
        forcePreferenceScrollToEnd();
        var directConfirm = clickBySelectors([
          '.fc-confirm-choices',
          '.fc-save-continue',
          '.fc-save-choices',
          '.fc-save-settings',
          '[class*="confirm-choices"]',
          '[class*="save-continue"]',
          '[class*="save-choices"]',
          '[aria-label*="Confirm" i]',
          '[aria-label*="Save" i]',
          '[aria-label*="Enregistrer" i]',
          '[aria-label*="Confirmer" i]'
        ], 170, 'direct_confirm_reject_choices');
        if (directConfirm) {
          return directConfirm;
        }
      }

      var directManage = clickBySelectors([
        '.fc-cta-manage-options',
        '[class*="manage-options"]',
        '[aria-label*="Manage" i]',
        '[aria-label*="Options" i]',
        '[aria-label*="Gérer" i]',
        '[aria-label*="Paramètres" i]'
      ], 90, 'direct_manage_options');
      if (directManage) {
        return directManage;
      }
    }

    var candidates = [];
    collect(document, candidates);
    var best = null;
    var bestScore = 0;
    var summary = [];
    for (var i = 0; i < candidates.length; i++) {
      var candidate = candidates[i];
      if (!actionable(candidate) || !visible(candidate)) {
        continue;
      }
      var candidateScore = score(candidate);
      var candidateLabel = describe(candidate).slice(0, 80);
      summary.push(
        candidate.tagName + ':' + (candidate.getAttribute('role') || '') +
          ':' + candidateScore + ':' + candidateLabel
      );
      if (candidateScore > bestScore) {
        bestScore = candidateScore;
        best = candidate;
      }
    }
    if (best && bestScore >= 40) {
      var label = describe(best).slice(0, 160);
      best.focus && best.focus();
      best.click();
      console.log('CodexUmpSilent: clicked score=' + bestScore + ' label=' + label);
      return 'clicked:' + bestScore + ':' + label;
    }
    if (decisionMode === 'REJECT' && scrollForMoreTargets()) {
      console.log('CodexUmpSilent: scrolled candidates=' + summary.slice(0, 20).join(' || '));
      return 'scrolling:' + summary.slice(0, 16).join(' || ');
    }
    var disabledSummary = [];
    var interesting = [];
    collectAll(document, interesting);
    for (var k = 0; k < interesting.length && disabledSummary.length < 20; k++) {
      var node = interesting[k];
      var text = describe(node).toLowerCase();
      if ((node.disabled || node.getAttribute('aria-disabled') === 'true') &&
          hasAny(text, ['confirm', 'save', 'reject', 'consent', 'fc-confirm', 'fc-save'])) {
        disabledSummary.push(
          node.tagName + ':' + (node.getAttribute('role') || '') +
            ':disabled:' + describe(node).slice(0, 100)
        );
      }
    }
    var bodyText = document.body ? toText(document.body.innerText || document.body.textContent) : '';
    var body = bodyText.slice(0, 420) + ' ...TAIL... ' + bodyText.slice(Math.max(0, bodyText.length - 420));
    console.log('CodexUmpSilent: no target candidates=' + summary.slice(0, 20).join(' || ') + ' disabled=' + disabledSummary.join(' || ') + ' body=' + body);
    return 'no_target:' + summary.slice(0, 16).join(' || ') + ':disabled=' + disabledSummary.join(' || ') + ':' + body;
  } catch (error) {
    console.log('CodexUmpSilent: error=' + error);
    return 'error:' + error;
  }
})()
"""
}
