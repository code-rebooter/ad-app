package com.smart.android.ad_app

import android.app.Application

/**
 * 作者　: hegaojian
 * 时间　: 2022/1/13
 * 描述　:
 */

/**
 * 全局上下文，可直接拿
 */
val appContext: Application by lazy { MvvmHelper.app }

object MvvmHelper {

    lateinit var app: Application

    /**
     * 框架初始化
     * @param application Application 全局上下文
     * @param debug Boolean  true为debug模式，会打印Log日志 false 关闭Log日志
     */
    fun init(application: Application) {
        app = application

    }
}