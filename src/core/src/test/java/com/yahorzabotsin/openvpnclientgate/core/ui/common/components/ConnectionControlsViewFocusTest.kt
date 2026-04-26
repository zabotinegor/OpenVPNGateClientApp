package com.yahorzabotsin.openvpnclientgate.core.ui.common.components

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ConnectionControlsViewFocusTest {

    @Test
    fun resolveFocusTarget_returnsPause_whenTvAndPauseHadFocusAndPauseVisible() {
        val target = ConnectionControlsView.resolveFocusTarget(
            isTvDevice = true,
            pauseHadFocus = true,
            pauseVisible = true
        )

        assertEquals(ConnectionControlsView.FocusTarget.PAUSE, target)
    }

    @Test
    fun resolveFocusTarget_returnsStart_whenTvAndPauseHadFocusAndPauseHidden() {
        val target = ConnectionControlsView.resolveFocusTarget(
            isTvDevice = true,
            pauseHadFocus = true,
            pauseVisible = false
        )

        assertEquals(ConnectionControlsView.FocusTarget.START, target)
    }

    @Test
    fun resolveFocusTarget_returnsNone_whenNotTv() {
        val target = ConnectionControlsView.resolveFocusTarget(
            isTvDevice = false,
            pauseHadFocus = true,
            pauseVisible = true
        )

        assertEquals(ConnectionControlsView.FocusTarget.NONE, target)
    }

    @Test
    fun resolveFocusTarget_returnsNone_whenPauseDidNotHaveFocus() {
        val target = ConnectionControlsView.resolveFocusTarget(
            isTvDevice = true,
            pauseHadFocus = false,
            pauseVisible = false
        )

        assertEquals(ConnectionControlsView.FocusTarget.NONE, target)
    }
}
