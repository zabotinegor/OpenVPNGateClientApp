package com.yahorzabotsin.openvpnclientgate.core.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountryFlagTest {

    @Test
    fun returns_flag_for_valid_iso_code() {
        val flag = countryFlagEmoji("ru")
        // Regional indicator symbols for R and U
        assertEquals("\uD83C\uDDF7\uD83C\uDDFA", flag)
    }

    @Test
    fun returns_null_for_invalid_code() {
        assertNull(countryFlagEmoji(null))
        assertNull(countryFlagEmoji(""))
        assertNull(countryFlagEmoji("R"))
        assertNull(countryFlagEmoji("RUS"))
        assertNull(countryFlagEmoji("1A"))
    }
}


