package com.yahorzabotsin.openvpnclientgate.core.ui.common.components

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.logging.launchLogged
import com.yahorzabotsin.openvpnclientgate.core.databinding.ViewConnectionControlsBinding
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclientgate.core.servers.countryFlagEmoji
import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionState
import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionStateManager
import com.yahorzabotsin.openvpnclientgate.vpn.ServerAutoSwitcher
import de.blinkt.openvpn.core.ConnectionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import java.util.Locale

class ConnectionControlsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private val binding: ViewConnectionControlsBinding =
        ViewConnectionControlsBinding.inflate(LayoutInflater.from(context), this)

    private var vpnConfig: String? = null
    private var selectedCountry: String? = null
    private var selectedCountryCode: String? = null
    private var selectedServerIp: String? = null
    private var openServerList: (() -> Unit)? = null
    private var onConnectionButtonClick: (() -> Unit)? = null
    private var connectionDetailsListener: ConnectionDetailsListener? = null
    private val useCase = ConnectionControlsUseCase()

    companion object {
        private val TAG = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "ConnectionControlsView"
        private const val DURATION_PLACEHOLDER = "00:00:00"
    }

    init {
        applyServerSelectionLabel(context.getString(R.string.current_country))
        setupClicks()
    }

    private fun setupClicks() {
        binding.startConnectionButton.setOnClickListener {
            onConnectionButtonClick?.invoke()
        }

        binding.serverSelectionContainer.setOnClickListener {
            Log.d(TAG, "Server selection container clicked")
            openServerList?.invoke()
        }
    }

    fun requestPrimaryFocus() {
        binding.startConnectionButton.isFocusable = true
        binding.startConnectionButton.isFocusableInTouchMode = true
        binding.startConnectionButton.requestFocus()
    }

    fun setConnectionDetailsListener(listener: ConnectionDetailsListener?) {
        connectionDetailsListener = listener
    }

    fun setConnectionButtonClickHandler(handler: () -> Unit) {
        onConnectionButtonClick = handler
    }

    fun setOpenServerListHandler(handler: () -> Unit) {
        openServerList = handler
    }

    fun setServer(country: String, countryCode: String? = null, ip: String? = null) {
        Log.d(TAG, "Server set: $country, ip=$ip")
        selectedCountry = country
        selectedCountryCode = countryCode
        updateAddress(ip)
        applyServerSelectionLabel(country, ip)
        updateServerPosition()

        if (ConnectionStateManager.state.value == ConnectionState.DISCONNECTED) {
            updateLocationPlaceholders()
        }
    }

    fun setVpnConfig(config: String) {
        setVpnConfigInternal(config)
    }

    fun setVpnConfigFromUser(config: String) {
        setVpnConfigInternal(config)
    }

    private fun setVpnConfigInternal(config: String) {
        Log.d(TAG, "VPN config set")
        vpnConfig = config
        val resolvedIp = resolveIpForConfig(config)
        if (!resolvedIp.isNullOrBlank()) {
            updateAddress(resolvedIp)
            applyServerSelectionLabel(selectedCountry ?: context.getString(R.string.current_country), resolvedIp)
        }
        updateServerPosition()
    }

    fun setLifecycleOwner(lifecycleOwner: LifecycleOwner) {
        lifecycleOwner.launchLogged(TAG) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConnectionStateManager.state.collect { state ->
                    updateStatusLabel(state)
                    updateButtonState(state)
                    syncSelectedServerIpFromStore()
                }
            }
        }
        lifecycleOwner.launchLogged(TAG) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    ConnectionStateManager.engineLevel,
                    ConnectionStateManager.engineDetail,
                    ConnectionStateManager.reconnectingHint,
                    ServerAutoSwitcher.remainingSeconds
                ) { _, _, _, _ -> }
                    .collect {
                        val current = ConnectionStateManager.state.value
                        updateStatusLabel(current)
                        updateButtonState(current)
                        syncSelectedServerIpFromStore()
                    }
            }
        }
        lifecycleOwner.launchLogged(TAG) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    updateDurationTimer()
                    delay(1000L)
                }
            }
        }
        lifecycleOwner.launchLogged(TAG) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    ConnectionStateManager.downloadedBytes,
                    ConnectionStateManager.uploadedBytes
                ) { downloaded, uploaded -> downloaded to uploaded }
                    .collect { (downloaded, uploaded) ->
                        updateTraffic(downloaded, uploaded)
                    }
            }
        }
    }

    private fun applyServerSelectionLabel(country: String, ip: String? = selectedServerIp) {
        val primary = country.ifBlank { context.getString(R.string.current_country) }
        val flag = countryFlagEmoji(selectedCountryCode)
        val primaryWithFlag = if (!flag.isNullOrEmpty()) "$flag $primary" else primary
        binding.serverSelectionContainer.text = buildServerSelectionLabel(primaryWithFlag, ip)
        val description = listOf(primaryWithFlag, ip.orEmpty())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(separator = ", ")
        binding.serverSelectionContainer.contentDescription = description
        updateServerButtonIcons(showGlobe = flag.isNullOrEmpty())
    }

    private fun buildServerSelectionLabel(country: String, ip: String?): CharSequence =
        buildSpannedString {
            inSpans(android.text.style.TextAppearanceSpan(context, R.style.TextAppearance_OpenVPNClientGate_BodyAdditional)) {
                append(country.trim())
            }
        }

    private fun syncSelectedServerIpFromStore() {
        val resolvedCountry = selectedCountry ?: runCatching { SelectedCountryStore.getSelectedCountry(context) }.getOrNull()
        if (resolvedCountry.isNullOrBlank()) return
        selectedCountry = resolvedCountry
        val current = runCatching { SelectedCountryStore.currentServer(context) }.getOrNull()
        val lastStarted = runCatching { SelectedCountryStore.getLastStartedConfig(context) }.getOrNull()
        val lastSuccessfulIp = runCatching { SelectedCountryStore.getLastSuccessfulIpForSelected(context) }.getOrNull()

        val ipFromCurrentConfig = current?.takeIf { it.config == vpnConfig }?.ip
        val ipFromLastStartedConfig = lastStarted
            ?.takeIf { it.country == resolvedCountry && it.config == vpnConfig }
            ?.ip

        val ip = when {
            !ipFromCurrentConfig.isNullOrBlank() -> ipFromCurrentConfig
            !ipFromLastStartedConfig.isNullOrBlank() -> ipFromLastStartedConfig
            !current?.ip.isNullOrBlank() -> current?.ip
            !lastStarted?.takeIf { it.country == resolvedCountry }?.ip.isNullOrBlank() -> lastStarted?.ip
            !lastSuccessfulIp.isNullOrBlank() -> lastSuccessfulIp
            else -> null
        }

        if (!ip.isNullOrBlank() && ip != selectedServerIp) {
            updateAddress(ip)
            applyServerSelectionLabel(resolvedCountry, ip)
        } else if (!selectedServerIp.isNullOrBlank()) {
            updateAddress(selectedServerIp)
        }
        updateServerPosition()
    }

    private fun updateStatusLabel(state: ConnectionState) {
        val statusRes = when (state) {
            ConnectionState.DISCONNECTED -> R.string.main_status_disconnected
            ConnectionState.CONNECTING -> R.string.main_status_connecting
            ConnectionState.CONNECTED -> R.string.main_status_connected
            ConnectionState.DISCONNECTING -> R.string.main_status_disconnecting
        }
        val baseStatus = context.getString(statusRes)
        val level = ConnectionStateManager.engineLevel.value
        val remaining = ServerAutoSwitcher.remainingSeconds.value
        val showCountdown = level == ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET ||
            level == ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED
        val statusText = if (state == ConnectionState.CONNECTING && remaining != null && showCountdown) {
            val suffix = runCatching { context.getString(R.string.state_countdown_seconds, remaining) }
                .getOrDefault("")
            "${useCase.trimEllipsis(baseStatus)}$suffix"
        } else {
            baseStatus
        }
        connectionDetailsListener?.updateStatus(statusText)
    }

    private fun updateButtonState(state: ConnectionState) {
        val detail = ConnectionStateManager.engineDetail.value
        val level = ConnectionStateManager.engineLevel.value
        val hint = ConnectionStateManager.reconnectingHint.value
        val remaining = ServerAutoSwitcher.remainingSeconds.value
        val connectButton = binding.startConnectionButton
        when (state) {
            ConnectionState.CONNECTED -> {
                connectButton.setText(R.string.stop_connection)
                val color = ContextCompat.getColor(context, R.color.connection_button_active)
                connectButton.backgroundTintList = ColorStateList.valueOf(color)
            }
            ConnectionState.DISCONNECTING -> {
                connectButton.setText(R.string.stop_connection)
                val color = ContextCompat.getColor(context, R.color.connection_button_active)
                connectButton.backgroundTintList = ColorStateList.valueOf(color)
            }
            ConnectionState.CONNECTING -> {
                val isTeardown = (level == ConnectionStatus.LEVEL_NOTCONNECTED &&
                    detail in setOf("NOPROCESS", "EXITING"))
                val t = if (ConnectionStateManager.reconnectingHint.value && isTeardown) {
                    engineDetailToText("RECONNECTING")
                } else {
                    val showGenericConnecting = (level == ConnectionStatus.LEVEL_NOTCONNECTED &&
                        detail in setOf(null, "NOPROCESS", "EXITING"))
                    engineDetailToText(if (showGenericConnecting) "CONNECTING" else detail)
                }
                connectButton.text = t
                val color = ContextCompat.getColor(context, R.color.connection_button_connecting)
                connectButton.backgroundTintList = ColorStateList.valueOf(color)
            }
            ConnectionState.DISCONNECTED -> {
                if (hint) {
                    val t = engineDetailToText("RECONNECTING")
                    connectButton.text = t
                    val color = ContextCompat.getColor(context, R.color.connection_button_connecting)
                    connectButton.backgroundTintList = ColorStateList.valueOf(color)
                } else {
                    connectButton.setText(R.string.start_connection)
                    val color = MaterialColors.getColor(
                        this,
                        androidx.appcompat.R.attr.colorPrimary,
                        ContextCompat.getColor(context, R.color.connection_button_disconnected)
                    )
                    connectButton.backgroundTintList = ColorStateList.valueOf(color)
                }
            }
        }
    }

    private fun engineDetailToText(detail: String?): CharSequence {
        val resId = useCase.mapEngineDetailToResId(detail)
        return resId?.let { context.getString(it) }
            ?: (detail ?: context.getString(R.string.vpn_notification_text_connecting))
    }

    private fun updateDurationTimer() {
        val start = ConnectionStateManager.connectionStartTimeMs.value
        val state = ConnectionStateManager.state.value
        val duration = if (state == ConnectionState.CONNECTED && start != null) {
            val elapsedSec = ((System.currentTimeMillis() - start) / 1000L).coerceAtLeast(0L)
            val hours = elapsedSec / 3600
            val minutes = (elapsedSec % 3600) / 60
            val seconds = elapsedSec % 60
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            DURATION_PLACEHOLDER
        }
        connectionDetailsListener?.updateDuration(duration)
    }

    private fun updateTraffic(downloaded: Long, uploaded: Long) {
        connectionDetailsListener?.updateTraffic(
            useCase.formatBytes(downloaded),
            useCase.formatBytes(uploaded)
        )
    }

    private fun updateLocationPlaceholders() {
        updateServerPosition()
        connectionDetailsListener?.updateAddress(selectedServerIp.orEmpty())
    }

    private fun updateAddress(ip: String?) {
        selectedServerIp = ip
        connectionDetailsListener?.updateAddress(ip.orEmpty())
    }

    private fun resolveIpForConfig(config: String?): String? {
        if (config.isNullOrBlank()) return selectedServerIp
        val current = runCatching { SelectedCountryStore.currentServer(context) }.getOrNull()
        if (current?.config == config && !current.ip.isNullOrBlank()) return current.ip
        val lastSuccessfulIp = runCatching { SelectedCountryStore.getLastSuccessfulIpForSelected(context) }.getOrNull()
        val lastSuccessfulConfig = runCatching { SelectedCountryStore.getLastSuccessfulConfigForSelected(context) }.getOrNull()
        if (!lastSuccessfulIp.isNullOrBlank() && lastSuccessfulConfig == config) return lastSuccessfulIp
        return runCatching { SelectedCountryStore.getIpForConfig(context, config) }.getOrNull()
            ?: selectedServerIp
    }

    private fun updateServerButtonIcons(showGlobe: Boolean) {
        val tint = MaterialColors.getColor(
            binding.serverSelectionContainer,
            com.google.android.material.R.attr.colorOnPrimary,
            ContextCompat.getColor(context, android.R.color.white)
        )
        val globe = if (showGlobe) {
            ContextCompat.getDrawable(context, R.drawable.ic_baseline_public_24)
        } else {
            null
        }
        val globeWrapped = globe?.let { DrawableCompat.wrap(it) }
        if (globeWrapped != null) {
            DrawableCompat.setTint(globeWrapped, tint)
        }

        val chevron = ContextCompat.getDrawable(context, R.drawable.ic_baseline_chevron_right_24)
        val chevronWrapped = chevron?.let { DrawableCompat.wrap(it) }
        if (chevronWrapped != null) {
            DrawableCompat.setTint(chevronWrapped, tint)
        }

        binding.serverSelectionContainer.icon = null
        binding.serverSelectionContainer.setCompoundDrawablesRelativeWithIntrinsicBounds(
            globeWrapped,
            null,
            chevronWrapped,
            null
        )
        binding.serverSelectionContainer.compoundDrawablePadding =
            resources.getDimensionPixelSize(R.dimen.server_item_margin)
    }

    private fun updateServerPosition() {
        val pos = runCatching { SelectedCountryStore.getCurrentPosition(context) }.getOrNull()
        val text = pos?.let { (index, total) ->
            context.getString(R.string.connection_detail_server_position, index, total)
        } ?: ""
        connectionDetailsListener?.updateCity(text)
    }

    interface ConnectionDetailsListener {
        fun updateDuration(text: String)
        fun updateTraffic(downloaded: String, uploaded: String)
        fun updateCity(city: String)
        fun updateAddress(address: String)
        fun updateStatus(text: String)
    }
}





