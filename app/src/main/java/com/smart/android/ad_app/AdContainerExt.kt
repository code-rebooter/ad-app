package com.smart.android.ad_app

import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.lib_autorun.log.printLog

fun ViewGroup.requireFrameLayout(
    errorMessage: String,
    onError: (() -> Unit)? = null
): FrameLayout? {
    val container = this as? FrameLayout
    if (container == null) {
        errorMessage.printLog()
        onError?.invoke()
    }
    return container
}
