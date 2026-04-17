package com.smart.android.ad_app

import android.content.Context

fun Int.getServerDimenPx(): Int {
    // 1. 适配系统常量：如果是 MATCH_PARENT (-1) 或 WRAP_CONTENT (-2)，直接返回原值    // 不要去拼接字符串寻找资源，因为系统 LayoutParams 识别的就是 -1 和 -2
    if (this == android.view.ViewGroup.LayoutParams.MATCH_PARENT ||
        this == android.view.ViewGroup.LayoutParams.WRAP_CONTENT) {
        return this
    }

    if (this < 0) {
        return this
    }

    // 2. 正常逻辑：拼出资源名
    val resName = "qb_px_${this}"

    // 3. 找到资源 ID
    val resId = getDimenResourceId(appContext, resName)

    return if (resId != 0) {
        appContext.resources.getDimensionPixelSize(resId)
    } else {
        // 如果找不到资源，为了防止崩溃，可以考虑返回原值(this)或者抛出异常
        // 建议增加一个 log，方便排查缺失的 dimen
        throw IllegalArgumentException("Dimen resource not found for: $resName")
    }
}


private fun getDimenResourceId(context: Context, dimenName: String): Int {
    return context.resources.getIdentifier(
        dimenName,  // 资源名称（如 "qb_400"）
        "dimen",     // 资源类型（dimen）
        context.packageName
    )
}
