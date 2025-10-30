package com.yahorzabotsin.openvpnclient.core.servers

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SelectionBootstrapTest {

    @Test
    fun uses_stored_selection_when_present() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val servers = listOf(
            Server("srv1","C1", Country("A"),10, SignalStrength.STRONG,"",0,0,0,0,0,0,"","","", "conf1"),
            Server("srv2","C2", Country("A"),20, SignalStrength.MEDIUM,"",0,0,0,0,0,0,"","","", "conf2")
        )
        SelectedCountryStore.saveSelection(ctx, "A", servers)

        var appliedCountry = ""
        var appliedCity = ""
        var appliedConfig = ""

        runBlockingCompat {
            SelectionBootstrap.ensureSelection(ctx, { emptyList() }) { c, city, conf ->
                appliedCountry = c; appliedCity = city; appliedConfig = conf
            }
        }

        assertEquals("A", appliedCountry)
        assertEquals("C1", appliedCity)
        assertEquals("conf1", appliedConfig)
    }

    @Test
    fun saves_default_country_when_no_selection() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val servers = listOf(
            Server("srv1","C1", Country("A"),10, SignalStrength.STRONG,"",0,0,0,0,0,0,"","","", "conf1"),
            Server("srv3","C3", Country("B"),30, SignalStrength.WEAK,"",0,0,0,0,0,0,"","","", "conf3"),
            Server("srv2","C2", Country("A"),20, SignalStrength.MEDIUM,"",0,0,0,0,0,0,"","","", "conf2")
        )

        var appliedCountry = ""
        var appliedCity = ""
        var appliedConfig = ""

        runBlockingCompat {
            SelectionBootstrap.ensureSelection(ctx, { servers }) { c, city, conf ->
                appliedCountry = c; appliedCity = city; appliedConfig = conf
            }
        }

        assertEquals("A", appliedCountry)
        assertEquals("C1", appliedCity)
        assertEquals("conf1", appliedConfig)

        val storedCountry = SelectedCountryStore.getSelectedCountry(ctx)
        assertEquals("A", storedCountry)
        val storedServers = SelectedCountryStore.getServers(ctx)
        assertEquals(2, storedServers.size)
    }
}

// Minimal runBlocking alternative without bringing kotlinx-coroutines-test
private fun runBlockingCompat(block: suspend () -> Unit) {
    kotlinx.coroutines.runBlocking { block() }
}
