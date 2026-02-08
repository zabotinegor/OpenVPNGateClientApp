package com.yahorzabotsin.openvpnclientgate.core.settings

import android.content.Context

interface DnsSettingsRepository {
    fun loadDnsOption(): DnsOption
    fun saveDnsOption(option: DnsOption)
}

class DefaultDnsSettingsRepository(
    private val appContext: Context
) : DnsSettingsRepository {
    override fun loadDnsOption(): DnsOption = UserSettingsStore.load(appContext).dnsOption

    override fun saveDnsOption(option: DnsOption) {
        UserSettingsStore.saveDnsOption(appContext, option)
    }
}
