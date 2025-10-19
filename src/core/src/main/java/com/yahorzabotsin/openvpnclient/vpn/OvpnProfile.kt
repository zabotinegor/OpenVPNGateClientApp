package com.yahorzabotsin.openvpnclient.vpn

data class OvpnProfile(
    var name: String? = null,
    var remote: String? = null,
    var port: Int? = null,
    var proto: String? = null,
    var dev: String? = null,
    var ca: String? = null,
    var cert: String? = null,
    var key: String? = null,
    var tlsAuth: String? = null,
    var tlsCrypt: String? = null,
) {
    val isValid: Boolean
        get() = remote != null && port != null && proto != null
}