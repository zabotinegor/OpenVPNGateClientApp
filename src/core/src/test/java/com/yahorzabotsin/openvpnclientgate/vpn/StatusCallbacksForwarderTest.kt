package com.yahorzabotsin.openvpnclientgate.vpn

import android.content.Intent
import android.os.IBinder
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IStatusCallbacks
import de.blinkt.openvpn.core.LogItem
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class StatusCallbacksForwarderTest {
    private class RecordingCallbacks : IStatusCallbacks {
        val byteCounts = mutableListOf<Pair<Long, Long>>()
        val states = mutableListOf<String?>()

        override fun asBinder(): IBinder? = null
        override fun newLogItem(item: LogItem?) {}
        override fun updateStateString(
            state: String?, msg: String?, resid: Int, level: ConnectionStatus?, intent: Intent?
        ) { states.add(state) }
        override fun connectedVPN(uuid: String?) {}
        override fun notifyProfileVersionChanged(uuid: String?, profileVersion: Int) {}
        override fun updateByteCount(inBytes: Long, outBytes: Long) { byteCounts.add(inBytes to outBytes) }
    }

    @Test
    fun `attached delegate receives callbacks`() {
        val target = RecordingCallbacks()
        val forwarder = StatusCallbacksForwarder().apply { attach(target) }

        forwarder.updateByteCount(10, 20)
        forwarder.updateStateString("CONNECTED", null, 0, null, null)

        assertEquals(listOf(10L to 20L), target.byteCounts)
        assertEquals(listOf<String?>("CONNECTED"), target.states)
    }

    @Test
    fun `detached forwarder drops callbacks and releases the delegate`() {
        val target = RecordingCallbacks()
        val forwarder = StatusCallbacksForwarder().apply { attach(target) }

        forwarder.detach()
        forwarder.updateByteCount(1, 2)
        forwarder.updateStateString("LATE", null, 0, null, null)

        assertEquals(emptyList<Pair<Long, Long>>(), target.byteCounts)
        assertEquals(emptyList<String?>(), target.states)
    }

    @Test
    fun `callbacks before attach are dropped and never replayed`() {
        val forwarder = StatusCallbacksForwarder()
        val target = RecordingCallbacks()

        forwarder.updateByteCount(1, 2)
        forwarder.attach(target)

        assertEquals(emptyList<Pair<Long, Long>>(), target.byteCounts)
    }
}
