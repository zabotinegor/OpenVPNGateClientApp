package com.yahorzabotsin.openvpnclient.core.ui

import android.Manifest
import android.content.Context
import android.content.res.ColorStateList
import android.net.VpnService
import android.text.style.TextAppearanceSpan
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.util.Locale
import com.google.android.material.color.MaterialColors
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.databinding.ViewConnectionControlsBinding
import com.yahorzabotsin.openvpnclient.core.net.IpInfo
import com.yahorzabotsin.openvpnclient.core.net.IpInfoService
import com.yahorzabotsin.openvpnclient.vpn.ConnectionState
import com.yahorzabotsin.openvpnclient.vpn.ConnectionStateManager
import com.yahorzabotsin.openvpnclient.vpn.VpnManager
import com.yahorzabotsin.openvpnclient.core.servers.SelectedCountryStore
import de.blinkt.openvpn.core.ConnectionStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import com.yahorzabotsin.openvpnclient.vpn.ServerAutoSwitcher

  class ConnectionControlsView @JvmOverloads constructor(
      context: Context,
      attrs: AttributeSet? = null,
      defStyleAttr: Int = 0
  ) : ConstraintLayout(context, attrs, defStyleAttr) {

    private val binding: ViewConnectionControlsBinding
    private var vpnConfig: String? = null
    private var selectedCountry: String? = null
    private var openServerList: (() -> Unit)? = null

    private companion object {
        const val TAG = "ConnectionControlsView"
    }

    private var requestVpnPermission: (() -> Unit)? = null
    private var requestNotificationPermission: (() -> Unit)? = null
    private var ipInfo: IpInfo? = null

    private fun durationTextView(): TextView? =
        rootView.findViewById(R.id.duration_value)

    private fun downloadedTextView(): TextView? =
        rootView.findViewById(R.id.downloaded_value)

    private fun uploadedTextView(): TextView? =
        rootView.findViewById(R.id.uploaded_value)

    private fun cityTextView(): TextView? =
        rootView.findViewById(R.id.city_value)

    private fun addressTextView(): TextView? =
        rootView.findViewById(R.id.address_value)

    private fun statusTextView(): TextView? =
        rootView.findViewById(R.id.status_value)

    init {
        binding = ViewConnectionControlsBinding.inflate(LayoutInflater.from(context), this)

        applyServerSelectionLabel(
            context.getString(R.string.current_country),
            context.getString(R.string.current_city)
        )

        binding.startConnectionButton.setOnClickListener {
            when (ConnectionStateManager.state.value) {
                ConnectionState.DISCONNECTED -> {
                    if (vpnConfig != null) {
                        Log.d(TAG, "Start VPN requested")
                        prepareAndStartVpn()
                    } else {
                        Toast.makeText(context, R.string.select_server_first, Toast.LENGTH_SHORT).show()
                        Log.d(TAG, "Connect clicked but no VPN config; ignoring")
                    }
                }
                ConnectionState.CONNECTED -> {
                    Log.d(TAG, "Stop VPN requested")
                    VpnManager.stopVpn(context)
                }
                ConnectionState.CONNECTING, ConnectionState.DISCONNECTING -> {
                    Log.d(TAG, "Cancel while ${ConnectionStateManager.state.value}; stopping VPN")
                    VpnManager.stopVpn(context)
                }
            }
        }

        binding.serverSelectionContainer.setOnClickListener {
            Log.d(TAG, "Server selection container clicked")
            openServerList?.invoke()
        }
    }

      fun performConnectionClick() {
          binding.startConnectionButton.performClick()
      }

      fun requestPrimaryFocus() {
          binding.startConnectionButton.isFocusable = true
          binding.startConnectionButton.isFocusableInTouchMode = true
          binding.startConnectionButton.requestFocus()
      }

    private fun prepareAndStartVpn() {
        val needNotificationPermission = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        val hasNotificationPermission = if (needNotificationPermission) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true

        if (!hasNotificationPermission) {
            Log.d(TAG, "POST_NOTIFICATIONS not granted; requesting")
            requestNotificationPermission?.invoke()
            return
        }

        if (VpnService.prepare(context) == null) {
            Log.d(TAG, "VPN permission granted; starting VPN")
            val configToUse = run {
                val lastSuccessfulConfig = try {
                    SelectedCountryStore.getLastSuccessfulConfigForSelected(context)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to resolve last successful config; falling back to current selection", e)
                    null
                }
                if (lastSuccessfulConfig != null) {
                    try {
                        SelectedCountryStore.prepareAutoSwitchFromStart(context)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to prepare index for auto-switch from start", e)
                    }
                    lastSuccessfulConfig
                } else {
                    try {
                        SelectedCountryStore.resetIndex(context)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to reset server index", e)
                    }
                    vpnConfig!!
                }
            }
            Log.d(TAG, "Starting VPN with ${if (configToUse == vpnConfig) "current selection" else "last successful config"}")
            VpnManager.startVpn(context, configToUse, selectedCountry)
        } else {
            Log.d(TAG, "VPN permission not granted; requesting")
            requestVpnPermission?.invoke()
        }
    }

    fun setVpnPermissionRequestHandler(handler: () -> Unit) {
        this.requestVpnPermission = handler
    }

    fun setNotificationPermissionRequestHandler(handler: () -> Unit) {
        this.requestNotificationPermission = handler
    }

    fun setOpenServerListHandler(handler: () -> Unit) {
        this.openServerList = handler
    }

    fun setServer(country: String, city: String) {
        Log.d(TAG, "Server set: $country, $city")
        applyServerSelectionLabel(country, city)
        selectedCountry = country
        if (ConnectionStateManager.state.value == ConnectionState.DISCONNECTED) {
            ipInfo = null
            updateLocationPlaceholders()
        }
    }

    fun setVpnConfig(config: String) {
        Log.d(TAG, "VPN config set")
        this.vpnConfig = config
    }

    fun setLifecycleOwner(lifecycleOwner: LifecycleOwner) {
        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConnectionStateManager.state.collect { state ->
                    updateStatusLabel(state)
                    updateButtonState(state)
                    when (state) {
                        ConnectionState.CONNECTED -> {
                            lifecycleOwner.lifecycleScope.launch {
                                val info = try {
                                    IpInfoService.fetchPublicIpInfo()
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to fetch IP info", e)
                                    null
                                }
                                if (info != null) {
                                    ipInfo = info
                                    updateLocationPlaceholders()
                                }
                            }
                        }
                        ConnectionState.DISCONNECTED -> {
                            ipInfo = null
                            updateLocationPlaceholders()
                        }
                        else -> Unit
                    }
                }
            }
        }
        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    ConnectionStateManager.engineLevel,
                    ConnectionStateManager.engineDetail,
                    ConnectionStateManager.reconnectingHint,
                    ServerAutoSwitcher.remainingSeconds
                ) { _, _, _, _ -> }
                    .collect {
                        val current = ConnectionStateManager.state.value
                        updateButtonState(current)
                    }
            }
        }
        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    updateDurationTimer()
                    delay(1000L)
                }
            }
        }
        lifecycleOwner.lifecycleScope.launch {
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

    private fun applyServerSelectionLabel(country: String, city: String) {
        val primary = country.ifBlank { context.getString(R.string.current_country) }
        val secondary = city.ifBlank { context.getString(R.string.current_city) }
        binding.serverSelectionContainer.text = buildServerSelectionLabel(primary, secondary)
        val description = listOf(primary)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(separator = ", ")
        binding.serverSelectionContainer.contentDescription = description
        updateServerButtonIcons()
    }

    private fun buildServerSelectionLabel(country: String, city: String): CharSequence =
        buildSpannedString {
            inSpans(TextAppearanceSpan(context, R.style.TextAppearance_OpenVPNClient_Body)) {
                append(country.trim())
            }
        }

    private fun updateServerButtonIcons() {
        val defaultTint = ContextCompat.getColor(context, R.color.text_color_primary)
        val tint = MaterialColors.getColor(
            binding.serverSelectionContainer,
            com.google.android.material.R.attr.colorOnSurface,
            defaultTint
        )
        val globe = ContextCompat.getDrawable(context, R.drawable.ic_baseline_public_24)?.mutate()
        val chevron = ContextCompat.getDrawable(context, R.drawable.ic_baseline_chevron_right_24)?.mutate()
        globe?.let { DrawableCompat.setTint(it, tint) }
        chevron?.let { DrawableCompat.setTint(it, tint) }
        binding.serverSelectionContainer.setCompoundDrawablesRelativeWithIntrinsicBounds(
            globe,
            null,
            chevron,
            null
        )
    }

    private fun updateDurationTimer() {
        val start = ConnectionStateManager.connectionStartTimeMs.value
        if (start == null) {
            durationTextView()?.text = context.getString(R.string.main_duration_default)
            return
        }
        val elapsedSec = ((System.currentTimeMillis() - start) / 1000L).coerceAtLeast(0)
        val hours = elapsedSec / 3600
        val minutes = (elapsedSec % 3600) / 60
        val seconds = elapsedSec % 60
        val formatted = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        durationTextView()?.text = formatted
    }

    private fun updateTraffic(downloaded: Long, uploaded: Long) {
        downloadedTextView()?.text = formatBytes(downloaded)
        uploadedTextView()?.text = formatBytes(uploaded)
    }

    private fun formatBytes(value: Long): String {
        val abs = value.coerceAtLeast(0L).toDouble()
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        val (amount, unitResId) = when {
            abs >= gb -> abs / gb to R.string.traffic_unit_gb
            abs >= mb -> abs / mb to R.string.traffic_unit_mb
            abs >= kb -> abs / kb to R.string.traffic_unit_kb
            else -> abs to R.string.traffic_unit_b
        }
        val unit = context.getString(unitResId)
        val number = when {
            amount >= 100 -> String.format(Locale.US, "%.0f", amount)
            amount >= 10 -> String.format(Locale.US, "%.1f", amount)
            else -> String.format(Locale.US, "%.2f", amount)
        }
        return "$number $unit"
    }

    private fun updateLocationPlaceholders() {
        val cityView = cityTextView()
        val addressView = addressTextView()
        val info = ipInfo
        if (info != null) {
            cityView?.text = info.city.orEmpty()
            addressView?.text = info.ip
        } else {
            cityView?.text = ""
            addressView?.text = ""
        }
    }

    private fun updateStatusLabel(state: ConnectionState) {
        val statusRes = when (state) {
            ConnectionState.DISCONNECTED -> R.string.main_status_disconnected
            ConnectionState.CONNECTING -> R.string.main_status_connecting
            ConnectionState.CONNECTED -> R.string.main_status_connected
            ConnectionState.DISCONNECTING -> R.string.main_status_disconnecting
        }
        statusTextView()?.text = context.getString(statusRes)
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
                val showCountdown = level == ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET ||
                        level == ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED
                val suffix = if (remaining != null && showCountdown) {
                    try {
                        context.getString(R.string.state_countdown_seconds, remaining)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to format countdown string", e)
                        ""
                    }
                } else ""
                val textWithTimer = "$t$suffix"
                connectButton.text = textWithTimer
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
                    val color = com.google.android.material.color.MaterialColors.getColor(
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
        val resId = when (detail) {
            "CONNECTING" -> R.string.state_connecting
            "WAIT" -> R.string.state_wait
            "AUTH" -> R.string.state_auth
            "VPN_GENERATE_CONFIG" -> R.string.building_configuration
            "GET_CONFIG" -> R.string.state_get_config
            "ASSIGN_IP" -> R.string.state_assign_ip
            "ADD_ROUTES" -> R.string.state_add_routes
            "CONNECTED" -> R.string.state_connected
            "DISCONNECTED" -> R.string.state_disconnected
            "CONNECTRETRY" -> R.string.state_reconnecting
            "RECONNECTING" -> R.string.state_reconnecting
            "EXITING" -> R.string.state_exiting
            "RESOLVE" -> R.string.state_resolve
            "TCP_CONNECT" -> R.string.state_tcp_connect
            "AUTH_PENDING" -> R.string.state_auth_pending
            else -> null
        }
        return if (resId != null) context.getString(resId) else (detail ?: context.getString(R.string.vpn_notification_text_connecting))
    }
}


