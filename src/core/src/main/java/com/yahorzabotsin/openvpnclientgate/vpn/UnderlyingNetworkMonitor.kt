package com.yahorzabotsin.openvpnclientgate.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.logging.LogTags
import java.util.concurrent.ConcurrentHashMap

/**
 * Tells whether the device has a usable underlying (non-VPN) network.
 *
 * Used to classify an engine LEVEL_NONETWORK report: with no usable network the session waits for
 * the network instead of burning through servers; with a usable network the report is treated as a
 * recoverable failure and goes through the regular status-timeout switch.
 */
interface UnderlyingNetworkMonitor {
    /** Fresh, decision-time answer. Never relies on a possibly missed callback alone. */
    fun isUsableNetworkAvailable(): Boolean

    /** Listener for changes of the callback-tracked usability, always invoked on the main thread. */
    fun setListener(listener: ((Boolean) -> Unit)?)

    fun start()

    fun stop()
}

/**
 * [UnderlyingNetworkMonitor] backed by [ConnectivityManager].
 *
 * A network is usable when it has INTERNET, NOT_VPN and VALIDATED. NOT_VPN is required because the
 * app itself runs under its own VPN: `activeNetwork` would return the tunnel network here.
 */
class ConnectivityUnderlyingNetworkMonitor(
    private val connectivityManager: ConnectivityManager?,
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) : UnderlyingNetworkMonitor {

    constructor(context: Context) : this(
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    )

    private val usableNetworks: MutableSet<Network> = ConcurrentHashMap.newKeySet()
    private val lock = Any()
    @Volatile private var listener: ((Boolean) -> Unit)? = null
    @Volatile private var lastReported: Boolean? = null
    @Volatile private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = runCatching { connectivityManager?.getNetworkCapabilities(network) }.getOrNull()
            track(network, caps)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            track(network, networkCapabilities)
        }

        override fun onLost(network: Network) {
            usableNetworks.remove(network)
            reportIfChanged()
        }
    }

    override fun isUsableNetworkAvailable(): Boolean {
        val cm = connectivityManager ?: return true
        return try {
            @Suppress("DEPRECATION")
            cm.allNetworks.any { isUsable(cm.getNetworkCapabilities(it)) }
        } catch (e: Exception) {
            AppLog.w(TAG, "Fresh underlying network check failed; using callback state", e)
            if (registered) usableNetworks.isNotEmpty() else true
        }
    }

    override fun setListener(listener: ((Boolean) -> Unit)?) {
        this.listener = listener
    }

    override fun start() {
        val cm = connectivityManager ?: return
        synchronized(lock) {
            if (registered) return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    cm.registerNetworkCallback(request, callback, mainHandler)
                } else {
                    cm.registerNetworkCallback(request, callback)
                }
                registered = true
                AppLog.d(TAG, "Underlying network monitor started")
            } catch (e: Exception) {
                AppLog.w(TAG, "Failed to register underlying network callback", e)
            }
        }
    }

    override fun stop() {
        val cm = connectivityManager ?: return
        synchronized(lock) {
            if (!registered) return
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                AppLog.w(TAG, "Failed to unregister underlying network callback", e)
            }
            registered = false
            usableNetworks.clear()
            lastReported = null
            AppLog.d(TAG, "Underlying network monitor stopped")
        }
    }

    private fun track(network: Network, caps: NetworkCapabilities?) {
        if (isUsable(caps)) usableNetworks.add(network) else usableNetworks.remove(network)
        reportIfChanged()
    }

    private fun reportIfChanged() {
        val usable = usableNetworks.isNotEmpty()
        if (lastReported == usable) return
        lastReported = usable
        AppLog.i(TAG, "Underlying network usable=$usable")
        val l = listener ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            l(usable)
        } else {
            mainHandler.post { listener?.invoke(usable) }
        }
    }

    internal fun callbackForTest(): ConnectivityManager.NetworkCallback = callback

    companion object {
        private val TAG = LogTags.APP + ':' + "UnderlyingNetwork"

        internal fun isUsable(caps: NetworkCapabilities?): Boolean {
            if (caps == null) return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }
}
