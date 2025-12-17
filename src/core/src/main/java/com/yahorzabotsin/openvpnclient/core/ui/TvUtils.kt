package com.yahorzabotsin.openvpnclient.core.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.view.View

object TvUtils {
    fun isTvDevice(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    fun requestTvFocus(context: Context, view: View?) {
        if (isTvDevice(context)) {
            view?.requestFocus()
        }
    }
}
