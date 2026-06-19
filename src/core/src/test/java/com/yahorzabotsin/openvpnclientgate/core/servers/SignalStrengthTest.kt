package com.yahorzabotsin.openvpnclientgate.core.servers

import org.junit.Assert.assertEquals
import org.junit.Test

class SignalStrengthTest {

    @Test
    fun ping_0_is_strong() = assertEquals(SignalStrength.STRONG, 0.toSignalStrength())

    @Test
    fun ping_50_is_strong() = assertEquals(SignalStrength.STRONG, 50.toSignalStrength())

    @Test
    fun ping_99_is_strong() = assertEquals(SignalStrength.STRONG, 99.toSignalStrength())

    @Test
    fun ping_100_is_medium() = assertEquals(SignalStrength.MEDIUM, 100.toSignalStrength())

    @Test
    fun ping_249_is_medium() = assertEquals(SignalStrength.MEDIUM, 249.toSignalStrength())

    @Test
    fun ping_250_is_weak() = assertEquals(SignalStrength.WEAK, 250.toSignalStrength())

    @Test
    fun ping_999_is_weak() = assertEquals(SignalStrength.WEAK, 999.toSignalStrength())
}
