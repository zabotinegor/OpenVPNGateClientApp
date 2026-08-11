package com.yahorzabotsin.openvpnclientgate.core.ui.common.components

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yahorzabotsin.openvpnclientgate.core.logging.launchLogged
import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionStateManager

/**
 * Placeholder - renders nothing. The gauge is being rebuilt from scratch; the public API
 * (setSpeedMbps / setMaxMbps / bindTo) is kept so MainActivityCore and the mobile/tv layouts
 * keep compiling and receiving speed updates while the new drawing code is written.
 */
class SpeedometerView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private companion object {
        val TAG = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "SpeedometerView"
    }

    private var currentMbps: Float = 0f
    private var maxMbps: Float = 100f

    fun setSpeedMbps(value: Double) {
        currentMbps = if (value.isFinite() && value >= 0) value.toFloat() else 0f
    }

    fun setMaxMbps(max: Float) {
        maxMbps = if (max > 0f) max else 100f
    }

    fun bindTo(lifecycleOwner: LifecycleOwner) {
        lifecycleOwner.launchLogged(TAG) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConnectionStateManager.speedMbps.collect { setSpeedMbps(it) }
            }
        }
    }
}
