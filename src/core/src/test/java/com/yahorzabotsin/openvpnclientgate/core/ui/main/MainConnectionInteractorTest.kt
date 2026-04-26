package com.yahorzabotsin.openvpnclientgate.core.ui.main

import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MainConnectionInteractorTest {

    private val interactor = DefaultMainConnectionInteractor(RuntimeEnvironment.getApplication())

    @Test
    fun shouldStopForUserSelection_returnsTrueWhenPausedAndIpChanged() {
        val shouldStop = interactor.shouldStopForUserSelection(
            state = ConnectionState.PAUSED,
            previousConfig = "cfg",
            newConfig = "cfg",
            previousIp = "1.1.1.1",
            newIp = "2.2.2.2"
        )

        assertTrue(shouldStop)
    }

    @Test
    fun shouldStopForUserSelection_returnsTrueWhenPausedAndConfigChanged() {
        val shouldStop = interactor.shouldStopForUserSelection(
            state = ConnectionState.PAUSED,
            previousConfig = "cfg1",
            newConfig = "cfg2",
            previousIp = "1.1.1.1",
            newIp = "1.1.1.1"
        )

        assertTrue(shouldStop)
    }

    @Test
    fun shouldStopForUserSelection_returnsFalseWhenDisconnected() {
        val shouldStop = interactor.shouldStopForUserSelection(
            state = ConnectionState.DISCONNECTED,
            previousConfig = "cfg1",
            newConfig = "cfg2",
            previousIp = "1.1.1.1",
            newIp = "2.2.2.2"
        )

        assertFalse(shouldStop)
    }

    @Test
    fun shouldStopForUserSelection_returnsFalseWhenNoChangesWhileConnected() {
        val shouldStop = interactor.shouldStopForUserSelection(
            state = ConnectionState.CONNECTED,
            previousConfig = "cfg1",
            newConfig = "cfg1",
            previousIp = "1.1.1.1",
            newIp = "1.1.1.1"
        )

        assertFalse(shouldStop)
    }
}
