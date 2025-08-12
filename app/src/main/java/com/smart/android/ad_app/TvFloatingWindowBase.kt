package com.smart.android.ad_app

import android.annotation.SuppressLint
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
import java.lang.ref.WeakReference
import java.lang.reflect.ParameterizedType
import java.util.UUID
import java.util.WeakHashMap

/**
 * Android TV专用悬浮窗基类，支持通过高阶函数动态设置宽高、偏移量、位置、焦点行为和ID
 */
abstract class TvFloatingWindowBase<T : ViewBinding>(context: Context) {
    protected val context: Context = context.applicationContext
    protected lateinit var binding: T
    protected var windowManager: WindowManager? = null
    protected var rootView: View? = null
    protected var layoutParams: WindowManager.LayoutParams? = null
    private var isShowing = false
    private var config: WindowConfig = WindowConfig()

    // 数据类用于配置悬浮窗参数
    data class WindowConfig(
        var width: Int? = null,
        var height: Int? = null,
        var x: Int = 0,
        var y: Int = 0,
        var position: Position = Position.RIGHT_BOTTOM,
        var isFocusable: Boolean = false, // 是否允许获取焦点
        var id: String = UUID.randomUUID().toString() // 默认生成随机ID
    )

    init {
        initWindowManager()
        // 将实例添加到静态管理
        synchronized(instances) {
            instances[config.id] = WeakReference(this)
        }
    }

    /**
     * 初始化WindowManager
     */
    private fun initWindowManager() {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: throw IllegalStateException("WindowManager service is not available")
    }

    /**
     * 使用高阶函数配置悬浮窗参数
     */
    fun configure(configBlock: WindowConfig.() -> Unit): TvFloatingWindowBase<T> {
        val oldId = config.id
        config = WindowConfig().apply(configBlock)
        // 如果ID发生变化，更新静态管理
        if (config.id != oldId) {
            synchronized(instances) {
                instances.remove(oldId)
                instances[config.id] = WeakReference(this)
            }
        }
        layoutParams?.let {
            it.width = config.width?.getServerDimenPx() ?: WindowManager.LayoutParams.WRAP_CONTENT
            it.height = config.height?.getServerDimenPx() ?: WindowManager.LayoutParams.WRAP_CONTENT
            it.x = config.x.getServerDimenPx()
            it.y = config.y.getServerDimenPx()
            it.gravity = config.position.toGravity()
            it.flags = if (config.isFocusable) {
                (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            } else {
                (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            }
            if (isShowing) {
                windowManager?.updateViewLayout(rootView, it)
            }
        }
        rootView?.let {
            it.isFocusable = config.isFocusable
            it.isFocusableInTouchMode = config.isFocusable
            // 不强制设置子视图的焦点属性，保留XML或AdManagerImpl设置
            if (config.isFocusable) {
                setupFocusHandling()
            }
        }
        return this
    }

    /**
     * 将Position枚举转换为Gravity值
     */
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

    /**
     * 检查悬浮窗权限
     */
    fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * 请求悬浮窗权限
     */
    fun requestOverlayPermission(requestCode: Int, activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !hasOverlayPermission()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            activity.startActivityForResult(intent, requestCode)
        }
    }

    /**
     * 显示悬浮窗，带淡入动画
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (isShowing || !hasOverlayPermission()) {
            println("W: Cannot show: already showing or no overlay permission")
            return
        }

        if (rootView == null) {
            createView()
            layoutParams = createLayoutParams()
            if (config.isFocusable) {
                setupFocusHandling()
            }
        }

        rootView?.let { view ->
            try {
                view.alpha = 0f
                windowManager?.addView(view, layoutParams)
                view.animate().alpha(1f).setDuration(300).start()
                isShowing = true
                if (config.isFocusable) {
                    view.requestFocus() // 触发系统选择默认焦点视图
                }
                onWindowShown()
            } catch (e: SecurityException) {
                println("E: Failed to show window: permission denied - ${e.message}")
                isShowing = false
                onPermissionDenied()
            }
        } ?: println("W: Root view is null, cannot show")
    }

    /**
     * 隐藏悬浮窗，带淡出动画
     */
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

    /**
     * 是否正在显示
     */
    fun isShowing(): Boolean = isShowing

    /**
     * 创建悬浮窗的视图
     */
    @Suppress("UNCHECKED_CAST")
    protected open fun createView(): View {
        val inflater = LayoutInflater.from(context).cloneInContext(context)
        val clazz = (javaClass.genericSuperclass as ParameterizedType).actualTypeArguments[0] as Class<T>
        val method = clazz.getMethod("inflate", LayoutInflater::class.java, ViewGroup::class.java, Boolean::class.javaPrimitiveType)
        binding = method.invoke(null, inflater, null, false) as T
        rootView = binding.root
        rootView?.isFocusable = config.isFocusable
        rootView?.isFocusableInTouchMode = config.isFocusable
        // 不强制设置子视图的焦点属性，保留XML或AdManagerImpl设置
        onViewCreated()
        return rootView!!
    }

    /**
     * 设置焦点管理，仅处理KEYCODE_BACK（仅当isFocusable为true时调用）
     */
    private fun setupFocusHandling() {
        rootView?.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                onBackPressed() // 交给子类处理
            } else {
                false
            }
        }
    }

    /**
     * 创建WindowManager的LayoutParams，使用configure设置的参数
     */
    protected open fun createLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }
            flags = if (config.isFocusable) {
                println("设置了获取焦点")
                (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            } else {
                println("设置了不获取焦点")
                (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            }
            format = PixelFormat.TRANSLUCENT
            gravity = config.position.toGravity()
            width = config.width?.getServerDimenPx() ?: WindowManager.LayoutParams.WRAP_CONTENT
            height = config.height?.getServerDimenPx() ?: WindowManager.LayoutParams.WRAP_CONTENT
            x = config.x.getServerDimenPx()
            y = config.y.getServerDimenPx()
        }
    }

    /**
     * 更新悬浮窗位置
     */
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

    /**
     * 动态设置悬浮窗宽高
     */
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


    /**
     * 动态设置悬浮窗是否可获取焦点
     * @param isFocusable 是否允许获取焦点
     */
    fun setFocusable(isFocusable: Boolean) {
        if (config.isFocusable != isFocusable) {
            config = config.copy(isFocusable = isFocusable)
            layoutParams?.let {
                it.flags = if (isFocusable) {
                    (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                } else {
                    (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                }
                if (isShowing) {
                    windowManager?.updateViewLayout(rootView, it)
                }
            }
            rootView?.let {
                it.isFocusable = isFocusable
                it.isFocusableInTouchMode = isFocusable
                if (isFocusable) {
                    setupFocusHandling() // 设置焦点监听（处理KEYCODE_BACK）
                    it.requestFocus() // 尝试获取焦点
                } else {
                    it.setOnKeyListener(null) // 移除焦点监听
                }
            }
        }
    }

    /**
     * 检查是否允许动态设置焦点
     * @return 初始配置的isFocusable值
     */
    fun canSetFocusable(): Boolean {
        return config.isFocusable
    }

    /**
     * 子类重写即可访问binding
     */
    protected abstract fun onViewCreated()

    /**
     * 遥控器返回键处理，由子类实现
     */
    protected open fun onBackPressed(): Boolean = false

    /**
     * 悬浮窗显示时的回调
     */
    protected open fun onWindowShown() {}

    /**
     * 悬浮窗隐藏时的回调
     */
    protected abstract fun onWindowHidden()

    /**
     * 悬浮窗销毁时的回调
     */
    protected open fun onWindowDestroyed() {}

    /**
     * 权限被拒绝时的回调
     */
    protected open fun onPermissionDenied() {}

    /**
     * 销毁悬浮窗
     */
    fun destroy() {
        hide()
        rootView = null
        layoutParams = null
        windowManager = null
        synchronized(instances) {
            instances.remove(config.id)
        }
        onWindowDestroyed()
        println("D: Floating window with id=${config.id} destroyed")
    }

    companion object {
        private val instances = WeakHashMap<String, WeakReference<TvFloatingWindowBase<*>>>()

        /**
         * 关闭所有悬浮窗
         */
        fun closeAll() {
            synchronized(instances) {
                instances.values.toList().forEach { ref ->
                    ref.get()?.destroy()
                }
                instances.clear()
            }
            println("D: All floating windows closed")
        }

        /**
         * 根据ID关闭指定的悬浮窗
         */
        fun closeById(id: String) {
            synchronized(instances) {
                instances[id]?.get()?.destroy()
                instances.remove(id)
            }
            println("D: Floating window with id=$id closed")
        }
    }
}