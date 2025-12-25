package com.yahorzabotsin.openvpnclientgate.core.ui

import android.content.res.Resources
import kotlin.math.roundToInt

internal object UiUtils {
    fun dpToPx(dp: Int, resources: Resources): Int =
        (dp * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
}

