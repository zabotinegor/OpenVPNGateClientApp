package com.yahorzabotsin.openvpnclientgate.core.ui.updates

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateDialogMessageComponentTest {

    @Test
    fun `component message rendering ignores backend english message`() {
        val rendered = buildUpdateDialogMessage(
            localizedMessage = "Dostępna jest nowa aktualizacja.",
            latestVersion = "1.0.2",
            latestVersionFormatter = { version -> "Najnowsza wersja: $version" },
            backendMessage = "Update available."
        )

        assertEquals(
            "Dostępna jest nowa aktualizacja.\nNajnowsza wersja: 1.0.2",
            rendered
        )
    }

    @Test
    fun `component message rendering keeps localized base when version is missing`() {
        val rendered = buildUpdateDialogMessage(
            localizedMessage = "Доступно новое обновление.",
            latestVersion = null,
            latestVersionFormatter = { version -> "Последняя версия: $version" },
            backendMessage = "Update available."
        )

        assertEquals("Доступно новое обновление.", rendered)
    }
}

