package com.yahorzabotsin.openvpnclientgate.core.ui.dns

import android.util.Log
import com.yahorzabotsin.openvpnclientgate.core.logging.LogTags
import com.yahorzabotsin.openvpnclientgate.core.dns.DnsOption

interface DnsLogger {
    fun logScreenOpened(providersCount: Int, currentOption: DnsOption)
    fun logSelectionChanged(old: DnsOption, selected: DnsOption, label: String)
}

class DefaultDnsLogger : DnsLogger {
    private val tag = LogTags.APP + ':' + "DnsActivity"

    override fun logScreenOpened(providersCount: Int, currentOption: DnsOption) {
        Log.i(tag, "DNS screen opened: providers=$providersCount, current=${currentOption.name}")
    }

    override fun logSelectionChanged(old: DnsOption, selected: DnsOption, label: String) {
        Log.i(tag, "DNS selection changed: ${old.name} -> ${selected.name} (${label})")
    }
}
