package com.yahorzabotsin.openvpnclientgate.core.ui.common.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [ServerDisplayFormatter] formatting logic.
 *
 * Covers formatUtc and formatCityWithUtc including valid inputs, boundary values,
 * invalid inputs, and null/blank handling.
 */
class ServerDisplayFormatterTest {

    // --------------- formatUtc ---------------

    @Test
    fun `formatUtc returns null for null input`() {
        assertNull(ServerDisplayFormatter.formatUtc(null))
    }

    @Test
    fun `formatUtc returns null for blank input`() {
        assertNull(ServerDisplayFormatter.formatUtc("   "))
    }

    @Test
    fun `formatUtc returns null for empty string`() {
        assertNull(ServerDisplayFormatter.formatUtc(""))
    }

    @Test
    fun `formatUtc parses UTC plus offset with hours only`() {
        assertEquals("+09:00 UTC", ServerDisplayFormatter.formatUtc("UTC+9"))
    }

    @Test
    fun `formatUtc parses UTC minus offset`() {
        assertEquals("-08:00 UTC", ServerDisplayFormatter.formatUtc("UTC-8"))
    }

    @Test
    fun `formatUtc parses GMT prefix`() {
        assertEquals("+05:30 UTC", ServerDisplayFormatter.formatUtc("GMT+5:30"))
    }

    @Test
    fun `formatUtc parses bare signed offset without prefix`() {
        assertEquals("+03:00 UTC", ServerDisplayFormatter.formatUtc("+3"))
    }

    @Test
    fun `formatUtc parses offset with colon separator`() {
        assertEquals("+05:30 UTC", ServerDisplayFormatter.formatUtc("UTC+5:30"))
    }

    @Test
    fun `formatUtc parses offset with no colon separator`() {
        assertEquals("+05:30 UTC", ServerDisplayFormatter.formatUtc("UTC+530"))
    }

    @Test
    fun `formatUtc parses UTC plus zero`() {
        assertEquals("+00:00 UTC", ServerDisplayFormatter.formatUtc("UTC+0"))
    }

    @Test
    fun `formatUtc accepts maximum valid hours boundary`() {
        assertEquals("+23:00 UTC", ServerDisplayFormatter.formatUtc("UTC+23"))
    }

    @Test
    fun `formatUtc returns null for hours exceeding 23`() {
        assertNull(ServerDisplayFormatter.formatUtc("UTC+24"))
    }

    @Test
    fun `formatUtc accepts maximum valid minutes boundary`() {
        assertEquals("+05:59 UTC", ServerDisplayFormatter.formatUtc("UTC+5:59"))
    }

    @Test
    fun `formatUtc returns null for minutes exceeding 59`() {
        assertNull(ServerDisplayFormatter.formatUtc("UTC+5:60"))
    }

    @Test
    fun `formatUtc returns null for unrecognized format`() {
        assertNull(ServerDisplayFormatter.formatUtc("notAZone"))
    }

    @Test
    fun `formatUtc is case insensitive for prefix`() {
        assertEquals("+09:00 UTC", ServerDisplayFormatter.formatUtc("utc+9"))
    }

    // --------------- formatCityWithUtc ---------------

    @Test
    fun `formatCityWithUtc returns null for null city`() {
        assertNull(ServerDisplayFormatter.formatCityWithUtc(null, "UTC+9"))
    }

    @Test
    fun `formatCityWithUtc returns null for blank city`() {
        assertNull(ServerDisplayFormatter.formatCityWithUtc("   ", "UTC+9"))
    }

    @Test
    fun `formatCityWithUtc returns null for empty city`() {
        assertNull(ServerDisplayFormatter.formatCityWithUtc("", "UTC+9"))
    }

    @Test
    fun `formatCityWithUtc returns trimmed city with formatted UTC`() {
        assertEquals("Tokyo (+09:00 UTC)", ServerDisplayFormatter.formatCityWithUtc("Tokyo", "UTC+9"))
    }

    @Test
    fun `formatCityWithUtc trims city whitespace`() {
        assertEquals("Paris (+01:00 UTC)", ServerDisplayFormatter.formatCityWithUtc("  Paris  ", "UTC+1"))
    }

    @Test
    fun `formatCityWithUtc returns city only when utc is null`() {
        assertEquals("Berlin", ServerDisplayFormatter.formatCityWithUtc("Berlin", null))
    }

    @Test
    fun `formatCityWithUtc returns city only when utc is blank`() {
        assertEquals("Madrid", ServerDisplayFormatter.formatCityWithUtc("Madrid", ""))
    }

    @Test
    fun `formatCityWithUtc returns city only when utc is unrecognized`() {
        assertEquals("Seoul", ServerDisplayFormatter.formatCityWithUtc("Seoul", "badZone"))
    }

    @Test
    fun `formatCityWithUtc handles negative UTC offset`() {
        assertEquals("Los Angeles (-08:00 UTC)", ServerDisplayFormatter.formatCityWithUtc("Los Angeles", "UTC-8"))
    }
}
