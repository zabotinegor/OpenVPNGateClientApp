package com.yahorzabotsin.openvpnclientgate.tv

import android.view.KeyEvent
import androidx.drawerlayout.widget.DrawerLayout

internal object TvDrawerInteractionGuard {
    fun shouldConsumeDebouncedOkEvent(
        keyCode: Int,
        keyAction: Int,
        isCloseDebounceActive: Boolean,
        isDrawerOpen: Boolean
    ): Boolean {
        if (!isCloseDebounceActive || isDrawerOpen) return false

        val isOkKey = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

        val isOkAction = keyAction == KeyEvent.ACTION_DOWN || keyAction == KeyEvent.ACTION_UP

        return isOkKey && isOkAction
    }

    fun shouldBlockMainContent(drawerState: Int, isDrawerOpen: Boolean): Boolean {
        return drawerState != DrawerLayout.STATE_IDLE || isDrawerOpen
    }

    fun shouldRequestDrawerFocus(slideOffset: Float): Boolean {
        return slideOffset > 0f
    }

    fun shouldConsumeOkEvent(
        keyCode: Int,
        keyAction: Int,
        drawerState: Int,
        isDrawerOpen: Boolean,
        isFocusInDrawer: Boolean
    ): Boolean {
        val isOkKey = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        val isOkAction = keyAction == KeyEvent.ACTION_DOWN || keyAction == KeyEvent.ACTION_UP
        if (!isOkKey || !isOkAction) return false

        if (drawerState != DrawerLayout.STATE_IDLE) {
            return true
        }

        return isDrawerOpen && !isFocusInDrawer
    }
}
