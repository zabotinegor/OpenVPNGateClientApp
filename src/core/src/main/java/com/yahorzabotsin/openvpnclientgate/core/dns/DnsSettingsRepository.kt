package com.yahorzabotsin.openvpnclientgate.core.dns

import android.content.Context
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore

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
