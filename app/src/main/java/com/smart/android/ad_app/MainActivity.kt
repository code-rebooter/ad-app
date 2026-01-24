package com.smart.android.ad_app

import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.smart.android.ad_app.bean.Position
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        appContext.grantSystemAlertWindowPermission()
        requestOverlayPermission()



        lifecycleScope.launch {
            delay(5000)
            println("开始调用了")
            AdConfigManager.reportAdStatus(false)
            val floatingWindow = TvAdFloatingWindow(appContext)
            // 调用者设置悬浮窗参数
            floatingWindow.configure {
                width = 200
                height = 112
                x = 15
                y = 15
                position = Position.RIGHT_TOP
                isFocusable = false
            }
            // 检查权限并显示
            if (floatingWindow.hasOverlayPermission()) {
                println("开始显示悬浮窗")
                floatingWindow.show()
            }
            //AdConfigManager.getAdConfig(AdType.SPLASH)
        }

    }
}
