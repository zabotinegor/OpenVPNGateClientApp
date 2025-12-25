package com.yahorzabotsin.openvpnclientgate.core.settings

data class DnsConfig(
    val overrideDns: Boolean,
    val primary: String? = null,
    val secondary: String? = null
)

data class DnsProvider(
    val option: DnsOption,
    val label: String,
    val primary: String,
    val secondary: String
)

object DnsOptions {
    val providers: List<DnsProvider> = listOf(
        DnsProvider(DnsOption.GOOGLE, "Google Public DNS", "8.8.8.8", "8.8.4.4"),
        DnsProvider(DnsOption.CLOUDFLARE, "Cloudflare", "1.1.1.1", "1.0.0.1"),
        DnsProvider(DnsOption.QUAD9, "Quad9", "9.9.9.9", "149.112.112.112"),
        DnsProvider(DnsOption.OPENDNS, "OpenDNS", "208.67.222.222", "208.67.220.220"),
        DnsProvider(DnsOption.ADGUARD, "AdGuard DNS", "94.140.14.14", "94.140.15.15"),
        DnsProvider(DnsOption.CLEANBROWSING, "CleanBrowsing", "185.228.168.9", "185.228.169.9"),
        DnsProvider(DnsOption.DNSWATCH, "DNS.Watch", "84.200.69.80", "84.200.70.40")
    )

    fun resolve(option: DnsOption): DnsConfig = when (option) {
        DnsOption.SERVER -> DnsConfig(overrideDns = false)
        else -> {
            val provider = providers.firstOrNull { it.option == option }
            if (provider == null) {
                DnsConfig(overrideDns = false)
            } else {
                DnsConfig(true, provider.primary, provider.secondary)
            }
        }
    }
}

