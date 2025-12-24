package com.yahorzabotsin.openvpnclientgate.vpn

import android.app.Application
import android.content.Intent
import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class VpnManagerTest {

    @Test
    fun startVpn_decodesBase64AndSetsExtras() {
        val app: Application = RuntimeEnvironment.getApplication()
        val plain = "remote example.com 1194"
        val encoded = Base64.encodeToString(plain.toByteArray(), Base64.DEFAULT)

        VpnManager.startVpn(app, encoded, displayName = "MyTitle")

        val shadowApp = Shadows.shadowOf(app)
        val started: Intent = shadowApp.nextStartedService
        assertNotNull(started)
        assertEquals(OpenVpnService::class.java.name, started.component?.className)

        val cfgKey = VpnManager.extraConfigKey(app)
        val titleKey = VpnManager.extraTitleKey(app)
        val actionKey = VpnManager.actionKey(app)
        assertEquals(plain, started.getStringExtra(cfgKey))
        assertEquals("MyTitle", started.getStringExtra(titleKey))
        assertEquals(VpnManager.ACTION_START, started.getStringExtra(actionKey))
    }

    @Test
    fun startVpn_acceptsPlainConfigIfNotBase64() {
        val app: Application = RuntimeEnvironment.getApplication()
        val plain = "not_base64!@#"

        VpnManager.startVpn(app, plain, displayName = null)

        val shadowApp = Shadows.shadowOf(app)
        val started: Intent = shadowApp.nextStartedService
        val cfgKey = VpnManager.extraConfigKey(app)
        val titleKey = VpnManager.extraTitleKey(app)
        assertEquals(plain, started.getStringExtra(cfgKey))
        // title not provided
        assertEquals(null, started.getStringExtra(titleKey))
    }

    @Test
    fun stopVpn_setsStopActionAndHint() {
        val app: Application = RuntimeEnvironment.getApplication()

        VpnManager.stopVpn(app, preserveReconnectHint = true)

        val shadowApp = Shadows.shadowOf(app)
        val started: Intent = shadowApp.nextStartedService
        val actionKey = VpnManager.actionKey(app)
        val hintKey = VpnManager.extraPreserveReconnectKey(app)
        assertEquals(VpnManager.ACTION_STOP, started.getStringExtra(actionKey))
        assertEquals(true, started.getBooleanExtra(hintKey, false))
    }
}

