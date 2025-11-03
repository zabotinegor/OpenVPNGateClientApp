package com.yahorzabotsin.openvpnclient.core.ui

import android.Manifest
import android.content.Context
import android.content.res.ColorStateList
import android.net.VpnService
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.databinding.ViewConnectionControlsBinding
import com.yahorzabotsin.openvpnclient.vpn.ConnectionState
import com.yahorzabotsin.openvpnclient.vpn.ConnectionStateManager
import com.yahorzabotsin.openvpnclient.vpn.VpnManager
import com.yahorzabotsin.openvpnclient.core.servers.SelectedCountryStore
import de.blinkt.openvpn.core.ConnectionStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine

  class ConnectionControlsView @JvmOverloads constructor(
      context: Context,
      attrs: AttributeSet? = null,
      defStyleAttr: Int = 0
  ) : ConstraintLayout(context, attrs, defStyleAttr) {

    private val binding: ViewConnectionControlsBinding
    private var vpnConfig: String? = null
    private var selectedCountry: String? = null

    private companion object {
        const val TAG = "ConnectionControlsView"
    }

    private var requestVpnPermission: (() -> Unit)? = null
    private var requestNotificationPermission: (() -> Unit)? = null

    init {
        binding = ViewConnectionControlsBinding.inflate(LayoutInflater.from(context), this)

        binding.startConnectionButton.setOnClickListener {
            Log.d(TAG, "Connect clicked. State=${ConnectionStateManager.state.value}")
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
            try { SelectedCountryStore.resetIndex(context) } catch (e: Exception) { Log.e(TAG, "Failed to reset server index", e) }
            VpnManager.startVpn(context, vpnConfig!!, selectedCountry)
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

    fun setServer(country: String, city: String) {
        Log.d(TAG, "Server set: $country, $city")
        binding.currentCountry.text = country
        binding.currentCity.text = city
        selectedCountry = country
    }

    fun setVpnConfig(config: String) {
        Log.d(TAG, "VPN config set")
        this.vpnConfig = config
    }

    fun setLifecycleOwner(lifecycleOwner: LifecycleOwner) {
        Log.d(TAG, "Observe connection state")
        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConnectionStateManager.state.collect { state ->
                    updateButtonState(state)
                }
            }
        }
        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    ConnectionStateManager.engineLevel,
                    ConnectionStateManager.engineDetail,
                    ConnectionStateManager.reconnectingHint
                ) { _, _, _ -> }
                    .collect {
                        val current = ConnectionStateManager.state.value
                        // When connecting, reflect granular engine changes; otherwise refresh using current state
                        updateButtonState(current)
                    }
            }
        }
    }

    private fun updateButtonState(state: ConnectionState) {
        val detail = ConnectionStateManager.engineDetail.value
        val level = ConnectionStateManager.engineLevel.value
        val hint = ConnectionStateManager.reconnectingHint.value
        Log.d(TAG, "Update button: state=$state level=$level detail=${detail ?: "<none>"} hint=$hint")
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
                val t = if (ConnectionStateManager.reconnectingHint.value &&
                    (level == ConnectionStatus.LEVEL_NOTCONNECTED || detail == "NOPROCESS" || detail == "EXITING")) {
                    engineDetailToText("RECONNECTING")
                } else engineDetailToText(detail)
                connectButton.text = t
                val color = ContextCompat.getColor(context, R.color.connection_button_connecting)
                connectButton.backgroundTintList = ColorStateList.valueOf(color)
                Log.d(TAG, "CONNECTING ui -> text='${t}' color=${color}")
            }
            ConnectionState.DISCONNECTED -> {
                if (hint) {
                    val t = engineDetailToText("RECONNECTING")
                    connectButton.text = t
                    val color = ContextCompat.getColor(context, R.color.connection_button_connecting)
                    connectButton.backgroundTintList = ColorStateList.valueOf(color)
                    Log.d(TAG, "DISCONNECTED masked as RECONNECTING -> text='${t}' color=${color}")
                } else {
                    connectButton.setText(R.string.start_connection)
                    val color = com.google.android.material.color.MaterialColors.getColor(
                        this,
                        androidx.appcompat.R.attr.colorPrimary,
                        ContextCompat.getColor(context, R.color.connection_button_disconnected)
                    )
                    connectButton.backgroundTintList = ColorStateList.valueOf(color)
                    Log.d(TAG, "DISCONNECTED ui -> text='START CONNECTION' color=${color}")
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


