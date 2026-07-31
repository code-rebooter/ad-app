package com.smart.android.ad_app

import android.annotation.SuppressLint
import android.content.Context
import android.os.CountDownTimer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.core.view.isVisible
import com.smart.android.ad_app.databinding.FloatAdBinding

class TvAdFloatingWindow(
    context: Context,
    private val adId: String? = null,
    private val soundEnabled: Boolean = false,
    private val onFloatingFlowFinished: (() -> Unit)? = null
) : TvFloatingWindowBase<FloatAdBinding>(context) {

    private var isCountdownFinished = false // 倒计时是否完成
    private lateinit var countdownTimer: CountDownTimer // 倒计时器
    private var hasDispatchedFlowFinished = false
    private var canHandleTouchClick = false
    private var hasHandledTouchClick = false
    private var touchClickOverlay: View? = null

    override fun onViewCreated() {
        installTouchClickOverlayIfNeeded()
        AdManagerImpl.showAd(
            binding.flAdcontainer,
            adId = adId,
            soundEnabled = soundEnabled,
            adStart = {
                "广告开始播放".adDebugPrintLog()
                enableTouchClickIfNeeded()
                if (canSetFocusable()) {
                    setFocusable(true)
                    startCountdown()
                }
            },
            adError = {
                "广告播放错误".adDebugPrintLog()
                disableTouchClick()
                hide()
                dispatchFlowFinishedOnce()
            }
        ) {
            "广告播放完成".adDebugPrintLog()
            disableTouchClick()
            hide()
            dispatchFlowFinishedOnce()
        }
    }

    override fun onBackPressed(): Boolean {
        if (!isCountdownFinished) {
            "W: 倒计时未结束，返回键无效".adDebugPrintLog()
            return true // 拦截返回键，不隐藏
        }
        "我按下了返回".adDebugPrintLog()
        binding.root.isVisible = false
        hide()
        return true
    }

    override fun onWindowHidden() {
        disableTouchClick()
        cancelCountdown()
        AdManagerImpl.destroyAd()
        dispatchFlowFinishedOnce()
    }

    override fun onWindowDestroyed() {
        disableTouchClick()
        cancelCountdown()
        dispatchFlowFinishedOnce()
    }

    override fun onPermissionDenied() {
        dispatchFlowFinishedOnce()
    }

    /**
     * 启动10秒倒计时，更新tv_tip文本
     */
    private fun startCountdown() {
        cancelCountdown()
        isCountdownFinished = false
        countdownTimer = object : CountDownTimer(10_000, 1_000) {
            @SuppressLint("StringFormatInvalid")
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = (millisUntilFinished / 1000).toInt() + 1
                binding.tvTip.isVisible = true
                binding.tvTip.text = appContext.getString(R.string.app_closure, secondsLeft)
                binding.tvTip.text.toString().adDebugPrintLog()
            }

            override fun onFinish() {
                isCountdownFinished = true
                binding.tvTip.text = appContext.getString(R.string.app_Return)
            }
        }.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installTouchClickOverlayIfNeeded() {
        if (!BuildFlavor.isHq008Family()) {
            return
        }
        val overlay = View(context).apply {
            isClickable = true
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isVisible = false
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    handleTouchClick()
                }
                true
            }
        }
        binding.root.addView(
            overlay,
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        )
        touchClickOverlay = overlay
    }

    private fun enableTouchClickIfNeeded() {
        if (!BuildFlavor.isHq008Family()) {
            return
        }
        canHandleTouchClick = true
        touchClickOverlay?.isVisible = true
    }

    private fun disableTouchClick() {
        canHandleTouchClick = false
        touchClickOverlay?.isVisible = false
    }

    private fun handleTouchClick() {
        if (!canHandleTouchClick || hasHandledTouchClick) {
            return
        }
        hasHandledTouchClick = true
        disableTouchClick()
        "广告区域收到触摸点击，立即停止广告".adDebugPrintLog()
        AdTouchClickReporter.reportHq008TouchClick(adId)
        cancelCountdown()
        AdManagerImpl.destroyAd()
        binding.root.isVisible = false
        hide()
        dispatchFlowFinishedOnce()
    }

    private fun cancelCountdown() {
        if (::countdownTimer.isInitialized) {
            countdownTimer.cancel()
        }
        isCountdownFinished = true
    }

    private fun dispatchFlowFinishedOnce() {
        if (hasDispatchedFlowFinished) return
        hasDispatchedFlowFinished = true
        onFloatingFlowFinished?.invoke()
    }
}
