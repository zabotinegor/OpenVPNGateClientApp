package com.yahorzabotsin.openvpnclientgate.core.servers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConsumerRulesProguardScopeTest {

    @Test
    fun consumer_rules_keep_v2_models_with_minimal_scope() {
        val rulesFile = resolveConsumerRulesFile()
        assertTrue("consumer-rules.pro must exist in core module", rulesFile.isFile)

        val content = rulesFile.readText()

        assertTrue(content.contains("-keep class com.yahorzabotsin.openvpnclientgate.core.servers.CountryV2"))
        assertTrue(content.contains("-keep class com.yahorzabotsin.openvpnclientgate.core.servers.ServerV2"))
        assertTrue(content.contains("-keep class com.yahorzabotsin.openvpnclientgate.core.servers.ServersPageResponse"))

        assertFalse(content.contains("-keep class com.yahorzabotsin.openvpnclientgate.core.servers.CountryV2 { *; }"))
        assertFalse(content.contains("-keep class com.yahorzabotsin.openvpnclientgate.core.servers.ServerV2 { *; }"))
        assertFalse(content.contains("-keep class com.yahorzabotsin.openvpnclientgate.core.servers.ServersPageResponse { *; }"))
    }

    private fun resolveConsumerRulesFile(): File {
        val userDir = System.getProperty("user.dir")
        val candidates = listOf(
            File("consumer-rules.pro"),
            File("src/core/consumer-rules.pro"),
            File(userDir, "consumer-rules.pro"),
            File(userDir, "src/core/consumer-rules.pro"),
            File(userDir, "core/consumer-rules.pro"),
            File(userDir, "../core/consumer-rules.pro")
        )

        return candidates.firstOrNull { it.isFile }
            ?: error("consumer-rules.pro not found. Checked: ${candidates.joinToString { it.path }}")
    }
}