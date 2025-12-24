package com.yahorzabotsin.openvpnclientgate.core.filter

import android.graphics.drawable.Drawable

data class AppFilterEntry(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
    val icon: Drawable?
)

