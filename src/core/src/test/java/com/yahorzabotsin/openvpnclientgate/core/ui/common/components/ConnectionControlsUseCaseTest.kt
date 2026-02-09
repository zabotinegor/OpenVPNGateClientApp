package com.yahorzabotsin.openvpnclientgate.core.ui.common.components

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
}
