package com.yahorzabotsin.openvpnclientgate.tv

import android.view.KeyEvent
import androidx.drawerlayout.widget.DrawerLayout

internal object TvDrawerInteractionGuard {
    fun isOkKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
    }

    fun shouldConsumeDebouncedOkEvent(
        keyCode: Int,
        keyAction: Int,
        isCloseDebounceActive: Boolean,
        isDrawerOpen: Boolean,
        isFocusInMainContent: Boolean,
        hasConsumedPostCloseOkUp: Boolean
    ): Boolean {
        if (!isCloseDebounceActive || isDrawerOpen || !isFocusInMainContent || hasConsumedPostCloseOkUp) {
            return false
        }

        val isOkKey = isOkKey(keyCode)

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
        isDrawerEngaged: Boolean,
        isFocusInDrawer: Boolean
    ): Boolean {
        val isOkKey = isOkKey(keyCode)
        val isOkAction = keyAction == KeyEvent.ACTION_DOWN || keyAction == KeyEvent.ACTION_UP
        if (!isOkKey || !isOkAction) return false

        if (drawerState != DrawerLayout.STATE_IDLE) {
            return true
        }

        return isDrawerEngaged && !isFocusInDrawer
    }
}
