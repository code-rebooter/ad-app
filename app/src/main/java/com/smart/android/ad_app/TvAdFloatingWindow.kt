package com.smart.android.ad_app

import android.content.Context
import com.smart.android.ad_app.databinding.FloatAdBinding

class TvAdFloatingWindow(context: Context) : TvFloatingWindowBase<FloatAdBinding>(context) {

    override fun onViewCreated() {
        AdManagerImpl.showAd(binding.flAdcontainer)
    }

}