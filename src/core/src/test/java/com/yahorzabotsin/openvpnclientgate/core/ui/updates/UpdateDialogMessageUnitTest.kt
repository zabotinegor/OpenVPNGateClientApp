package com.yahorzabotsin.openvpnclientgate.core.ui.updates

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateDialogMessageUnitTest {

    @Test
    fun `uses localized message and appends formatted latest version when version is provided`() {
        val message = buildUpdateDialogMessage(
            localizedMessage = "Доступно новое обновление.",
            latestVersion = "1.0.2",
            latestVersionFormatter = { version -> "Последняя версия: $version" })

        assertEquals(
            "Доступно новое обновление.\nПоследняя версия: 1.0.2",
            message
        )
    }

    @Test
    fun `returns only localized message when latest version is null`() {
        val message = buildUpdateDialogMessage(
            localizedMessage = "Доступно новое обновление.",
            latestVersion = null,
            latestVersionFormatter = { version -> "Последняя версия: $version" })

        assertEquals("Доступно новое обновление.", message)
    }

    @Test
    fun `returns only localized message when latest version is blank`() {
        val message = buildUpdateDialogMessage(
            localizedMessage = "Dostępna jest nowa aktualizacja.",
            latestVersion = "",
            latestVersionFormatter = { version -> "Najnowsza wersja: $version" })

        assertEquals("Dostępna jest nowa aktualizacja.", message)
    }
}


