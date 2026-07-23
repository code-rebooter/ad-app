package com.smart.android.ad_app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.database.Cursor
import android.net.Uri
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
