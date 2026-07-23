package com.smart.android.ad_app

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import com.tcl.ff.component.oversea.CmpConsentManager
import com.tcl.ff.component.oversea.constant.CMPErrorCode
import com.tcl.ff.component.oversea.constant.CmpDisplayType
import com.tcl.ff.component.oversea.listener.OnCmpStatusListener
import com.tcl.ff.component.oversea.model.expose.GDPRConsent

/**
 * Internal host required by the CMP SDK.  It deliberately has no MAIN or
 * LAUNCHER intent filter and closes as soon as the CMP flow finishes.
 */
class Hq008CmpConsentActivity : FragmentActivity() {

    companion object {
        private const val TAG = "Hq008CmpConsent"
        private const val SHOW_POPUP_FALLBACK_DELAY_MS = 500L
        private const val EXTRA_SESSION_ID = "cmp_session_id"

        internal fun createIntent(context: Context, sessionId: Long): Intent {
            return Intent(context, Hq008CmpConsentActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cmpConsentManager = CmpConsentManager()
    private val sessionId by lazy {
        intent.getLongExtra(EXTRA_SESSION_ID, -1L)
    }
    private var popupRequested = false
    private var resultDelivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContentView(FrameLayout(this))
        requestCmpPopup()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { cmpConsentManager.dismissCmpFragment() }
        runCatching { cmpConsentManager.release() }
        if (!resultDelivered) {
            resultDelivered = true
            Hq008CmpManager.onConsentActivityFinished(
                sessionId,
                consent = null,
                consentAction = null,
                completed = false
            )
        }
        super.onDestroy()
    }

    private fun requestCmpPopup() {
        val config = Hq008CmpManager.buildCmpConfig(this, forcePopup = true)
        Log.i(TAG, "Loading normal CMP popup through the public SDK entry")
        runCatching {
            cmpConsentManager.loadCmpPrivacy(
                CmpDisplayType.CMP_POP,
                config,
                this,
                object : OnCmpStatusListener {
                    override fun onCmpDataReady() {
                        mainHandler.postDelayed({
                            if (!popupRequested && !resultDelivered && !isFinishing) {
                                popupRequested = true
                                runCatching {
                                    cmpConsentManager.showCmpPop(supportFragmentManager)
                                }.onFailure { error ->
                                    Log.e(TAG, "CMP popup display failed", error)
                                    finishWithResult(
                                        consent = null,
                                        consentAction = null,
                                        completed = false
                                    )
                                }
                            }
                        }, SHOW_POPUP_FALLBACK_DELAY_MS)
                    }

                    override fun onConsentStringReady(
                        consentString: String?,
                        gdprConsent: GDPRConsent?
                    ) {
                        if (!popupRequested) {
                            Log.i(TAG, "Ignoring the stored CMP snapshot until the popup is displayed")
                            return
                        }
                        Log.i(
                            TAG,
                            "CMP user decision completed, consentLength=${consentString?.length ?: 0}, action=${gdprConsent?.consentAction}"
                        )
                        finishWithResult(
                            consent = consentString,
                            consentAction = gdprConsent?.consentAction?.toString(),
                            completed = true
                        )
                    }

                    override fun onCmpPopup() {
                        popupRequested = true
                        Log.i(TAG, "CMP SDK popup displayed")
                    }

                    override fun onError(errorCode: CMPErrorCode) {
                        Log.e(
                            TAG,
                            "CMP SDK error: ${errorCode.name}, code=${errorCode.errorCode}, message=${errorCode.msg}"
                        )
                        finishWithResult(consent = null, consentAction = null, completed = false)
                    }
                },
                null
            )
        }.onFailure { error ->
            Log.e(TAG, "CMP SDK popup request failed", error)
            finishWithResult(consent = null, consentAction = null, completed = false)
        }
    }

    private fun finishWithResult(
        consent: String?,
        consentAction: String?,
        completed: Boolean
    ) {
        if (resultDelivered) {
            return
        }
        resultDelivered = true
        Hq008CmpManager.onConsentActivityFinished(
            sessionId,
            consent,
            consentAction,
            completed
        )
        finish()
    }
}
