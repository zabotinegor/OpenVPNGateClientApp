package com.yahorzabotsin.openvpnclientgate.core.ui

import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.ConnectionControlsUseCase
import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionControlsViewTest {
    private val useCase = ConnectionControlsUseCase()

    @Test
    fun stopOnUserSelectionWhenConnectedOrConnecting() {
        assertEquals(
            true,
            useCase.shouldStopForUserSelection(
                ConnectionState.CONNECTED,
                "config1",
                "config2"
            )
        )
        assertEquals(
            true,
            useCase.shouldStopForUserSelection(
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
            useCase.shouldStopForUserSelection(
                ConnectionState.DISCONNECTED,
                "config1",
                "config2"
            )
        )
        assertEquals(
            false,
            useCase.shouldStopForUserSelection(
                ConnectionState.DISCONNECTING,
                "config1",
                "config2"
            )
        )
        assertEquals(
            false,
            useCase.shouldStopForUserSelection(
                ConnectionState.CONNECTED,
                "config1",
                "config1"
            )
        )
    }
}

