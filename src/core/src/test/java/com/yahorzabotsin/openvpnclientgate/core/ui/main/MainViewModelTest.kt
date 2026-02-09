package com.yahorzabotsin.openvpnclientgate.core.ui.main

import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.ui.about.MainDispatcherRule
import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.UiText
import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionState
import com.yahorzabotsin.openvpnclientgate.vpn.VpnConnectionStateProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun `load initial selection updates state with cache only from connection state`() = runTest {
        val interactor = FakeMainSelectionInteractor(
            initialSelection = InitialSelection(
                country = "France",
                city = "Paris",
                config = "config",
                countryCode = "FR",
                ip = "1.2.3.4"
            )
        )
        val viewModel = MainViewModel(
            selectionInteractor = interactor,
            connectionInteractor = FakeMainConnectionInteractor(),
            connectionStateProvider = FakeConnectionProvider(ConnectionState.CONNECTED),
            logger = FakeMainLogger()
        )

        viewModel.onAction(MainAction.LoadInitialSelection)
        advanceUntilIdle()

        assertTrue(interactor.lastCacheOnly == true)
        val selected = viewModel.state.value.selectedServer
        assertEquals("France", selected?.country)
        assertEquals("config", selected?.config)
        assertEquals(false, selected?.fromUserSelection)
    }

    @Test
    fun `server menu and successful selection reopens drawer and applies user config`() = runTest {
        val viewModel = MainViewModel(
            selectionInteractor = FakeMainSelectionInteractor(),
            connectionInteractor = FakeMainConnectionInteractor(),
            connectionStateProvider = FakeConnectionProvider(ConnectionState.CONNECTED),
            logger = FakeMainLogger()
        )

        val effects = mutableListOf<MainEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            viewModel.effects.take(4).toList(effects)
        }

        viewModel.onAction(MainAction.NavigationItemSelected(R.id.nav_server))
        viewModel.onAction(
            MainAction.OnServerSelectionResult(
                SelectedServerResult(
                    country = "Germany",
                    countryCode = "DE",
                    city = "Berlin",
                    config = "cfg",
                    ip = "8.8.8.8"
                )
            )
        )
        advanceUntilIdle()

        assertTrue(effects.any { it is MainEffect.OpenDestination })
        assertTrue(effects.any { it is MainEffect.CloseDrawer })
        assertEquals(true, viewModel.state.value.selectedServer?.fromUserSelection)
        assertTrue(effects.any { it is MainEffect.ReopenDrawer })
        job.cancel()
    }

    @Test
    fun `connection click without selected server emits toast effect`() = runTest {
        val viewModel = MainViewModel(
            selectionInteractor = FakeMainSelectionInteractor(),
            connectionInteractor = FakeMainConnectionInteractor(),
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeMainLogger()
        )
        val effects = mutableListOf<MainEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            viewModel.effects.take(1).toList(effects)
        }

        viewModel.onAction(
            MainAction.ConnectionButtonClicked(
                hasNotificationPermission = true,
                hasVpnPermission = true
            )
        )
        advanceUntilIdle()

        val effect = effects.first() as MainEffect.ShowToast
        assertEquals(UiText.Res(R.string.select_server_first), effect.text)
        job.cancel()
    }

    @Test
    fun `multi window mode updates details visibility in state`() = runTest {
        val viewModel = MainViewModel(
            selectionInteractor = FakeMainSelectionInteractor(),
            connectionInteractor = FakeMainConnectionInteractor(),
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeMainLogger()
        )

        viewModel.onAction(MainAction.OnMultiWindowModeChanged(isInMultiWindowMode = true))
        advanceUntilIdle()
        assertEquals(false, viewModel.state.value.isDetailsVisible)

        viewModel.onAction(MainAction.OnMultiWindowModeChanged(isInMultiWindowMode = false))
        advanceUntilIdle()
        assertEquals(true, viewModel.state.value.isDetailsVisible)
    }

    @Test
    fun `connection click with missing permissions emits permission effect`() = runTest {
        val viewModel = MainViewModel(
            selectionInteractor = FakeMainSelectionInteractor(
                initialSelection = InitialSelection(
                    country = "France",
                    city = "Paris",
                    config = "config",
                    countryCode = "FR",
                    ip = "1.2.3.4"
                )
            ),
            connectionInteractor = FakeMainConnectionInteractor(),
            connectionStateProvider = FakeConnectionProvider(ConnectionState.DISCONNECTED),
            logger = FakeMainLogger()
        )
        viewModel.onAction(MainAction.LoadInitialSelection)
        advanceUntilIdle()

        val effects = mutableListOf<MainEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            viewModel.effects.take(1).toList(effects)
        }

        viewModel.onAction(
            MainAction.ConnectionButtonClicked(
                hasNotificationPermission = false,
                hasVpnPermission = true
            )
        )
        advanceUntilIdle()

        assertTrue(effects.first() is MainEffect.RequestNotificationPermission)
        job.cancel()
    }

    private class FakeMainSelectionInteractor(
        private val initialSelection: InitialSelection? = null
    ) : MainSelectionInteractor {
        var lastCacheOnly: Boolean? = null

        override suspend fun loadInitialSelection(cacheOnly: Boolean): InitialSelection? {
            lastCacheOnly = cacheOnly
            return initialSelection
        }
    }

    private class FakeConnectionProvider(initial: ConnectionState) : VpnConnectionStateProvider {
        private val stateFlow = MutableStateFlow(initial)
        override val state: StateFlow<ConnectionState> = stateFlow
        override fun isConnected(): Boolean = stateFlow.value == ConnectionState.CONNECTED
    }

    private class FakeMainConnectionInteractor : MainConnectionInteractor {
        override fun prepareStart(
            selectedServer: MainSelectedServer?,
            preferUserSelection: Boolean
        ): PreparedConnectionStart? = selectedServer?.let {
            PreparedConnectionStart(
                config = it.config,
                country = it.country,
                ip = it.ip
            )
        }
    }

    private class FakeMainLogger : MainLogger {
        override fun logInitialSelectionLoaded(selection: InitialSelection) = Unit
        override fun logInitialSelectionError(error: Exception) = Unit
        override fun logServerSelectionApplied(selection: SelectedServerResult) = Unit
        override fun logIncompleteServerSelection(selection: SelectedServerResult) = Unit
    }
}
