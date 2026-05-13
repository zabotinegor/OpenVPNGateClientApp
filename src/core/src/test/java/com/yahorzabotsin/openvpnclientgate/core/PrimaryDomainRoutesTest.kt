package com.yahorzabotsin.openvpnclientgate.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PrimaryDomainRoutesTest {

    @Test
    fun `derives legacy csv and v2 routes from primary base url`() {
        val baseUrl = "https://api.example.com"

        assertEquals(
            "https://api.example.com/api/v1/servers/active",
            PrimaryDomainRoutes.legacyServersUrl(baseUrl)
        )
        assertEquals(
            "https://api.example.com/api/v2/servers/countries/active",
            PrimaryDomainRoutes.v2CountriesUrl(baseUrl)
        )
        assertEquals(
            "https://api.example.com/api/v2/servers",
            PrimaryDomainRoutes.v2ServersUrl(baseUrl)
        )
        assertEquals(
            "https://api.example.com/",
            PrimaryDomainRoutes.retrofitBaseUrl(baseUrl)
        )
    }

    @Test
    fun `preserves safe shared path prefixes when deriving routes`() {
        val baseUrl = "https://api.example.com/custom/root"

        assertEquals(
            "https://api.example.com/custom/root/api/v1/servers/active",
            PrimaryDomainRoutes.legacyServersUrl(baseUrl)
        )
        assertEquals(
            "https://api.example.com/custom/root/api/v2/servers/countries/active",
            PrimaryDomainRoutes.v2CountriesUrl(baseUrl)
        )
        assertEquals(
            "https://api.example.com/custom/root/api/v1/versions/number/1.2.3/build/42?locale=ru",
            PrimaryDomainRoutes.versionByNumberAndBuildUrl(baseUrl, "1.2.3", 42L, "ru")
        )
        assertEquals(
            "https://api.example.com/custom/root/api/v2/versions/check-update?platform=mobile&releaseType=release&currentBuild=42&locale=ru",
            PrimaryDomainRoutes.updateCheckUrl(baseUrl, 2, "mobile", "release", 42L, "ru")
        )
        assertEquals(
            "https://api.example.com/custom/root/",
            PrimaryDomainRoutes.retrofitBaseUrl(baseUrl)
        )
    }

    @Test
    fun `normalizes old api route inputs back to the primary domain root`() {
        val legacyRoute = "https://api.example.com/custom/root/api/v1/servers/active"

        assertEquals(
            "https://api.example.com/custom/root/api/v2/servers",
            PrimaryDomainRoutes.v2ServersUrl(legacyRoute)
        )
        assertEquals(
            "https://api.example.com/custom/root/",
            PrimaryDomainRoutes.retrofitBaseUrl(legacyRoute)
        )
    }

    @Test
    fun `normalizes base urls that end with api version marker without trailing slash`() {
        val baseUrl = "https://api.example.com/custom/root/api/v1"

        assertEquals(
            "https://api.example.com/custom/root/api/v1/servers/active",
            PrimaryDomainRoutes.legacyServersUrl(baseUrl)
        )
        assertEquals(
            "https://api.example.com/custom/root/",
            PrimaryDomainRoutes.retrofitBaseUrl(baseUrl)
        )
    }
}