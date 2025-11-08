package com.yahorzabotsin.openvpnclient.vpn

import android.content.Context
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.StringReader

@RunWith(RobolectricTestRunner::class)
class VpnConfigurationTest {
    private lateinit var context: Context
    private lateinit var configParser: ConfigParser

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        configParser = ConfigParser()
    }

    @Test
    fun `parses basic OpenVPN configuration`() {
        val config = """
            client
            dev tun
            proto udp
            remote example.com 1194
            resolv-retry infinite
            nobind
            persist-key
            persist-tun
            ca ca.crt
            cert client.crt
            key client.key
            remote-cert-tls server
            cipher AES-256-CBC
            auth SHA256
            verb 3
        """.trimIndent()

        configParser.parseConfig(StringReader(config))
        val profile = configParser.convertProfile()

        assertEquals("example.com", profile.mConnections[0].mServerName)
        assertTrue(profile.mConnections[0].mUseUdp)
        assertEquals("1194", profile.mConnections[0].mServerPort)
        assertTrue(profile.mPersistTun)
        assertEquals("AES-256-CBC", profile.mCipher)
    }

    @Test
    fun `parses inline certificate configuration`() {
        val config = """
            client
            dev tun
            proto tcp
            remote example.com 443
            <ca>
            -----BEGIN CERTIFICATE-----
            MIIDQjCCAiqgAwIBAgIUJ2dPqvxGQ6RHcgVrwJQeNH5O4aswDQYJKoZIhvcNAQEL
            BQAwEzERMA8GA1UEAwwIQ2hhbmdlTWUwHhcNMjAxMjMxMjM1OTU5WhcNMzAxMjI5
            -----END CERTIFICATE-----
            </ca>
            <cert>
            -----BEGIN CERTIFICATE-----
            MIID0DCCArigAwIBAgIBADANBgkqhkiG9w0BAQsFADATMREwDwYDVQQDDAhDaGFu
            Z2VNZTAeFw0yMDEyMzEyMzU5NTlaFw0zMDEyMjkyMzU5NTlaMBMxETAPBgNVBAMM
            -----END CERTIFICATE-----
            </cert>
        """.trimIndent()

        configParser.parseConfig(StringReader(config))
        val profile = configParser.convertProfile()

        assertTrue(VpnProfile.isEmbedded(profile.mCaFilename))
        assertTrue(profile.mCaFilename.contains("-----BEGIN CERTIFICATE-----"))
        assertEquals("example.com", profile.mConnections[0].mServerName)
        assertEquals("443", profile.mConnections[0].mServerPort)
    }

    @Test
    fun `handles multiple remote servers configuration`() {
        val config = """
            client
            dev tun
            remote-random
            remote server1.example.com 1194
            remote server2.example.com 1194
            remote server3.example.com 1194
        """.trimIndent()

        configParser.parseConfig(StringReader(config))
        val profile = configParser.convertProfile()

        assertEquals(3, profile.mConnections.size)
        assertTrue(profile.mRemoteRandom)
        assertEquals("server1.example.com", profile.mConnections[0].mServerName)
        assertEquals("server2.example.com", profile.mConnections[1].mServerName)
        assertEquals("server3.example.com", profile.mConnections[2].mServerName)
    }

    @Test
    fun `parses user-auth configuration`() {
        val config = """
            client
            dev tun
            proto udp
            remote example.com 1194
            auth-user-pass
        """.trimIndent()

        configParser.parseConfig(StringReader(config))
        val profile = configParser.convertProfile()

        assertEquals(VpnProfile.TYPE_USERPASS, profile.mAuthenticationType)
    }

    @Test
    fun `handles compression settings`() {
        val config = """
            client
            dev tun
            proto udp
            remote example.com 1194
            comp-lzo
        """.trimIndent()

        configParser.parseConfig(StringReader(config))
        val profile = configParser.convertProfile()

        assertTrue(profile.mUseLzo)
    }

    @Test
    fun `generates valid OpenVPN configuration`() {
        val profile = VpnProfile("TestProfile")
        profile.mConnections = arrayOf(de.blinkt.openvpn.core.Connection())
        profile.mServerName = "example.com"
        profile.mConnections[0].mServerName = "example.com"
        profile.mConnections[0].mServerPort = "1194"
        profile.mConnections[0].mUseUdp = true
        profile.mUsePull = true
        profile.mPersistTun = true
        profile.mCipher = "AES-256-GCM"
        profile.mAuthenticationType = VpnProfile.TYPE_USERPASS
        profile.mUsername = "testuser"
        profile.mPassword = "testpass"
        
        val config = profile.getConfigFile(context, false)
        
        assertTrue(config.contains("remote example.com 1194"))
        // The underlying OpenVPN engine formats transport either as a separate
        // `proto udp` line or by appending `udp` on the `remote` line.
        // Accept both to keep the test resilient across engine versions.
        assertTrue(
            config.contains("\nproto udp") ||
            config.contains(" remote example.com 1194 udp") ||
            config.contains("remote example.com 1194 udp")
        )
        assertTrue(config.contains("persist-tun"))
        assertTrue(config.contains("cipher AES-256-GCM"))
        assertTrue(config.contains("auth-user-pass"))
        assertTrue(config.contains("client"))
    }

    @Test
    fun `handles unknown options as custom configuration`() {
        val invalidConfig = """
            invalid-option
            not-a-real-setting
        """.trimIndent()

        configParser.parseConfig(StringReader(invalidConfig))
        val profile = configParser.convertProfile()

        assertTrue(profile.mUseCustomConfig)
        assertTrue(profile.mCustomConfigOptions.contains("invalid-option"))
        assertTrue(profile.mCustomConfigOptions.contains("not-a-real-setting"))
    }
}
