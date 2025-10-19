package com.yahorzabotsin.openvpnclient.vpn

import java.io.InputStream
import java.io.InputStreamReader

object OvpnProfileParser {

    fun parse(inputStream: InputStream): OvpnProfile {
        val profile = OvpnProfile()
        val reader = InputStreamReader(inputStream)
        val lines = reader.readLines()

        var inCa = false
        var inCert = false
        var inKey = false
        var inTlsAuth = false
        var inTlsCrypt = false

        for (line in lines) {
            when {
                line.startsWith("remote ") -> {
                    val parts = line.split(" ")
                    if (parts.size >= 3) {
                        profile.remote = parts[1]
                        profile.port = parts[2].toIntOrNull()
                    }
                }
                line.startsWith("proto ") -> {
                    profile.proto = line.substringAfter("proto ")
                }
                line.startsWith("dev ") -> {
                    profile.dev = line.substringAfter("dev ")
                }
                line.startsWith("<ca>") -> inCa = true
                line.startsWith("</ca>") -> inCa = false
                line.startsWith("<cert>") -> inCert = true
                line.startsWith("</cert>") -> inCert = false
                line.startsWith("<key>") -> inKey = true
                line.startsWith("</key>") -> inKey = false
                line.startsWith("<tls-auth>") -> inTlsAuth = true
                line.startsWith("</tls-auth>") -> inTlsAuth = false
                line.startsWith("<tls-crypt>") -> inTlsCrypt = true
                line.startsWith("</tls-crypt>") -> inTlsCrypt = false
                else -> {
                    when {
                        inCa -> profile.ca = (profile.ca ?: "") + line + "\n"
                        inCert -> profile.cert = (profile.cert ?: "") + line + "\n"
                        inKey -> profile.key = (profile.key ?: "") + line + "\n"
                        inTlsAuth -> profile.tlsAuth = (profile.tlsAuth ?: "") + line + "\n"
                        inTlsCrypt -> profile.tlsCrypt = (profile.tlsCrypt ?: "") + line + "\n"
                    }
                }
            }
        }

        return profile
    }
}