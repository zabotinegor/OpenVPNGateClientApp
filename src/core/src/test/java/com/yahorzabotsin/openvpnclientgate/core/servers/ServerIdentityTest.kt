package com.yahorzabotsin.openvpnclientgate.core.servers

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

/** Encoding details of [ServerIdentity] that must stay byte-for-byte stable across releases. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ServerIdentityTest {

    private fun idless(config: String, ip: String = "1.1.1.1") =
        StoredServer(city = "c", config = config, ip = ip, id = 0)

    private fun reference(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun fallbackKey(config: String): String = ServerIdentity.keys(listOf(idless(config))).single()

    @Test
    fun sha256Fallback_matchesPublishedVectors() {
        assertEquals(
            "cfg:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad|1.1.1.1",
            fallbackKey("abc")
        )
        assertEquals(
            "cfg:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855|1.1.1.1",
            fallbackKey("")
        )
    }

    @Test
    fun sha256Fallback_isByteForByteTheOldFormatImplementation() {
        // Many inputs so digests with leading-zero bytes and nibbles (the padding risk) are covered.
        for (i in 0 until 600) {
            val config = "no-remote-$i-é中"
            assertEquals("cfg:${reference(config)}|1.1.1.1", fallbackKey(config))
        }
        assertTrue(
            "the sweep must include a digest with a zero leading nibble",
            (0 until 600).any { reference("no-remote-$it-é中").startsWith("0") }
        )
    }

    private val expected = "udp:@1.2.3.4:443:"

    @Test
    fun endpoint_acceptsEverySupportedRemoteLineVariant() {
        val variants = listOf(
            "proto udp\nremote 1.2.3.4 443\n",
            "proto udp\r\nremote 1.2.3.4 443\r\n",
            "proto udp\n   remote 1.2.3.4 443\n",
            "proto udp\n\tremote 1.2.3.4 443\n",
            "proto udp\n \t remote\t1.2.3.4\t443\n",
            "proto udp\n\n\nremote 1.2.3.4 443\n"
        )
        variants.forEach { assertEquals(it, expected, ServerIdentity.endpoint(it)) }
    }

    @Test
    fun endpoint_decodesBase64ConfigsIncludingWrappedAndCrlfOnes() {
        val text = "proto udp\r\nremote 1.2.3.4 443\r\n"
        val encoded = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)
        val wrapped = Base64.encodeToString(text.toByteArray(), Base64.DEFAULT)
        assertEquals(expected, ServerIdentity.endpoint(encoded))
        assertEquals(expected, ServerIdentity.endpoint(wrapped))
    }

    @Test
    fun endpoint_isNullWhenThereIsNoRealRemoteLine() {
        assertNull(ServerIdentity.endpoint(""))
        assertNull(ServerIdentity.endpoint("proto udp\nport 443\n"))
        // A "remote" keyword without a host on its line must not borrow the next line's token.
        assertNull(ServerIdentity.endpoint("remote\n1.2.3.4 443\n"))
        assertNull(ServerIdentity.endpoint("# remote 1.2.3.4 443\n"))
        assertNull(ServerIdentity.endpoint("not base64 at all !!"))
    }

    @Test
    fun sameConfig_matchesTheStoredTextOrItsBase64Decoding() {
        val decoded = "client\nverb 3\n"
        val encoded = Base64.encodeToString(decoded.toByteArray(), Base64.NO_WRAP)
        assertTrue(ServerIdentity.sameConfig(encoded, decoded))
        assertTrue(ServerIdentity.sameConfig(decoded, decoded))
        assertFalse(ServerIdentity.sameConfig(encoded, "client\nverb 4\n"))
        assertFalse(ServerIdentity.sameConfig("", decoded))
        assertFalse(ServerIdentity.sameConfig("not base64 !!", decoded))
    }
}
