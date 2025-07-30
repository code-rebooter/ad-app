package com.smart.android.ad_app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.*
import androidx.viewbinding.ViewBinding
import com.smart.android.ad_app.bean.Position
import java.lang.reflect.ParameterizedType

abstract class TvFloatingWindowBase<T : ViewBinding>(
    context: Context
) {
    protected val context: Context = context.applicationContext
    protected lateinit var binding: T
    protected var windowManager: WindowManager? = null
    protected var rootView: View? = null
    protected var layoutParams: WindowManager.LayoutParams? = null
    private var isShowing = false
    private var config: WindowConfig = WindowConfig()

    data class WindowConfig(
        var width: Int? = null,
        var height: Int? = null,
        var x: Int = 0,
        var y: Int = 0,
        var position: Position = Position.RIGHT_BOTTOM
    )

    init {
        initWindowManager()
    }

    private fun initWindowManager() {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: throw IllegalStateException("WindowManager service is not available")
    }

    fun configure(configBlock: WindowConfig.() -> Unit): TvFloatingWindowBase<T> {
        config = WindowConfig().apply(configBlock)
        layoutParams?.let {
            it.width = config.width ?: WindowManager.LayoutParams.WRAP_CONTENT
            it.height = config.height ?: WindowManager.LayoutParams.WRAP_CONTENT
            it.x = config.x
            it.y = config.y
            it.gravity = config.position.toGravity()
            if (isShowing) {
                windowManager?.updateViewLayout(rootView, it)
            }
        }
        return this
    }

    private fun Position.toGravity(): Int = when (this) {
        Position.RIGHT_BOTTOM -> Gravity.BOTTOM or Gravity.END
        Position.LEFT_TOP -> Gravity.TOP or Gravity.START
        Position.RIGHT_TOP -> Gravity.TOP or Gravity.END
        Position.LEFT_BOTTOM -> Gravity.BOTTOM or Gravity.START
        Position.CENTER -> Gravity.CENTER
        Position.LEFT_CENTER -> Gravity.START or Gravity.CENTER_VERTICAL
        Position.RIGHT_CENTER -> Gravity.END or Gravity.CENTER_VERTICAL
        Position.TOP_CENTER -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
        Position.BOTTOM_CENTER -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    }

    fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun requestOverlayPermission(requestCode: Int, activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !hasOverlayPermission()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            activity.startActivityForResult(intent, requestCode)
        }
    }

    @Suppress("UNCHECKED_CAST")
    protected open fun createView(): View {
        val inflater = LayoutInflater.from(context).cloneInContext(context)
        // 反射调用对应的 inflate(LayoutInflater, ViewGroup?, Boolean) 静态方法
        val clazz = (javaClass.genericSuperclass as ParameterizedType).actualTypeArguments[0] as Class<T>
        val method = clazz.getMethod("inflate", LayoutInflater::class.java, ViewGroup::class.java, Boolean::class.javaPrimitiveType)
        binding = method.invoke(null, inflater, null, false) as T
        rootView = binding.root
        onViewCreated()
        return rootView!!
    }

    // 子类重写即可访问binding
    protected abstract fun onViewCreated()

    protected open fun createLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }
            flags = (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            format = PixelFormat.TRANSLUCENT
            gravity = config.position.toGravity()
            width = config.width ?: WindowManager.LayoutParams.WRAP_CONTENT
            height = config.height ?: WindowManager.LayoutParams.WRAP_CONTENT
            x = config.x
            y = config.y
        }
    }

    fun show() {
        if (isShowing || !hasOverlayPermission()) {
            println("W: Cannot show: already showing or no overlay permission")
            return
        }
        if (rootView == null) {
            createView()
            layoutParams = createLayoutParams()
        }
        rootView?.let { view ->
            try {
                view.alpha = 0f
                windowManager?.addView(view, layoutParams)
                view.animate().alpha(1f).setDuration(300).start()
                isShowing = true
                onWindowShown()
            } catch (e: SecurityException) {
                println("E: Failed to show window: permission denied - ${e.message}")
                isShowing = false
                onPermissionDenied()
            }
        } ?: println("W: Root view is null, cannot show")
    }

    fun hide() {
        if (!isShowing) return
        rootView?.let { view ->
            view.animate().alpha(0f).setDuration(300).withEndAction {
                try {
                    windowManager?.removeView(view)
                    isShowing = false
                    onWindowHidden()
                } catch (e: Exception) {
                    println("E: Failed to hide window - ${e.message}")
                }
            }.start()
        }
    }

    fun isShowing(): Boolean = isShowing

    fun updatePosition(x: Int, y: Int) {
        layoutParams?.let {
            it.x = x
            it.y = y
            config = config.copy(x = x, y = y)
            if (isShowing) {
                windowManager?.updateViewLayout(rootView, it)
            }
        }
    }

    fun setSize(width: Int?, height: Int?) {
        layoutParams?.let {
            it.width = width ?: WindowManager.LayoutParams.WRAP_CONTENT
            it.height = height ?: WindowManager.LayoutParams.WRAP_CONTENT
            config = config.copy(width = width, height = height)
            if (isShowing) {
                windowManager?.updateViewLayout(rootView, it)
            }
        }
    }

    protected open fun onWindowShown() {}
    protected open fun onWindowHidden() {}
    protected open fun onPermissionDenied() {}

    fun destroy() {
        hide()
        rootView = null
        layoutParams = null
        windowManager = null
        println("D: Floating window destroyed")
    }
}
