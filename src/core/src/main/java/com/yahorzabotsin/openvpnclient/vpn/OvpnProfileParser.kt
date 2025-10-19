package com.yahorzabotsin.openvpnclient.vpn

import android.util.Log
import java.io.InputStream

object OvpnProfileParser {

    private val TAG = OvpnProfileParser::class.simpleName

    private enum class ParseState {
        NONE,
        CA,
        CERT,
        KEY,
        TLS_AUTH,
        TLS_CRYPT
    }

    fun parse(inputStream: InputStream): OvpnProfile {
        Log.d(TAG, "Starting to parse OVPN profile...")

        var name: String? = null
        var remote: String? = null
        var port: Int? = null
        var proto: String? = null
        var dev: String? = null
        val ca = StringBuilder()
        val cert = StringBuilder()
        val key = StringBuilder()
        val tlsAuth = StringBuilder()
        val tlsCrypt = StringBuilder()

        var currentState = ParseState.NONE

        inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val trimmedLine = line.trim()
                if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) return@forEach

                when {
                    trimmedLine.startsWith("setenv PROFILE_NAME") -> name = trimmedLine.split("\"")[1]
                    trimmedLine.startsWith("remote ") -> {
                        val parts = trimmedLine.split(" ").filter { it.isNotBlank() }
                        if (parts.size >= 3) {
                            remote = parts[1]
                            port = parts[2].toIntOrNull()
                            if (port == null) Log.w(TAG, "Could not parse port from line: $trimmedLine")
                        } else {
                            Log.w(TAG, "Malformed remote line: $trimmedLine")
                        }
                    }
                    trimmedLine.startsWith("proto ") -> proto = trimmedLine.substringAfter("proto ")
                    trimmedLine.startsWith("dev ") -> dev = trimmedLine.substringAfter("dev ")

                    trimmedLine.startsWith("<ca>") -> currentState = ParseState.CA
                    trimmedLine.startsWith("</ca>") -> currentState = ParseState.NONE
                    trimmedLine.startsWith("<cert>") -> currentState = ParseState.CERT
                    trimmedLine.startsWith("</cert>") -> currentState = ParseState.NONE
                    trimmedLine.startsWith("<key>") -> currentState = ParseState.KEY
                    trimmedLine.startsWith("</key>") -> currentState = ParseState.NONE
                    trimmedLine.startsWith("<tls-auth>") -> currentState = ParseState.TLS_AUTH
                    trimmedLine.startsWith("</tls-auth>") -> currentState = ParseState.NONE
                    trimmedLine.startsWith("<tls-crypt>") -> currentState = ParseState.TLS_CRYPT
                    trimmedLine.startsWith("</tls-crypt>") -> currentState = ParseState.NONE

                    else -> {
                        when (currentState) {
                            ParseState.CA -> ca.append(line).append("\n")
                            ParseState.CERT -> cert.append(line).append("\n")
                            ParseState.KEY -> key.append(line).append("\n")
                            ParseState.TLS_AUTH -> tlsAuth.append(line).append("\n")
                            ParseState.TLS_CRYPT -> tlsCrypt.append(line).append("\n")
                            ParseState.NONE -> Log.w(TAG, "Unrecognized line: $trimmedLine")
                        }
                    }
                }
            }
        }

        val profile = OvpnProfile(
            name = name,
            remote = remote,
            port = port,
            proto = proto,
            dev = dev,
            ca = ca.toString().trim(),
            cert = cert.toString().trim(),
            key = key.toString().trim(),
            tlsAuth = tlsAuth.toString().trim(),
            tlsCrypt = tlsCrypt.toString().trim()
        )

        Log.i(TAG, "Successfully parsed OVPN profile: ${profile.name ?: "(no name)"}, remote: ${profile.remote}:${profile.port}")
        return profile
    }
}