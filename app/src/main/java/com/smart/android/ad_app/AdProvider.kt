package com.smart.android.ad_app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.os.Binder
import android.database.MatrixCursor
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.speed.log.printLog

class AdProvider : ContentProvider() {
    private companion object {
        private const val TAG = "AdProvider"
    }

    override fun onCreate(): Boolean {
        Log.i(TAG, "正式链路：AdProvider 已创建，authority=${context?.packageName}.adprovider，开始初始化 CMP 与广告调度器")
        "AdProvider onCreate".printLog()
        context?.applicationContext?.let {
            Hq008CmpManager.init(it)
            AdRuntimeCoordinator.start(it)
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        Log.i(TAG, "正式链路：AdProvider 收到查询，uri=$uri，pathSegments=${uri.pathSegments}")
        val callerUid = Binder.getCallingUid()
        val shellCaller = callerUid == Process.SHELL_UID || callerUid == Process.ROOT_UID
        val isDebugShowFloating = shellCaller &&
            uri.pathSegments.contains("debug") &&
            uri.lastPathSegment == "showFloating"
        val isDebugRequestFloating = shellCaller &&
            uri.pathSegments.contains("debug") &&
            uri.lastPathSegment == "requestFloating"
        val isDebugShowCmp = shellCaller &&
            uri.pathSegments.contains("debug") &&
            uri.lastPathSegment == "showCmp"
        val isDebugCmpAction = shellCaller &&
            uri.pathSegments.contains("debug") &&
            uri.pathSegments.contains("cmpAction")
        if (isDebugShowFloating) {
            Log.i(TAG, "调试链路：命中 showFloating 触发，callerUid=$callerUid，准备展示调试广告窗口")
            Handler(Looper.getMainLooper()).post {
                AdRenderer.showFloatingAd(
                    com.smart.android.ad_app.bean.AdConfigDto(
                        adId = "debug_hq008",
                        adType = AdType.FLOATING.value,
                        adUrl = null,
                        contentType = null,
                        displayDuration = 15000,
                        floatingHeight = 180,
                        floatingWidth = 320,
                        floatingX = 0,
                        floatingY = 0,
                        imageUrl = null,
                        isClosable = 1,
                        isCountdownVisible = false,
                        position = 0,
                        videoUrl = null
                    )
                )
            }
            return MatrixCursor(arrayOf("triggered")).apply {
                addRow(arrayOf<Any>(1))
            }
        }
        if (isDebugRequestFloating) {
            Log.i(TAG, "调试链路：命中 requestFloating 触发，callerUid=$callerUid，准备执行完整悬浮广告请求链路")
            Handler(Looper.getMainLooper()).post {
                context?.startActivity(
                    Intent(context, Hq008FloatingDebugActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
            return MatrixCursor(arrayOf("triggered")).apply {
                addRow(arrayOf<Any>(1))
            }
        }
        if (isDebugShowCmp) {
            Log.i(TAG, "调试链路：命中 showCmp 触发，callerUid=$callerUid，准备拉起 CMP 调试页面")
            Handler(Looper.getMainLooper()).post {
                context?.startActivity(
                    Intent(context, Hq008CmpDebugActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
            return MatrixCursor(arrayOf("triggered")).apply {
                addRow(arrayOf<Any>(1))
            }
        }
        if (isDebugCmpAction) {
            val action = uri.lastPathSegment.orEmpty()
            Log.i(TAG, "调试链路：命中 cmpAction 触发，callerUid=$callerUid，action=$action")
            Handler(Looper.getMainLooper()).post {
                context?.applicationContext?.let { appContext ->
                    Hq008CmpManager.debugRunReflectiveSdkAction(appContext, action) { result ->
                        Log.i(TAG, "调试链路：cmpAction 执行结束，action=$action，result=$result")
                    }
                }
            }
            return MatrixCursor(arrayOf("triggered", "action")).apply {
                addRow(arrayOf<Any>(1, action))
            }
        }
        return MatrixCursor(arrayOf("result"))
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0
}
