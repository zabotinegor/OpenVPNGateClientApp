package com.yahorzabotsin.openvpnclient.vpn

data class OvpnProfile(
    val name: String?,
    val remote: String?,
    val port: Int?,
    val proto: String?,
    val dev: String?,
    val ca: String?,
    val cert: String?,
    val key: String?,
    val tlsAuth: String?,
    val tlsCrypt: String?,
) {
    val isValid: Boolean
        get() = remote != null && port != null && proto != null
}