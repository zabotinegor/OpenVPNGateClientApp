package com.yahorzabotsin.openvpnclient.core.ui

import android.content.Context
import android.content.res.ColorStateList
import android.net.VpnService
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.databinding.ViewConnectionControlsBinding
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

    private val binding: ViewConnectionControlsBinding

    private var vpnConfig: String? = null
    private val tag = ConnectionControlsView::class.simpleName

    private var requestVpnPermission: (() -> Unit)? = null

    init {
        binding = ViewConnectionControlsBinding.inflate(LayoutInflater.from(context), this)

        binding.startConnectionButton.setOnClickListener {
            Log.d(tag, "Connect button clicked. Current state: ${ConnectionStateManager.state.value}")
            when (ConnectionStateManager.state.value) {
                ConnectionState.DISCONNECTED -> {
                    if (vpnConfig != null) {
                        Log.d(tag, "Attempting to start VPN connection.")
                        prepareAndStartVpn()
                    } else {
                        Log.w(tag, "Connect button clicked, but no VPN config is set.")
                        Toast.makeText(context, "Please select a server first", Toast.LENGTH_SHORT).show()
                    }
                }
                ConnectionState.CONNECTED -> {
                    Log.d(tag, "Attempting to stop VPN connection.")
                    VpnManager.stopVpn(context)
                }
                else -> {
                    Log.w(tag, "Connect button clicked in an intermediate state: ${ConnectionStateManager.state.value}. No action taken.")
                }
            }
        }
    }

    /**
     * Programmatically triggers a click on the connection button.
     * Useful for re-triggering the connection flow after a permission request.
     */
    fun performConnectionClick() {
        binding.startConnectionButton.performClick()
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
        Log.d(tag, "Server updated. Country: $country, City: $city")
        binding.currentCountry.text = country
        binding.currentCity.text = city
    }

    fun setVpnConfig(config: String) {
        Log.d(tag, "Setting vpnConfig")
        this.vpnConfig = config
    }

    fun setLifecycleOwner(lifecycleOwner: LifecycleOwner) {
        Log.d(tag, "LifecycleOwner set, starting to observe connection state.")
        ConnectionStateManager.state
            .onEach { state -> updateButtonState(state) }
            .launchIn(lifecycleOwner.lifecycleScope)
    }

    private fun updateButtonState(state: ConnectionState) {
        Log.d(tag, "Updating button state for state: $state")
        val connectButton = binding.startConnectionButton
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
