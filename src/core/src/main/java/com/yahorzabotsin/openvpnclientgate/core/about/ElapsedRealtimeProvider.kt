package com.yahorzabotsin.openvpnclientgate.core.about

interface ElapsedRealtimeProvider {
    fun elapsedRealtimeMs(): Long
}

class SystemElapsedRealtimeProvider : ElapsedRealtimeProvider {
    override fun elapsedRealtimeMs(): Long = android.os.SystemClock.elapsedRealtime()
}
