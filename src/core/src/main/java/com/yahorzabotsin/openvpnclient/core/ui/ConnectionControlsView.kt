package com.yahorzabotsin.openvpnclient.core.ui

import android.content.Context
import android.content.res.ColorStateList
import android.net.VpnService
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.vpn.ConnectionState
import com.yahorzabotsin.openvpnclient.vpn.ConnectionStateManager
import com.yahorzabotsin.openvpnclient.vpn.VpnManager
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class ConnectionControlsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private val countryTextView: TextView
    private val cityTextView: TextView
    private val connectButton: Button

    private var vpnConfig: String? = null
    private val tag = "ConnectionControlsView"

    private var requestVpnPermission: (() -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_connection_controls, this, true)
        countryTextView = findViewById(R.id.current_country)
        cityTextView = findViewById(R.id.current_city)
        connectButton = findViewById(R.id.start_connection_button)

        connectButton.setOnClickListener {
            Log.d(tag, "Connect button clicked. Current state: ${ConnectionStateManager.state.value}")
            when (ConnectionStateManager.state.value) {
                ConnectionState.DISCONNECTED -> {
                    if (vpnConfig != null) {
                        prepareAndStartVpn()
                    } else {
                        Toast.makeText(context, "Please select a server first", Toast.LENGTH_SHORT).show()
                    }
                }
                ConnectionState.CONNECTED -> {
                    VpnManager.stopVpn(context)
                }
                else -> { /* No-op */ }
            }
        }
    }

    private fun prepareAndStartVpn() {
        // Check if we already have permission
        if (VpnService.prepare(context) == null) {
            Log.d(tag, "VPN permission already granted. Starting VPN.")
            VpnManager.startVpn(context, vpnConfig!!)
        } else {
            Log.d(tag, "VPN permission not granted. Requesting permission.")
            requestVpnPermission?.invoke()
        }
    }

    fun setVpnPermissionRequestHandler(handler: () -> Unit) {
        this.requestVpnPermission = handler
    }

    fun setServer(country: String, city: String) {
        countryTextView.text = country
        cityTextView.text = city
    }

    fun setVpnConfig(config: String) {
        Log.d(tag, "Setting vpnConfig")
        this.vpnConfig = config
    }

    fun setLifecycleOwner(lifecycleOwner: LifecycleOwner) {
        ConnectionStateManager.state
            .onEach { state -> updateButtonState(state) }
            .launchIn(lifecycleOwner.lifecycleScope)
    }

    private fun updateButtonState(state: ConnectionState) {
        Log.d(tag, "Updating button state for state: $state")
        when (state) {
            ConnectionState.CONNECTED -> {
                connectButton.setText(R.string.stop_connection)
                connectButton.backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.red))
            }
            ConnectionState.DISCONNECTED -> {
                connectButton.setText(R.string.start_connection)
                connectButton.backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.green))
            }
            else -> { /* No-op */ }
        }
    }
}