package com.yahorzabotsin.openvpnclientgate.core.ui.common.utils

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

    /**
     * Handles a FocusFirstItem effect for a server/country list screen. This is a TV/D-pad
     * concern only: on touch devices both the scroll and the focus request are skipped
     * entirely, because scrollToPosition(1) on open would hide the pinned Favorites section
     * header at position 0 (DEF-sub03-header-misscroll-on-open in CountryServersActivity,
     * DEF-sub05-serverlist-header-misscroll-on-open in ServerListActivity) and touch users
     * don't need item-level focus. Extracted as a shared testable seam used by both
     * activities.
     */
    fun applyFocusFirstItem(
        isTvDevice: Boolean,
        position: Int,
        scrollToPosition: (Int) -> Unit,
        focusWhenReady: (Int) -> Unit
    ) {
        // position < 0 covers RecyclerView.NO_POSITION (-1) without pulling a
        // RecyclerView dependency into this utility.
        if (!isTvDevice || position < 0) {
            return
        }
        // Scroll first: findViewHolderForAdapterPosition returns null for a position
        // RecyclerView hasn't bound yet because it's off-screen.
        scrollToPosition(position)
        focusWhenReady(position)
    }
}


