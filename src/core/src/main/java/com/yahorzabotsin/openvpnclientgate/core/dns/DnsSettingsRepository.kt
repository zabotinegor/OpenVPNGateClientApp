package com.yahorzabotsin.openvpnclientgate.core.dns

import android.content.Context
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore

interface DnsSettingsRepository {
    fun loadDnsOption(): DnsOption
    fun saveDnsOption(option: DnsOption)
}

class DefaultDnsSettingsRepository(
    context: Context
) : DnsSettingsRepository {
    private val appContext = context.applicationContext

    override fun loadDnsOption(): DnsOption = UserSettingsStore.load(appContext).dnsOption

    override fun saveDnsOption(option: DnsOption) {
        UserSettingsStore.saveDnsOption(appContext, option)
    }
}
