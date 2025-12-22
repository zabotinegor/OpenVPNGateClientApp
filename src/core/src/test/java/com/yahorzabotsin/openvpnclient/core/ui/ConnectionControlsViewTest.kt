package com.yahorzabotsin.openvpnclient.core.ui

import com.yahorzabotsin.openvpnclient.vpn.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionControlsViewTest {

    @Test
    fun stopOnUserSelectionWhenConnectedOrConnecting() {
        assertEquals(
            true,
            ConnectionControlsView.shouldStopForUserSelection(
                ConnectionState.CONNECTED,
                "config1",
                "config2"
            )
        )
        assertEquals(
            true,
            ConnectionControlsView.shouldStopForUserSelection(
                ConnectionState.CONNECTING,
                "config1",
                "config2"
            )
        )
    }

    @Test
    fun noStopWhenDisconnectedOrSameConfig() {
        assertEquals(
            false,
            ConnectionControlsView.shouldStopForUserSelection(
                ConnectionState.DISCONNECTED,
                "config1",
                "config2"
            )
        )
        assertEquals(
            false,
            ConnectionControlsView.shouldStopForUserSelection(
                ConnectionState.DISCONNECTING,
                "config1",
                "config2"
            )
        )
        assertEquals(
            false,
            ConnectionControlsView.shouldStopForUserSelection(
                ConnectionState.CONNECTED,
                "config1",
                "config1"
            )
        )
    }
}
