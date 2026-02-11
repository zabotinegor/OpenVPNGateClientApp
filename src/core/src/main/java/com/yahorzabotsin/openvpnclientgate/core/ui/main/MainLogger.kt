package com.yahorzabotsin.openvpnclientgate.core.ui.main

import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog

interface MainLogger {
    fun logInitialSelectionLoaded(selection: InitialSelection)
    fun logInitialSelectionError(error: Exception)
    fun logServerSelectionApplied(selection: SelectedServerResult)
    fun logIncompleteServerSelection(selection: SelectedServerResult)
}

class DefaultMainLogger : MainLogger {
    private val tag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "MainViewModel"

    override fun logInitialSelectionLoaded(selection: InitialSelection) {
        AppLog.i(
            tag,
            "Initial selection loaded: ${selection.country}, ${selection.city}, ip=${selection.ip ?: "<none>"}"
        )
    }

    override fun logInitialSelectionError(error: Exception) {
        AppLog.e(tag, "Failed to initialize selection", error)
    }

    override fun logServerSelectionApplied(selection: SelectedServerResult) {
        AppLog.i(
            tag,
            "Server selected: ${selection.country}, ${selection.city}, ip=${selection.ip ?: "<none>"}"
        )
    }

    override fun logIncompleteServerSelection(selection: SelectedServerResult) {
        AppLog.w(
            tag,
            "Server selection returned with incomplete data: country=${selection.country}, city=${selection.city}"
        )
    }
}

