package com.yahorzabotsin.openvpnclientgate.tv

import android.view.KeyEvent
import androidx.drawerlayout.widget.DrawerLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvDrawerInteractionGuardTest {

    @Test
    fun isOkKey_returnsTrueForSupportedOkKeys() {
        assertTrue(TvDrawerInteractionGuard.isOkKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(TvDrawerInteractionGuard.isOkKey(KeyEvent.KEYCODE_ENTER))
        assertTrue(TvDrawerInteractionGuard.isOkKey(KeyEvent.KEYCODE_NUMPAD_ENTER))
    }

    @Test
    fun isOkKey_returnsFalseForNonOkKeys() {
        assertFalse(TvDrawerInteractionGuard.isOkKey(KeyEvent.KEYCODE_DPAD_LEFT))
    }

    @Test
    fun shouldBlockMainContent_whenDrawerIsOpening() {
        val blocked = TvDrawerInteractionGuard.shouldBlockMainContent(
            drawerState = DrawerLayout.STATE_SETTLING,
            isDrawerOpen = false
        )

        assertTrue(blocked)
    }

    @Test
    fun shouldBlockMainContent_whenDrawerIsOpenAndIdle() {
        val blocked = TvDrawerInteractionGuard.shouldBlockMainContent(
            drawerState = DrawerLayout.STATE_IDLE,
            isDrawerOpen = true
        )

        assertTrue(blocked)
    }

    @Test
    fun shouldNotBlockMainContent_whenDrawerIsClosedAndIdle() {
        val blocked = TvDrawerInteractionGuard.shouldBlockMainContent(
            drawerState = DrawerLayout.STATE_IDLE,
            isDrawerOpen = false
        )

        assertFalse(blocked)
    }

    @Test
    fun shouldRequestDrawerFocus_onlyWhenSlideOffsetPositive() {
        assertFalse(TvDrawerInteractionGuard.shouldRequestDrawerFocus(0f))
        assertTrue(TvDrawerInteractionGuard.shouldRequestDrawerFocus(0.1f))
    }

    @Test
    fun shouldConsumeOkEvent_whenDrawerInTransition() {
        val consume = TvDrawerInteractionGuard.shouldConsumeOkEvent(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            keyAction = KeyEvent.ACTION_DOWN,
            drawerState = DrawerLayout.STATE_SETTLING,
            isDrawerOpen = false,
            isFocusInDrawer = false
        )

        assertTrue(consume)
    }

    @Test
    fun shouldConsumeOkEventActionUp_whenDrawerInTransition() {
        val consume = TvDrawerInteractionGuard.shouldConsumeOkEvent(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            keyAction = KeyEvent.ACTION_UP,
            drawerState = DrawerLayout.STATE_SETTLING,
            isDrawerOpen = false,
            isFocusInDrawer = false
        )

        assertTrue(consume)
    }

    @Test
    fun shouldConsumeOkEvent_whenNumpadEnterAndDrawerInTransition() {
        val consume = TvDrawerInteractionGuard.shouldConsumeOkEvent(
            keyCode = KeyEvent.KEYCODE_NUMPAD_ENTER,
            keyAction = KeyEvent.ACTION_DOWN,
            drawerState = DrawerLayout.STATE_SETTLING,
            isDrawerOpen = false,
            isFocusInDrawer = false
        )

        assertTrue(consume)
    }

    @Test
    fun shouldConsumeOkEvent_whenDrawerOpenAndFocusOutsideDrawer() {
        val consume = TvDrawerInteractionGuard.shouldConsumeOkEvent(
            keyCode = KeyEvent.KEYCODE_ENTER,
            keyAction = KeyEvent.ACTION_DOWN,
            drawerState = DrawerLayout.STATE_IDLE,
            isDrawerOpen = true,
            isFocusInDrawer = false
        )

        assertTrue(consume)
    }

    @Test
    fun shouldNotConsumeOkEvent_whenDrawerOpenAndFocusInDrawer() {
        val consume = TvDrawerInteractionGuard.shouldConsumeOkEvent(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            keyAction = KeyEvent.ACTION_DOWN,
            drawerState = DrawerLayout.STATE_IDLE,
            isDrawerOpen = true,
            isFocusInDrawer = true
        )

        assertFalse(consume)
    }

    @Test
    fun shouldNotConsumeOkEvent_whenKeyIsNotOk() {
        val consume = TvDrawerInteractionGuard.shouldConsumeOkEvent(
            keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
            keyAction = KeyEvent.ACTION_DOWN,
            drawerState = DrawerLayout.STATE_SETTLING,
            isDrawerOpen = true,
            isFocusInDrawer = false
        )

        assertFalse(consume)
    }

    @Test
    fun shouldNotConsumeOkEvent_whenActionIsNotDownOrUp() {
        val consume = TvDrawerInteractionGuard.shouldConsumeOkEvent(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            keyAction = KeyEvent.ACTION_MULTIPLE,
            drawerState = DrawerLayout.STATE_SETTLING,
            isDrawerOpen = true,
            isFocusInDrawer = false
        )

        assertFalse(consume)
    }

    @Test
    fun shouldConsumeDebouncedOkEvent_onlyAfterCloseWhenDrawerIsNotOpen() {
        val consume = TvDrawerInteractionGuard.shouldConsumeDebouncedOkEvent(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            keyAction = KeyEvent.ACTION_DOWN,
            isCloseDebounceActive = true,
            isDrawerOpen = false
        )

        assertTrue(consume)
    }

    @Test
    fun shouldConsumeDebouncedOkEventActionUp_onlyAfterCloseWhenDrawerIsNotOpen() {
        val consume = TvDrawerInteractionGuard.shouldConsumeDebouncedOkEvent(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            keyAction = KeyEvent.ACTION_UP,
            isCloseDebounceActive = true,
            isDrawerOpen = false
        )

        assertTrue(consume)
    }

    @Test
    fun shouldConsumeDebouncedOkEvent_whenNumpadEnterAfterClose() {
        val consume = TvDrawerInteractionGuard.shouldConsumeDebouncedOkEvent(
            keyCode = KeyEvent.KEYCODE_NUMPAD_ENTER,
            keyAction = KeyEvent.ACTION_DOWN,
            isCloseDebounceActive = true,
            isDrawerOpen = false
        )

        assertTrue(consume)
    }

    @Test
    fun shouldNotConsumeDebouncedOkEvent_whenDrawerIsOpen() {
        val consume = TvDrawerInteractionGuard.shouldConsumeDebouncedOkEvent(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            keyAction = KeyEvent.ACTION_DOWN,
            isCloseDebounceActive = true,
            isDrawerOpen = true
        )

        assertFalse(consume)
    }

    @Test
    fun shouldNotConsumeDebouncedOkEvent_whenDebounceIsNotActive() {
        val consume = TvDrawerInteractionGuard.shouldConsumeDebouncedOkEvent(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            keyAction = KeyEvent.ACTION_DOWN,
            isCloseDebounceActive = false,
            isDrawerOpen = false
        )

        assertFalse(consume)
    }

    @Test
    fun shouldNotConsumeDebouncedOkEvent_whenKeyIsNotOk() {
        val consume = TvDrawerInteractionGuard.shouldConsumeDebouncedOkEvent(
            keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
            keyAction = KeyEvent.ACTION_DOWN,
            isCloseDebounceActive = true,
            isDrawerOpen = false
        )

        assertFalse(consume)
    }
}
