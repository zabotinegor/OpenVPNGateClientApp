package com.yahorzabotsin.openvpnclientgate.core.servers

import com.google.gson.Gson
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesFilterTest {

    private fun country(code: String, name: String = code, serverCount: Int = 1) =
        CountryV2(code = code, name = name, serverCount = serverCount)

    private fun server(
        id: Int,
        name: String = "srv-$id",
        city: String = "City",
        countryName: String = "CountryA"
    ) = Server(
        lineIndex = id,
        name = name,
        city = city,
        country = Country(countryName),
        ping = 10,
        signalStrength = SignalStrength.STRONG,
        ip = "1.1.1.$id",
        score = 100,
        speed = 1000,
        numVpnSessions = 1,
        uptime = 100,
        totalUsers = 10,
        totalTraffic = 1000,
        logType = "",
        operator = "",
        message = "",
        configData = "config$id",
        id = id
    )

    // --- Countries: all-present (AC3) ---

    @Test
    fun filterFavoriteCountries_allFavoritesPresent_returnsAllFavorites() {
        val favorites = setOf("US", "DE")
        val current = listOf(country("US"), country("DE"), country("FR"))

        val result = FavoritesFilter.filterFavoriteCountries(favorites, current)

        assertEquals(2, result.size)
        assertTrue(result.any { it.code == "US" })
        assertTrue(result.any { it.code == "DE" })
    }

    // --- Countries: some-absent (AC3) ---

    @Test
    fun filterFavoriteCountries_someFavoritesAbsent_excludesAbsentOnesButKeepsPersisted() {
        val favorites = setOf("US", "DE", "JP")
        val current = listOf(country("US"), country("FR")) // DE and JP absent from current sync

        val result = FavoritesFilter.filterFavoriteCountries(favorites, current)

        assertEquals(1, result.size)
        assertEquals("US", result[0].code)
        // Filtering must not mutate the input favorites set (persistence is untouched by design).
        assertEquals(setOf("US", "DE", "JP"), favorites)
    }

    @Test
    fun filterFavoriteCountries_noFavorites_returnsEmpty() {
        val result = FavoritesFilter.filterFavoriteCountries(emptySet(), listOf(country("US")))

        assertTrue(result.isEmpty())
    }

    @Test
    fun filterFavoriteCountries_emptyCurrentList_returnsEmpty() {
        val result = FavoritesFilter.filterFavoriteCountries(setOf("US"), emptyList())

        assertTrue(result.isEmpty())
    }

    // --- Countries: locale-independent code matching (PR #118 review R2) ---

    @Test
    fun filterFavoriteCountries_matchesLowercaseCodesRegardlessOfDefaultLocale() {
        val defaultLocale = Locale.getDefault()
        try {
            // Turkish locale: locale-sensitive "i".uppercase() yields dotted capital I (U+0130),
            // which would break matching against the store-normalized "TR"/"IT" codes.
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))

            val favorites = setOf("tr", "it")
            val current = listOf(country("TR"), country("IT"), country("US"))

            val result = FavoritesFilter.filterFavoriteCountries(favorites, current)

            assertEquals(2, result.size)
            assertTrue(result.any { it.code == "TR" })
            assertTrue(result.any { it.code == "IT" })
        } finally {
            Locale.setDefault(defaultLocale)
        }
    }

    // --- Countries: restoration after re-appearance (AC4) ---

    @Test
    fun filterFavoriteCountries_restoresFavoriteOnceItReappearsInLaterSync() {
        val favorites = setOf("US", "DE")
        val syncWithoutDe = listOf(country("US"))

        val firstResult = FavoritesFilter.filterFavoriteCountries(favorites, syncWithoutDe)
        assertEquals(1, firstResult.size)
        assertEquals("US", firstResult[0].code)

        // DE reappears in a later sync; no add/restore action taken by the caller.
        val syncWithDe = listOf(country("US"), country("DE"))
        val secondResult = FavoritesFilter.filterFavoriteCountries(favorites, syncWithDe)

        assertEquals(2, secondResult.size)
        assertTrue(secondResult.any { it.code == "DE" })
    }

    // --- Countries: defensive null/blank code guard (review round 1) ---

    @Test
    fun filterFavoriteCountries_skipsCountryWithNullCodeInjectedByGson() {
        // CountryV2.code is declared non-null, but Gson leaves it null when the JSON
        // field is missing; the filter must skip such entries instead of throwing NPE.
        val nullCodeCountry = Gson().fromJson(
            """{"name":"NoCode","serverCount":1}""",
            CountryV2::class.java
        )
        val favorites = setOf("US")
        val current = listOf(nullCodeCountry, country("US"))

        val result = FavoritesFilter.filterFavoriteCountries(favorites, current)

        assertEquals(1, result.size)
        assertEquals("US", result[0].code)
    }

    @Test
    fun filterFavoriteCountries_skipsCountriesWithBlankCode() {
        val favorites = setOf("US")
        val current = listOf(country(""), country("   ", name = "Blank"), country("US"))

        val result = FavoritesFilter.filterFavoriteCountries(favorites, current)

        assertEquals(1, result.size)
        assertEquals("US", result[0].code)
    }

    // --- Servers: all-present (AC3) ---

    @Test
    fun filterFavoriteServers_allFavoritesPresent_returnsAllFavorites() {
        val favorites = setOf(1, 2)
        val current = listOf(server(1), server(2), server(3))

        val result = FavoritesFilter.filterFavoriteServers(favorites, current)

        assertEquals(2, result.size)
        assertTrue(result.any { it.id == 1 })
        assertTrue(result.any { it.id == 2 })
    }

    // --- Servers: some-absent (AC3) ---

    @Test
    fun filterFavoriteServers_someFavoritesAbsent_excludesAbsentOnesButKeepsPersisted() {
        val favorites = setOf(1, 2, 3)
        val current = listOf(server(1)) // 2 and 3 absent from current sync

        val result = FavoritesFilter.filterFavoriteServers(favorites, current)

        assertEquals(1, result.size)
        assertEquals(1, result[0].id)
        assertEquals(setOf(1, 2, 3), favorites)
    }

    @Test
    fun filterFavoriteServers_noFavorites_returnsEmpty() {
        val result = FavoritesFilter.filterFavoriteServers(emptySet(), listOf(server(1)))

        assertTrue(result.isEmpty())
    }

    @Test
    fun filterFavoriteServers_emptyCurrentList_returnsEmpty() {
        val result = FavoritesFilter.filterFavoriteServers(setOf(1), emptyList())

        assertTrue(result.isEmpty())
    }

    // --- Servers: restoration after re-appearance (AC4) ---

    @Test
    fun filterFavoriteServers_restoresFavoriteOnceItReappearsInLaterSync() {
        val favorites = setOf(1, 2)
        val syncWithoutServer2 = listOf(server(1))

        val firstResult = FavoritesFilter.filterFavoriteServers(favorites, syncWithoutServer2)
        assertEquals(1, firstResult.size)
        assertEquals(1, firstResult[0].id)

        // Server 2 reappears in a later sync; no add/restore action taken by the caller.
        val syncWithServer2 = listOf(server(1), server(2))
        val secondResult = FavoritesFilter.filterFavoriteServers(favorites, syncWithServer2)

        assertEquals(2, secondResult.size)
        assertTrue(secondResult.any { it.id == 2 })
    }
}
