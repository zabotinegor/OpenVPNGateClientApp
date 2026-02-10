package com.yahorzabotsin.openvpnclientgate.core.ui.common.components

import android.content.Context
import com.yahorzabotsin.openvpnclientgate.core.servers.LastConfig
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclientgate.core.servers.StoredServer
import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionState
import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionStateManager
import com.yahorzabotsin.openvpnclientgate.vpn.ServerAutoSwitcher
import de.blinkt.openvpn.core.ConnectionStatus
import kotlinx.coroutines.flow.StateFlow

interface ConnectionControlsRuntime {
    val state: StateFlow<ConnectionState>
    val engineLevel: StateFlow<ConnectionStatus?>
    val engineDetail: StateFlow<String?>
    val reconnectingHint: StateFlow<Boolean>
    val remainingSeconds: StateFlow<Int?>
    val connectionStartTimeMs: StateFlow<Long?>
    val downloadedBytes: StateFlow<Long>
    val uploadedBytes: StateFlow<Long>
}

class DefaultConnectionControlsRuntime : ConnectionControlsRuntime {
    override val state: StateFlow<ConnectionState> = ConnectionStateManager.state
    override val engineLevel: StateFlow<ConnectionStatus?> = ConnectionStateManager.engineLevel
    override val engineDetail: StateFlow<String?> = ConnectionStateManager.engineDetail
    override val reconnectingHint: StateFlow<Boolean> = ConnectionStateManager.reconnectingHint
    override val remainingSeconds: StateFlow<Int?> = ServerAutoSwitcher.remainingSeconds
    override val connectionStartTimeMs: StateFlow<Long?> = ConnectionStateManager.connectionStartTimeMs
    override val downloadedBytes: StateFlow<Long> = ConnectionStateManager.downloadedBytes
    override val uploadedBytes: StateFlow<Long> = ConnectionStateManager.uploadedBytes
}

interface ConnectionControlsSelectionStore {
    fun getSelectedCountry(context: Context): String?
    fun currentServer(context: Context): StoredServer?
    fun getLastStartedConfig(context: Context): LastConfig?
    fun getLastSuccessfulIpForSelected(context: Context): String?
    fun getLastSuccessfulConfigForSelected(context: Context): String?
    fun getIpForConfig(context: Context, config: String): String?
    fun getCurrentPosition(context: Context): Pair<Int, Int>?
}

class DefaultConnectionControlsSelectionStore : ConnectionControlsSelectionStore {
    override fun getSelectedCountry(context: Context): String? =
        SelectedCountryStore.getSelectedCountry(context)

    override fun currentServer(context: Context): StoredServer? =
        SelectedCountryStore.currentServer(context)

    override fun getLastStartedConfig(context: Context): LastConfig? =
        SelectedCountryStore.getLastStartedConfig(context)

    override fun getLastSuccessfulIpForSelected(context: Context): String? =
        SelectedCountryStore.getLastSuccessfulIpForSelected(context)

    override fun getLastSuccessfulConfigForSelected(context: Context): String? =
        SelectedCountryStore.getLastSuccessfulConfigForSelected(context)

    override fun getIpForConfig(context: Context, config: String): String? =
        SelectedCountryStore.getIpForConfig(context, config)

    override fun getCurrentPosition(context: Context): Pair<Int, Int>? =
        SelectedCountryStore.getCurrentPosition(context)
}
