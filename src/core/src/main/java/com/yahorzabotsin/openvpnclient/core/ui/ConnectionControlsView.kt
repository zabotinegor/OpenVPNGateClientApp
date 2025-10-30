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
import kotlinx.coroutines.launch

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
                        Toast.makeText(context, "Please select a server first", Toast.LENGTH_SHORT).show()
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
            try { SelectedCountryStore.resetIndex(context) } catch (_: Exception) {}
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
    }

    private fun updateButtonState(state: ConnectionState) {
        Log.d(TAG, "Update button state: $state")
        val connectButton = binding.startConnectionButton
        when (state) {
            ConnectionState.CONNECTED -> {
                connectButton.setText(R.string.stop_connection)
                connectButton.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.red)
                )
            }
            ConnectionState.DISCONNECTED -> {
                connectButton.setText(R.string.start_connection)
                connectButton.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.green)
                )
            }
            ConnectionState.CONNECTING, ConnectionState.DISCONNECTING -> {
                connectButton.setText(R.string.stop_connection)
                connectButton.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.red)
                )
            }
        }
    }
}


