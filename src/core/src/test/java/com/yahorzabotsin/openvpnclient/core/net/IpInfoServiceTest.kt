package com.yahorzabotsin.openvpnclient.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IpInfoServiceTest {

    @Test
    fun `parseIpInfoJson returns ip and city when both present`() {
        val json = """
            {
              "ip": "203.0.113.10",
              "city": "Tokyo"
            }
        """.trimIndent()

        val result = IpInfoService.parseIpInfoJson(json)

        assertEquals(IpInfo(ip = "203.0.113.10", city = "Tokyo"), result)
    }

    @Test
    fun `parseIpInfoJson returns ip with null city when city missing`() {
        val json = """
            {
              "ip": "203.0.113.10"
            }
        """.trimIndent()

        val result = IpInfoService.parseIpInfoJson(json)

        assertEquals(IpInfo(ip = "203.0.113.10", city = null), result)
    }

    @Test
    fun `parseIpInfoJson returns null when ip missing or blank`() {
        val jsonNoIp = """{ "city": "Tokyo" }"""
        val jsonBlankIp = """{ "ip": "   ", "city": "Tokyo" }"""

        assertNull(IpInfoService.parseIpInfoJson(jsonNoIp))
        assertNull(IpInfoService.parseIpInfoJson(jsonBlankIp))
    }

    @Test
    fun `parseIpInfoJson returns null on invalid json`() {
        val invalid = "not a json"
        assertNull(IpInfoService.parseIpInfoJson(invalid))
    }
}

