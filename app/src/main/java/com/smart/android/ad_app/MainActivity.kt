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

        lifecycleScope.launch {
            delay(3000)
            val floatingWindow = TvAdFloatingWindow(appContext)
            // 调用者设置悬浮窗参数
            floatingWindow.configure {
                width = 240
                height = 135
                x = 15
                y = 15
                position = Position.RIGHT_BOTTOM
                isFocusable = false
            }
            // 检查权限并显示
            if (floatingWindow.hasOverlayPermission()) {
                println("开始显示悬浮窗")
                floatingWindow.show()
            }
        }


    }
}
