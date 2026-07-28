package com.smart.android.ad_app

import android.view.ViewGroup
import android.widget.FrameLayout

fun ViewGroup.requireFrameLayout(
    errorMessage: String,
    onError: (() -> Unit)? = null
): FrameLayout? {
    val container = this as? FrameLayout
    if (container == null) {
        errorMessage.adDebugPrintLog()
        onError?.invoke()
    }
    return container
}
