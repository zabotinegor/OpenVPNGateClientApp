package com.yahorzabotsin.openvpnclientgate.core.ui.filter

import android.util.Log
import com.yahorzabotsin.openvpnclientgate.core.logging.LogTags
import com.yahorzabotsin.openvpnclientgate.core.ui.AppCategory

interface FilterLogger {
    fun logScreenOpened(userCount: Int, systemCount: Int, excludedCount: Int)
    fun logSelectAll(category: AppCategory, isChecked: Boolean, affectedCount: Int)
    fun logToggle(packageName: String, isEnabled: Boolean)
}

class DefaultFilterLogger : FilterLogger {
    private val tag = LogTags.APP + ':' + "FilterActivity"

    override fun logScreenOpened(userCount: Int, systemCount: Int, excludedCount: Int) {
        Log.i(tag, "Filter screen opened: user=$userCount, system=$systemCount, excluded=$excludedCount")
    }

    override fun logSelectAll(category: AppCategory, isChecked: Boolean, affectedCount: Int) {
        Log.i(tag, "Filter select all: category=${category.name}, checked=$isChecked, affected=$affectedCount")
    }

    override fun logToggle(packageName: String, isEnabled: Boolean) {
        Log.i(tag, "Filter toggle: package=$packageName, enabled=$isEnabled")
    }
}
