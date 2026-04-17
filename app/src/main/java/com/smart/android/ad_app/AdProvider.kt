package com.smart.android.ad_app

import android.content.ContentProvider
import android.content.ContentValues
import android.os.Binder
import android.database.MatrixCursor
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import io.github.lib_autorun.log.printLog

class AdProvider : ContentProvider() {
    private companion object {
        private const val TAG = "AdProvider"
    }

    override fun onCreate(): Boolean {
        Log.i(TAG, "onCreate authority=${context?.packageName}.adprovider")
        "AdProvider onCreate".printLog()
        context?.applicationContext?.let(AdRuntimeCoordinator::start)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        Log.i(TAG, "query uri=$uri pathSegments=${uri.pathSegments}")
        val callerUid = Binder.getCallingUid()
        val shellCaller = callerUid == Process.SHELL_UID || callerUid == Process.ROOT_UID
        val isDebugShowFloating = shellCaller &&
            uri.pathSegments.contains("debug") &&
            uri.lastPathSegment == "showFloating"
        if (isDebugShowFloating) {
            Log.i(TAG, "Debug trigger matched: showFloating callerUid=$callerUid")
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
        return null
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
