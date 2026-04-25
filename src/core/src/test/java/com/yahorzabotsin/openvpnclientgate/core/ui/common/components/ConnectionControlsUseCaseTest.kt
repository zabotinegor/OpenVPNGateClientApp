package com.yahorzabotsin.openvpnclientgate.core.ui.common.components

import com.yahorzabotsin.openvpnclientgate.core.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionControlsUseCaseTest {

    private val useCase = ConnectionControlsUseCase()

    @Test
    fun `trimEllipsis removes ascii dots`() {
        assertEquals("Connecting", useCase.trimEllipsis("Connecting..."))
    }

    @Test
    fun `trimEllipsis removes unicode ellipsis`() {
        assertEquals("Connecting", useCase.trimEllipsis("Connecting\u2026"))
    }

    @Test
    fun `formatBytes formats gigabytes`() {
        val value = 3L * 1024L * 1024L * 1024L
        assertEquals("3.00 GB", useCase.formatBytes(value))
    }

    @Test
    fun `mapEngineDetailToResId maps transitional states`() {
        assertEquals(R.string.state_tcp_connect, useCase.mapEngineDetailToResId("TCP_CONNECT"))
        assertEquals(R.string.state_auth, useCase.mapEngineDetailToResId("AUTH"))
        assertEquals(R.string.state_assign_ip, useCase.mapEngineDetailToResId("ASSIGN_IP"))
        assertEquals(R.string.state_userpause, useCase.mapEngineDetailToResId("USERPAUSE"))
        assertEquals(R.string.state_screenoff, useCase.mapEngineDetailToResId("SCREENOFF"))
        assertEquals(R.string.state_nonetwork, useCase.mapEngineDetailToResId("NONETWORK"))
    }
}
