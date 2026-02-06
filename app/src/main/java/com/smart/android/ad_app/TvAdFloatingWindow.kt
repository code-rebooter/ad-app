package com.smart.android.ad_app

import android.annotation.SuppressLint
import android.content.Context
import android.os.CountDownTimer
import android.os.Handler
import androidx.core.view.isVisible
import com.smart.android.ad_app.databinding.FloatAdBinding

class TvAdFloatingWindow(context: Context) : TvFloatingWindowBase<FloatAdBinding>(context) {

    private var isCountdownFinished = false // 倒计时是否完成
    private lateinit var countdownTimer: CountDownTimer // 倒计时器

    override fun onViewCreated() {
        AdManagerImpl.showAd(binding.flAdcontainer, adStart = {
            println("广告开始播放")
            if(canSetFocusable()){
                setFocusable(true)
                // 启动10秒倒计时
                startCountdown()
            }

        }, adError = {
            //广告错误
            println("广告播放错误")
            hide()
        }) {
            //广告播放完成
            println("广告播放完成")
            hide()
        }
    }

    override fun onBackPressed(): Boolean {
        if (!isCountdownFinished) {
            println("W: 倒计时未结束，返回键无效")
            return true // 拦截返回键，不隐藏
        }
        println("我按下了返回")
        binding.root.isVisible = false
        hide()
        return true
    }

    override fun onWindowHidden() {
        //在这里销毁广告
        AdManagerImpl.destroyAd()
    }

    /**
     * 启动10秒倒计时，更新tv_tip文本
     */
    private fun startCountdown() {
        isCountdownFinished = false
        countdownTimer = object : CountDownTimer(10_000, 1_000) {
            @SuppressLint("StringFormatInvalid")
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = (millisUntilFinished / 1000).toInt() + 1
                binding.tvTip.isVisible = true
                binding.tvTip.text = appContext.getString(R.string.app_closure, secondsLeft)
                println(binding.tvTip.text.toString())
            }

            override fun onFinish() {
                isCountdownFinished = true
                binding.tvTip.text = appContext.getString(R.string.app_Return)
            }
        }.start()
    }

}