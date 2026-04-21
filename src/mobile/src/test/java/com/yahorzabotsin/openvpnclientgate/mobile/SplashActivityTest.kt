package com.yahorzabotsin.openvpnclientgate.mobile

import com.yahorzabotsin.openvpnclientgate.core.ui.splash.SplashServerPreloadInteractor
import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionState
import com.yahorzabotsin.openvpnclientgate.vpn.VpnConnectionStateProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SplashActivityTest {

    private lateinit var activity: SplashActivity

    @Before
    fun setUp() {
        startKoin {
            modules(module {
                single<SplashServerPreloadInteractor> {
                    object : SplashServerPreloadInteractor {
                        override suspend fun preloadServers(cacheOnly: Boolean) { /* no-op */ }
                    }
                }
                single<VpnConnectionStateProvider> {
                    object : VpnConnectionStateProvider {
                        override val state: StateFlow<ConnectionState> =
                            MutableStateFlow(ConnectionState.DISCONNECTED)
                        override fun isConnected(): Boolean = false
                    }
                }
            })
        }
        activity = Robolectric.buildActivity(SplashActivity::class.java).get()
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    /**
     * Verifies that the hasNavigated guard prevents double navigation.
     * Calling navigateToMain twice should set hasNavigated on the first call only.
     */
    @Test
    fun hasNavigatedGuardPreventsDoubleNavigation() {
        assertFalse("hasNavigated should be false initially", getHasNavigated(activity))

        // First call sets hasNavigated (startActivity may throw in test env, that is expected)
        try {
            invokeNavigateToMain(activity)
        } catch (e: Exception) {
            // Expected — startActivity fails without a real context
        }

        assertTrue("hasNavigated should be true after first call", getHasNavigated(activity))

        // Second call must be a no-op and must not throw
        invokeNavigateToMain(activity)

        assertTrue("hasNavigated should still be true after second call", getHasNavigated(activity))
    }

    // --- Reflection helpers ---

    private fun invokeNavigateToMain(activity: SplashActivity) {
        val method = com.yahorzabotsin.openvpnclientgate.core.ui.splash.SplashActivityCore::class.java
            .getDeclaredMethod("navigateToMain")
        method.isAccessible = true
        try {
            method.invoke(activity)
        } catch (e: Exception) {
            throw RuntimeException("Failed to invoke navigateToMain", e)
        }
    }

    private fun getHasNavigated(activity: SplashActivity): Boolean {
        val field = com.yahorzabotsin.openvpnclientgate.core.ui.splash.SplashActivityCore::class.java
            .getDeclaredField("hasNavigated")
        field.isAccessible = true
        return field.getBoolean(activity)
    }
}

