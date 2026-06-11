package com.smart.android.ad_app

import android.annotation.SuppressLint
import android.content.Context
import android.os.CountDownTimer
import androidx.core.view.isVisible
import com.smart.android.ad_app.databinding.FloatAdBinding
import com.speed.log.printLog

class TvAdFloatingWindow(
    context: Context,
    private val adId: String? = null,
    private val onFloatingFlowFinished: (() -> Unit)? = null
) : TvFloatingWindowBase<FloatAdBinding>(context) {

    private var isCountdownFinished = false // 倒计时是否完成
    private lateinit var countdownTimer: CountDownTimer // 倒计时器
    private var hasDispatchedFlowFinished = false

    override fun onViewCreated() {
        AdManagerImpl.showAd(
            binding.flAdcontainer,
            adId = adId,
            adStart = {
                "广告开始播放".printLog()
                if (canSetFocusable()) {
                    setFocusable(true)
                    startCountdown()
                }
            },
            adError = {
                "广告播放错误".printLog()
                hide()
                dispatchFlowFinishedOnce()
            }
        ) {
            "广告播放完成".printLog()
            hide()
            dispatchFlowFinishedOnce()
        }
    }

    override fun onBackPressed(): Boolean {
        if (!isCountdownFinished) {
            "W: 倒计时未结束，返回键无效".printLog()
            return true // 拦截返回键，不隐藏
        }
        "我按下了返回".printLog()
        binding.root.isVisible = false
        hide()
        return true
    }

    override fun onWindowHidden() {
        cancelCountdown()
        AdManagerImpl.destroyAd()
        dispatchFlowFinishedOnce()
    }

    override fun onWindowDestroyed() {
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
                binding.tvTip.text.toString().printLog()
            }

            override fun onFinish() {
                isCountdownFinished = true
                binding.tvTip.text = appContext.getString(R.string.app_Return)
            }
        }.start()
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
