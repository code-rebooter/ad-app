package com.smart.android.ad_app.google

import android.content.Context
import com.smart.android.ad_app.Hq008ApiRequestFields

/** Share the existing google_tcl_ad/aid with our backend authorization request. */
internal object GoogleTclUidStore {
    fun readOrCreate(context: Context): String = Hq008ApiRequestFields.readOrCreateRdid(context)
}
