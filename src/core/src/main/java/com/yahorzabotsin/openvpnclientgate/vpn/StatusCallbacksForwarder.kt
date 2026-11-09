package com.yahorzabotsin.openvpnclientgate.vpn

import android.content.Intent
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IStatusCallbacks
import de.blinkt.openvpn.core.LogItem

/**
 * The Binder registered with the engine's status service stays reachable (JNI global) for as long
 * as the remote process holds its proxy, which can outlive the registering Service. It therefore
 * delegates through a reference that [detach] clears, so a destroyed Service is never retained
 * by it.
 */
internal class StatusCallbacksForwarder : IStatusCallbacks.Stub() {
    @Volatile private var delegate: IStatusCallbacks? = null

    fun attach(target: IStatusCallbacks) { delegate = target }
    fun detach() { delegate = null }

    override fun newLogItem(item: LogItem?) { delegate?.newLogItem(item) }

    override fun updateStateString(
        state: String?, msg: String?, resid: Int, level: ConnectionStatus?, intent: Intent?
    ) { delegate?.updateStateString(state, msg, resid, level, intent) }

    override fun connectedVPN(uuid: String?) { delegate?.connectedVPN(uuid) }

    override fun notifyProfileVersionChanged(uuid: String?, profileVersion: Int) {
        delegate?.notifyProfileVersionChanged(uuid, profileVersion)
    }

    override fun updateByteCount(inBytes: Long, outBytes: Long) {
        delegate?.updateByteCount(inBytes, outBytes)
    }
}
