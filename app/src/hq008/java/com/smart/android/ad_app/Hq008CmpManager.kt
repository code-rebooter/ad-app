package com.smart.android.ad_app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.tcl.ff.component.oversea.CmpConfigParams
import com.tcl.ff.component.overseabase.base.util.Logger
import com.tcl.ff.component.overseabase.base.util.GlobalContext
import java.util.Locale

/**
 * Normal, user-visible CMP entry for the HQ008 TCL integration.
 *
 * The application still has no launcher activity.  When the ad scheduler needs
 * consent, this coordinator briefly starts [Hq008CmpConsentActivity], lets the
 * CMP SDK render its own popup, and resumes the existing background flow after
 * the user finishes.
 */
object Hq008CmpManager {
    private const val TAG = "Hq008CmpManager"
    private const val DEFAULT_ZONE = "de"
    private const val PREFS_NAME = "hq008_cmp_consent"
    private const val KEY_COMPLETED_VERSION = "completed_version"
    private const val KEY_CONSENT_STRING = "consent_string"
    private const val CONSENT_ACTIVITY_TIMEOUT_MS = 10 * 60_000L
    private const val NO_SESSION_ID = -1L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingCallbacks = mutableListOf<(Boolean) -> Unit>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var consentString: String? = null

    @Volatile
    private var consentResolved = false

    @Volatile
    private var activityLaunchInProgress = false

    private var nextSessionId = 0L
    private var activeSessionId = NO_SESSION_ID
    private var timeoutRunnable: Runnable? = null

    fun init(context: Context) {
        if (!BuildConfig.HQ008_LOCAL_INTEGRATION) {
            return
        }
        val applicationContext = context.applicationContext
        appContext = applicationContext
        if (BuildConfig.DEBUG) {
            Logger.setEnableLog(true)
        }
        runCatching {
            GlobalContext.setAppContext(applicationContext)
        }.onFailure { error ->
            Log.w(TAG, "CMP SDK global context initialization failed", error)
        }
        loadPersistedConsent(applicationContext)
        Log.i(
            TAG,
            "Normal CMP integration initialized, consentLength=${consentString?.length ?: 0}, resolved=$consentResolved"
        )
    }

    fun getConsentString(): String? = consentString

    /**
     * Delivers a callback once consent is available.  The first request of the
     * current version opens the internal host Activity; later requests reuse the
     * user decision persisted by the CMP SDK.
     */
    fun ensureConsent(onResult: (Boolean) -> Unit) {
        if (!BuildConfig.HQ008_LOCAL_INTEGRATION) {
            onResult(true)
            return
        }

        val context = appContext
        if (context == null) {
            onResult(false)
            return
        }

        var sessionId = NO_SESSION_ID
        synchronized(this) {
            if (consentResolved) {
                onResult(true)
                return
            }
            pendingCallbacks += onResult
            if (activityLaunchInProgress) {
                return
            }
            activityLaunchInProgress = true
            nextSessionId += 1
            activeSessionId = nextSessionId
            sessionId = activeSessionId
        }

        Log.i(TAG, "Starting internal CMP host Activity, sessionId=$sessionId")
        val intent = Hq008CmpConsentActivity.createIntent(context, sessionId)
        runCatching {
            context.startActivity(intent)
        }.onFailure { error ->
            Log.e(TAG, "Unable to start internal CMP host Activity", error)
            onConsentActivityFinished(
                sessionId,
                consent = null,
                consentAction = null,
                completed = false
            )
            return
        }

        timeoutRunnable?.let(mainHandler::removeCallbacks)
        val timeout = Runnable {
            Log.w(TAG, "CMP host Activity timed out, sessionId=$sessionId; skipping this ad request")
            onConsentActivityFinished(
                sessionId,
                consent = null,
                consentAction = null,
                completed = false
            )
        }
        timeoutRunnable = timeout
        mainHandler.postDelayed(timeout, CONSENT_ACTIVITY_TIMEOUT_MS)
    }

    internal fun onConsentActivityFinished(
        sessionId: Long,
        consent: String?,
        consentAction: String?,
        completed: Boolean
    ) {
        val callbacks: List<(Boolean) -> Unit>
        val context = appContext
        synchronized(this) {
            if (sessionId != activeSessionId) {
                Log.i(
                    TAG,
                    "Ignoring stale CMP Activity result, sessionId=$sessionId, activeSessionId=$activeSessionId"
                )
                return
            }
            timeoutRunnable?.let(mainHandler::removeCallbacks)
            timeoutRunnable = null
            activityLaunchInProgress = false
            activeSessionId = NO_SESSION_ID
            if (completed) {
                consentString = consent?.takeIf { it.isNotBlank() }
                consentResolved = true
                context?.let(::persistConsent)
            }
            callbacks = pendingCallbacks.toList()
            pendingCallbacks.clear()
        }
        val notifyCallbacks = {
            callbacks.forEach { callback ->
                mainHandler.post { callback(completed) }
            }
        }
        val resolvedAction = consentAction?.takeIf { completed && it.isNotBlank() }
        if (resolvedAction == null) {
            notifyCallbacks()
            return
        }
        Hq008ConsentReportClient.reportConsentResult(resolvedAction) { error ->
            if (error != null) {
                Log.w(TAG, "consent-report failed for action=$resolvedAction, error=$error")
            } else {
                Log.i(TAG, "consent-report completed for action=$resolvedAction")
            }
            notifyCallbacks()
        }
    }

    internal fun buildCmpConfig(context: Context, forcePopup: Boolean): CmpConfigParams {
        return CmpConfigParams.Builder()
            .setDeviceMake(resolveDeviceMake())
            .setDeviceId(resolveDeviceId(context))
            .setZone(DEFAULT_ZONE)
            .setClientType(resolveClientType())
            .setShowPopForce(forcePopup)
            .setCorner(24f)
            .build()
    }

    private fun loadPersistedConsent(context: Context) {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val completedVersion = preferences.getInt(KEY_COMPLETED_VERSION, -1)
        if (completedVersion == BuildConfig.VERSION_CODE) {
            consentString = preferences.getString(KEY_CONSENT_STRING, null)
            consentResolved = true
        }
    }

    private fun persistConsent(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_COMPLETED_VERSION, BuildConfig.VERSION_CODE)
            .putString(KEY_CONSENT_STRING, consentString)
            .apply()
    }

    @SuppressLint("HardwareIds")
    private fun resolveDeviceId(context: Context): String {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
    }

    private fun resolveDeviceMake(): String {
        return Build.MANUFACTURER.orEmpty()
            .ifBlank { "android" }
            .lowercase(Locale.US)
    }

    private fun resolveClientType(): String {
        return Build.MODEL.orEmpty().ifBlank { "android" }
    }
}
