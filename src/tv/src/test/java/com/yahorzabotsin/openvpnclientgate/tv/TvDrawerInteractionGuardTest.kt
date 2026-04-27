package com.yahorzabotsin.openvpnclientgate.tv

import android.view.KeyEvent
import androidx.drawerlayout.widget.DrawerLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvDrawerInteractionGuardTest {

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
            isDrawerEngaged = false,
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
            isDrawerEngaged = false,
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
            isDrawerEngaged = true,
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
            isDrawerEngaged = true,
            isFocusInDrawer = true
        )

        assertFalse(consume)
    }

    @Test
    fun shouldConsumeDebouncedOkEvent_onlyAfterCloseWhenDrawerIsNotOpen() {
        val consume = TvDrawerInteractionGuard.shouldConsumeDebouncedOkEvent(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            keyAction = KeyEvent.ACTION_DOWN,
            isCloseDebounceActive = true,
            isDrawerOpen = false,
            isFocusInMainContent = true,
            hasConsumedPostCloseOkUp = false
        )

        assertTrue(consume)
    }

    @Test
    fun shouldConsumeDebouncedOkEventActionUp_onlyAfterCloseWhenDrawerIsNotOpen() {
        val consume = TvDrawerInteractionGuard.shouldConsumeDebouncedOkEvent(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            keyAction = KeyEvent.ACTION_UP,
            isCloseDebounceActive = true,
            isDrawerOpen = false,
            isFocusInMainContent = true,
            hasConsumedPostCloseOkUp = false
        )

        assertTrue(consume)
    }

    @Test
    fun shouldNotConsumeDebouncedOkEvent_whenDrawerIsOpen() {
        val consume = TvDrawerInteractionGuard.shouldConsumeDebouncedOkEvent(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            keyAction = KeyEvent.ACTION_DOWN,
            isCloseDebounceActive = true,
            isDrawerOpen = true,
            isFocusInMainContent = true,
            hasConsumedPostCloseOkUp = false
        )

        assertFalse(consume)
    }

    @Test
    fun shouldNotConsumeDebouncedOkEvent_whenFocusNotInMainContent() {
        val consume = TvDrawerInteractionGuard.shouldConsumeDebouncedOkEvent(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            keyAction = KeyEvent.ACTION_DOWN,
            isCloseDebounceActive = true,
            isDrawerOpen = false,
            isFocusInMainContent = false,
            hasConsumedPostCloseOkUp = false
        )

        assertFalse(consume)
    }

    @Test
    fun shouldNotConsumeDebouncedOkEvent_afterFirstPostCloseOkUpWasConsumed() {
        val consume = TvDrawerInteractionGuard.shouldConsumeDebouncedOkEvent(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            keyAction = KeyEvent.ACTION_UP,
            isCloseDebounceActive = true,
            isDrawerOpen = false,
            isFocusInMainContent = true,
            hasConsumedPostCloseOkUp = true
        )

        assertFalse(consume)
    }

    @Test
    fun shouldArmBurstGuardAfterDebouncedConsume_onOkDown() {
        val shouldArm = TvDrawerInteractionGuard.shouldArmBurstGuardAfterDebouncedConsume(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            keyAction = KeyEvent.ACTION_DOWN
        )

        assertTrue(shouldArm)
    }

    @Test
    fun shouldArmBurstGuardAfterDebouncedConsume_onOkUp() {
        val shouldArm = TvDrawerInteractionGuard.shouldArmBurstGuardAfterDebouncedConsume(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            keyAction = KeyEvent.ACTION_UP
        )

        assertTrue(shouldArm)
    }

    @Test
    fun shouldNotArmBurstGuardAfterDebouncedConsume_onNonOkKey() {
        val shouldArm = TvDrawerInteractionGuard.shouldArmBurstGuardAfterDebouncedConsume(
            keyCode = KeyEvent.KEYCODE_BACK,
            keyAction = KeyEvent.ACTION_UP
        )

        assertFalse(shouldArm)
    }
}
