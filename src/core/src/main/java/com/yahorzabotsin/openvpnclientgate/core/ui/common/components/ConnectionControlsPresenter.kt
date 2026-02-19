package com.yahorzabotsin.openvpnclientgate.core.ui.common.components

import android.content.Context
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionState
import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionStateManager
import de.blinkt.openvpn.core.ConnectionStatus
import java.util.Locale

data class ConnectionButtonModel(
    val text: CharSequence,
    val style: ConnectionButtonStyle
)

enum class ConnectionButtonStyle {
    ACTIVE,
    CONNECTING,
    DISCONNECTED
}

data class ConnectionServerSync(
    val country: String?,
    val ip: String?,
    val cityText: String
)

class ConnectionControlsPresenter(
    private val context: Context,
    private val useCase: ConnectionControlsUseCase
) {

    private val durationPlaceholder = "00:00:00"

    fun buildStatusText(
        state: ConnectionState,
        engineLevel: ConnectionStatus?,
        remainingSeconds: Int?
    ): String {
        val statusRes = when (state) {
            ConnectionState.DISCONNECTED -> R.string.main_status_disconnected
            ConnectionState.CONNECTING -> R.string.main_status_connecting
            ConnectionState.CONNECTED -> R.string.main_status_connected
            ConnectionState.DISCONNECTING -> R.string.main_status_disconnecting
        }
        val baseStatus = context.getString(statusRes)
        val showCountdown = engineLevel == ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET ||
            engineLevel == ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED
        return if (state == ConnectionState.CONNECTING && remainingSeconds != null && showCountdown) {
            val suffix = runCatching { context.getString(R.string.state_countdown_seconds, remainingSeconds) }
                .getOrDefault("")
            "${useCase.trimEllipsis(baseStatus)}$suffix"
        } else {
            baseStatus
        }
    }

    fun buildButtonModel(
        state: ConnectionState,
        detail: String?,
        level: ConnectionStatus?,
        reconnectingHint: Boolean
    ): ConnectionButtonModel {
        return when (state) {
            ConnectionState.CONNECTED,
            ConnectionState.DISCONNECTING -> ConnectionButtonModel(
                text = context.getString(R.string.stop_connection),
                style = ConnectionButtonStyle.ACTIVE
            )

            ConnectionState.CONNECTING -> {
                val isTeardown = (level == ConnectionStatus.LEVEL_NOTCONNECTED &&
                    isTeardownDetail(detail))
                val text = if (reconnectingHint && isTeardown) {
                    engineDetailToText("RECONNECTING")
                } else {
                    val showGenericConnecting = (level == ConnectionStatus.LEVEL_NOTCONNECTED &&
                        isGenericConnectingDetail(detail))
                    engineDetailToText(if (showGenericConnecting) "CONNECTING" else detail)
                }
                ConnectionButtonModel(text = text, style = ConnectionButtonStyle.CONNECTING)
            }

            ConnectionState.DISCONNECTED -> {
                if (reconnectingHint) {
                    ConnectionButtonModel(
                        text = engineDetailToText("RECONNECTING"),
                        style = ConnectionButtonStyle.CONNECTING
                    )
                } else {
                    ConnectionButtonModel(
                        text = context.getString(R.string.start_connection),
                        style = ConnectionButtonStyle.DISCONNECTED
                    )
                }
            }
        }
    }

    fun formatDuration(state: ConnectionState, connectionStartTimeMs: Long?): String {
        if (state != ConnectionState.CONNECTED || connectionStartTimeMs == null) return durationPlaceholder
        val elapsedSec = ((System.currentTimeMillis() - connectionStartTimeMs) / 1000L).coerceAtLeast(0L)
        val hours = elapsedSec / 3600
        val minutes = (elapsedSec % 3600) / 60
        val seconds = elapsedSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    fun formatTraffic(downloaded: Long, uploaded: Long): Pair<String, String> =
        useCase.formatBytes(downloaded) to useCase.formatBytes(uploaded)

    fun syncServer(
        selectionStore: ConnectionControlsSelectionStore,
        selectedCountry: String?,
        selectedServerIp: String?,
        vpnConfig: String?,
        reconnectingHint: Boolean = false
    ): ConnectionServerSync? {
        val resolvedCountry = selectedCountry ?: runCatching { selectionStore.getSelectedCountry(context) }.getOrNull()
        if (resolvedCountry.isNullOrBlank()) return null

        val current = runCatching { selectionStore.currentServer(context) }.getOrNull()
        val lastStarted = runCatching { selectionStore.getLastStartedConfig(context) }.getOrNull()
        val lastSuccessfulIp = runCatching { selectionStore.getLastSuccessfulIpForSelected(context) }.getOrNull()

        val ipFromCurrentConfig = current?.takeIf { it.config == vpnConfig }?.ip
        val ipFromLastStartedConfig = lastStarted
            ?.takeIf { it.country == resolvedCountry && it.config == vpnConfig }
            ?.ip
        val ipFromCurrentServer = current?.ip

        val ip = when {
            !ipFromCurrentConfig.isNullOrBlank() -> ipFromCurrentConfig
            !ipFromLastStartedConfig.isNullOrBlank() -> ipFromLastStartedConfig
            reconnectingHint && !ipFromCurrentServer.isNullOrBlank() -> ipFromCurrentServer
            !selectedServerIp.isNullOrBlank() -> selectedServerIp
            !ipFromCurrentServer.isNullOrBlank() -> ipFromCurrentServer
            !lastStarted?.takeIf { it.country == resolvedCountry }?.ip.isNullOrBlank() -> lastStarted?.ip
            !lastSuccessfulIp.isNullOrBlank() -> lastSuccessfulIp
            else -> null
        }

        val cityText = runCatching { selectionStore.getCurrentPosition(context) }
            .getOrNull()
            ?.let { (index, total) ->
                context.getString(R.string.connection_detail_server_position, index, total)
            }
            .orEmpty()

        return ConnectionServerSync(
            country = resolvedCountry,
            ip = ip,
            cityText = cityText
        )
    }

    fun resolveIpForConfig(
        selectionStore: ConnectionControlsSelectionStore,
        config: String?,
        selectedServerIp: String?
    ): String? {
        if (config.isNullOrBlank()) return selectedServerIp
        val current = runCatching { selectionStore.currentServer(context) }.getOrNull()
        if (current?.config == config && !current.ip.isNullOrBlank()) return current.ip
        val lastSuccessfulIp = runCatching { selectionStore.getLastSuccessfulIpForSelected(context) }.getOrNull()
        val lastSuccessfulConfig = runCatching { selectionStore.getLastSuccessfulConfigForSelected(context) }.getOrNull()
        if (!lastSuccessfulIp.isNullOrBlank() && lastSuccessfulConfig == config) return lastSuccessfulIp
        return runCatching { selectionStore.getIpForConfig(context, config) }.getOrNull()
            ?: selectedServerIp
    }

    private fun engineDetailToText(detail: String?): CharSequence {
        val resId = useCase.mapEngineDetailToResId(detail)
        return resId?.let { context.getString(it) }
            ?: (detail ?: context.getString(R.string.vpn_notification_text_connecting))
    }

    private fun isGenericConnectingDetail(detail: String?): Boolean {
        return detail == null || isTeardownDetail(detail)
    }

    private fun isTeardownDetail(detail: String?): Boolean {
        return detail != null && detail in ConnectionStateManager.engineTeardownDetails
    }
}
