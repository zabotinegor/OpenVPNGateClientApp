package com.yahorzabotsin.openvpnclientgate.core.dns

enum class DnsOption {
    SERVER, GOOGLE, CLOUDFLARE, QUAD9, OPENDNS, ADGUARD, CLEANBROWSING, DNSWATCH;

    companion object {
        private val NAME_MAP by lazy { values().associateBy(DnsOption::name) }
        fun fromString(name: String?): DnsOption = NAME_MAP[name] ?: SERVER
    }
}
