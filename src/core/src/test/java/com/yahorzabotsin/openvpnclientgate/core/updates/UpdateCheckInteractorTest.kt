package com.yahorzabotsin.openvpnclientgate.core.updates

import com.yahorzabotsin.openvpnclientgate.core.ui.main.DefaultUpdateCheckInteractor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class UpdateCheckInteractorTest {

    @Test
    fun `check delegates to repository with force flag`() = runTest {
        val expected = AppUpdateInfo(
            hasUpdate = true,
            currentBuild = 1,
            latestBuild = 2,
            latestVersion = "1.0",
            name = "Release",
            changelog = "changes",
            resolvedLocale = "en",
            message = "Update available.",
            asset = null
        )
        val repository = FakeRepository(expected)
        val interactor = DefaultUpdateCheckInteractor(repository)

        val result = interactor.check(forceRefresh = true)

        assertSame(expected, result)
        assertEquals(1, repository.calls)
        assertEquals(true, repository.lastForceRefresh)
    }

    private class FakeRepository(
        private val result: AppUpdateInfo?
    ) : UpdateCheckRepository {
        var calls: Int = 0
        var lastForceRefresh: Boolean = false

        override suspend fun checkForUpdate(forceRefresh: Boolean): AppUpdateInfo? {
            calls += 1
            lastForceRefresh = forceRefresh
            return result
        }
    }
}

