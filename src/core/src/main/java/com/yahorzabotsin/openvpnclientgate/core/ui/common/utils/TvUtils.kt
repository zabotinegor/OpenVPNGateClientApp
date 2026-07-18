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
     * entirely, because scrolling on open would hide the pinned Favorites section header at
     * position 0 (DEF-sub03-header-misscroll-on-open in CountryServersActivity,
     * DEF-sub05-serverlist-header-misscroll-on-open in ServerListActivity) and touch users
     * don't need item-level focus. Extracted as a shared testable seam used by both
     * activities.
     *
     * The scroll target and the focus target are intentionally decoupled
     * (DEF-4-tv-list-misscroll-on-open): the list always scrolls to position 0 first so the
     * pinned Favorites header (or, when there is no header, the first row itself) stays fully
     * visible at the top on initial load, while D-pad focus still lands on [position] (1 when
     * a header exists, so focus reaches the first row; 0 when there is no header, so scroll
     * and focus target the same row). Position 0 and 1 are adjacent, so RecyclerView has
     * already bound/laid out position 1 by the time focusWhenReady runs after scrolling to 0.
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
        // Scroll to the top first so the pinned Favorites header (position 0) stays visible:
        // scrolling directly to `position` (1 when a header exists) top-aligns the first row
        // and pushes the header out of view. findViewHolderForAdapterPosition still resolves
        // `position` afterward because it is adjacent to 0 and gets bound/laid out as part of
        // the same scroll.
        scrollToPosition(0)
        focusWhenReady(position)
    }
}


