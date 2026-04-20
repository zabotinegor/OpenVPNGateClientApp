package com.yahorzabotsin.openvpnclientgate.core.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedCountryVersionSignalTest {

    @Test
    fun `bump increases version by one`() {
        val before = SelectedCountryVersionSignal.version.value

        SelectedCountryVersionSignal.bump()

        val after = SelectedCountryVersionSignal.version.value
        assertTrue(after > before)
        assertEquals(before + 1L, after)
    }
}
