package com.yahorzabotsin.openvpnclientgate.core.reviewbot

import org.junit.Assert.assertEquals
import org.junit.Test

class CanaryTokenNormalizerTest {
    @Test
    fun normalizes_case_and_surrounding_whitespace() {
        assertEquals("dr-probe", normalizeCanaryToken("  DR-PROBE  "))
    }
}