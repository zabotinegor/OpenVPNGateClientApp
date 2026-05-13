package com.yahorzabotsin.openvpnclientgate.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiConstantsTest {

    @Test
    fun `primary retrofit base url returns normalized value for valid input`() {
        val result = ApiConstants.primaryRetrofitBaseUrlOrFallback("https://api.example.com/custom")

        assertEquals("https://api.example.com/custom/", result)
    }

    @Test
    fun `primary retrofit base url falls back to safe local url for invalid authority`() {
        val result = ApiConstants.primaryRetrofitBaseUrlOrFallback("https:///api/v1/servers/active")

        assertEquals("https://openvpnclientgate.local/", result)
    }
}