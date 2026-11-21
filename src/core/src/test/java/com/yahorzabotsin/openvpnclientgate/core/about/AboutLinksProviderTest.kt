package com.yahorzabotsin.openvpnclientgate.core.about

import org.junit.Assert.assertEquals
import org.junit.Test

class AboutLinksProviderTest {

    @Test
    fun `about links use the canonical production website`() {
        val links = DefaultAboutLinksProvider().get()

        assertEquals("https://openvpngateclient.com", links.website)
        assertEquals("${links.website}/privacy-policy", links.privacyPolicy)
        assertEquals("${links.website}/terms-of-use", links.termsOfUse)
    }
}
