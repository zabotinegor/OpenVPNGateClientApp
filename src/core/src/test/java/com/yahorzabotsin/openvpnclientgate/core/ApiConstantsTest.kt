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

    @Test
    fun `primary legacy servers url returns normalized value for valid input`() {
        val result = ApiConstants.primaryLegacyServersUrlOrFallback("https://api.example.com/custom")

        assertEquals("https://api.example.com/custom/api/v1/servers/active", result)
    }

    @Test
    fun `primary legacy servers url falls back to safe local url for invalid authority`() {
        val result = ApiConstants.primaryLegacyServersUrlOrFallback("https:///api/v1/servers/active")

        assertEquals("https://openvpnclientgate.local/api/v1/servers/active", result)
    }

    @Test
    fun `primary version url falls back to safe local url for invalid authority`() {
        val result = ApiConstants.primaryVersionByNumberAndBuildUrlOrFallback(
            primaryServersUrl = "https:///api/v1/servers/active",
            versionName = "1.2.3",
            buildNumber = 42L,
            locale = "ru"
        )

        assertEquals(
            "https://openvpnclientgate.local/api/v1/versions/number/1.2.3/build/42?locale=ru",
            result
        )
    }
}