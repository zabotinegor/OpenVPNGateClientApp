package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Cycle identity: the auto-switch cycle and the refresh-time index restore
 * must follow server IDENTITY, never numeric position or IP, so a mid-cycle refresh cannot roll the
 * counter back, retry an attempted server or collapse same-IP entries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ServerCycleIdentityTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun srv(id: Int, config: String, ip: String = "9.9.9.9", country: String = "CountryA") = Server(
        lineIndex = 1, name = "n-$config", city = "city-$config", country = Country(country), ping = 1,
        signalStrength = SignalStrength.STRONG, ip = ip, score = 1, speed = 1, numVpnSessions = 1,
        uptime = 1, totalUsers = 1, totalTraffic = 1, logType = "", operator = "", message = "",
        configData = config, id = id
    )

    private fun stored(id: Int, config: String, ip: String? = "9.9.9.9") =
        StoredServer(city = "c", config = config, ip = ip, id = id)

    private fun configs() = SelectedCountryStore.getServers(ctx).map { it.config }
    private fun currentConfig() = SelectedCountryStore.currentServer(ctx)?.config

    /** Marks the persisted current server as attempted, like ServerAutoSwitcher does at cycle start. */
    private fun startCycle(): LinkedHashSet<String> =
        linkedSetOf(SelectedCountryStore.currentServerKey(ctx)!!)

    private fun advance(visited: MutableSet<String>): String? {
        val next = SelectedCountryStore.nextUnvisitedServer(ctx, visited) ?: return null
        visited.add(next.key)
        return next.server.config
    }

    /** Drives the cycle to exhaustion like ServerAutoSwitcher: each returned candidate is marked visited. */
    private fun drain(visited: MutableSet<String>): List<String> {
        val out = mutableListOf<String>()
        while (true) out.add(advance(visited) ?: return out)
    }

    // ---- identity scheme -------------------------------------------------------------------

    @Test
    fun keys_useServerIdWhenValidAndUnique() {
        val keys = ServerIdentity.keys(listOf(stored(7, "a"), stored(8, "b")))
        assertEquals(listOf("id:7", "id:8"), keys)
    }

    @Test
    fun keys_neverUseIpAlone_sameIpDifferentConfigsStayDistinct() {
        val keys = ServerIdentity.keys(listOf(stored(0, "cfg-1"), stored(0, "cfg-2"), stored(-1, "cfg-3")))
        assertEquals(3, keys.toSet().size)
    }

    @Test
    fun keys_fullDuplicatesGetOrdinalsAndFirstOccurrenceKeepsPlainKey() {
        val dupIds = ServerIdentity.keys(listOf(stored(5, "a"), stored(5, "a")))
        assertEquals(2, dupIds.toSet().size)
        val dupNoIds = ServerIdentity.keys(listOf(stored(0, "a"), stored(0, "a"), stored(0, "a")))
        assertEquals(3, dupNoIds.toSet().size)
        // Appending another duplicate must not rename the existing ones.
        val grown = ServerIdentity.keys(listOf(stored(0, "a"), stored(0, "a"), stored(0, "a"), stored(0, "a")))
        assertEquals(dupNoIds, grown.take(3))
    }

    /** A stored (base64) OpenVPN config for [port]; [revision] stands for re-issued key material. */
    private fun vpnConfig(port: Int, revision: String = "r1", proto: String = "udp") =
        android.util.Base64.encodeToString(
            "client\ndev tun\nproto $proto\nremote 9.9.9.9 $port\n<ca>\n$revision\n</ca>\n".toByteArray(),
            android.util.Base64.NO_WRAP
        )

    @Test
    fun keys_idlessEntriesFollowTheirEndpointNotTheConfigText() {
        val before = ServerIdentity.keys(listOf(stored(0, vpnConfig(1194)), stored(0, vpnConfig(443, proto = "tcp"))))
        val after = ServerIdentity.keys(listOf(stored(0, vpnConfig(1194, "r2")), stored(0, vpnConfig(443, "r2", "tcp"))))
        assertEquals(before, after)
        assertEquals(2, before.toSet().size)
    }

    @Test
    fun keys_idlessFallbackIsCollisionResistant_stableAcrossReorder() {
        // "AaAa" and "BBBB" share String.hashCode() and length; neither declares an endpoint.
        assertEquals("AaAa".hashCode(), "BBBB".hashCode())
        val a = ServerIdentity.keys(listOf(stored(0, "AaAa"), stored(0, "BBBB")))
        val b = ServerIdentity.keys(listOf(stored(0, "BBBB"), stored(0, "AaAa")))
        assertEquals(2, a.toSet().size)
        assertEquals("each config keeps its own key after a reorder", a[0], b[1])
        assertEquals(a[1], b[0])
    }

    @Test
    fun refreshReissuingEveryIdlessConfig_keepsTheSecondServerSelected() {
        SelectedCountryStore.saveSelection(ctx, "CountryA", listOf(srv(0, vpnConfig(1194)), srv(0, vpnConfig(1195))))
        SelectedCountryStore.setCurrentIndex(ctx, 1)

        SelectedCountryStore.saveSelectionPreservingIndex(
            ctx, "CountryA", listOf(srv(0, vpnConfig(1194, "r2")), srv(0, vpnConfig(1195, "r2")))
        )

        assertEquals(2 to 2, SelectedCountryStore.getCurrentPosition(ctx))
        assertEquals(vpnConfig(1195, "r2"), currentConfig())
    }

    @Test
    fun refreshReissuingEveryIdlessConfigMidCycle_neverRetriesAnAttemptedServer() {
        SelectedCountryStore.saveSelection(
            ctx, "CountryA", listOf(srv(0, vpnConfig(1)), srv(0, vpnConfig(2)), srv(0, vpnConfig(3)))
        )
        val visited = startCycle() // port 1
        assertEquals(vpnConfig(2), advance(visited))

        SelectedCountryStore.saveSelectionPreservingIndex(
            ctx, "CountryA", listOf(srv(0, vpnConfig(3, "r2")), srv(0, vpnConfig(2, "r2")), srv(0, vpnConfig(1, "r2")))
        )

        assertEquals(vpnConfig(2, "r2"), currentConfig())
        assertEquals(listOf(vpnConfig(3, "r2")), drain(visited))
    }

    @Test
    fun keys_survivePositionChanges() {
        val a = ServerIdentity.keys(listOf(stored(1, "a"), stored(0, "b"), stored(2, "c")))
        val b = ServerIdentity.keys(listOf(stored(2, "c"), stored(1, "a"), stored(0, "b")))
        assertEquals(a.toSet(), b.toSet())
        assertEquals(a[1], b[2])
    }

    // ---- cycle candidate selection ---------------------------------------------------------

    @Test
    fun nextUnvisited_walksAllSameIpEntriesWithoutCollapsing() {
        SelectedCountryStore.saveSelection(ctx, "CountryA", listOf(srv(0, "c1"), srv(0, "c2"), srv(0, "c3")))
        SelectedCountryStore.setCurrentIndex(ctx, 1)
        val visited = startCycle()

        assertEquals(listOf("c3", "c1"), drain(visited))
        assertNull(SelectedCountryStore.nextUnvisitedServer(ctx, visited))
    }

    @Test
    fun nextUnvisited_persistsIndexOfReturnedCandidate_uiCounterFollowsTarget() {
        SelectedCountryStore.saveSelection(ctx, "CountryA", listOf(srv(1, "c1"), srv(2, "c2"), srv(3, "c3")))
        val visited = startCycle()

        val next = SelectedCountryStore.nextUnvisitedServer(ctx, visited)!!
        assertEquals("c2", next.server.config)
        assertEquals(1, next.index)
        assertEquals(2 to 3, SelectedCountryStore.getCurrentPosition(ctx))
    }

    @Test
    fun nextUnvisited_outOfRangeIndexScansFromFirstServer() {
        SelectedCountryStore.saveSelection(ctx, "CountryA", listOf(srv(1, "c1"), srv(2, "c2")))
        SelectedCountryStore.prepareAutoSwitchFromStart(ctx)

        assertEquals("c1", SelectedCountryStore.nextUnvisitedServer(ctx, emptySet())?.server?.config)
    }

    // ---- refresh during an active cycle ----------------------------------------------------

    @Test
    fun refreshWithDuplicateIdEntries_keepsIndexOnTheSecondDuplicate() {
        val list = listOf(srv(5, "same"), srv(5, "same"), srv(6, "other"))
        SelectedCountryStore.saveSelection(ctx, "CountryA", list)
        SelectedCountryStore.setCurrentIndex(ctx, 1)

        SelectedCountryStore.saveSelectionPreservingIndex(ctx, "CountryA", list)

        assertEquals("indexOfFirst would have collapsed this onto position 0", 1, SelectedCountryStore.getCurrentIndex(ctx))
    }

    @Test
    fun refreshWithIdlessDuplicateEntries_keepsIndexOnTheSecondDuplicate() {
        val list = listOf(srv(0, "same"), srv(0, "same"), srv(0, "other"))
        SelectedCountryStore.saveSelection(ctx, "CountryA", list)
        SelectedCountryStore.setCurrentIndex(ctx, 1)

        SelectedCountryStore.saveSelectionPreservingIndex(ctx, "CountryA", list)

        assertEquals(1, SelectedCountryStore.getCurrentIndex(ctx))
    }

    @Test
    fun refreshWithChangedConfigText_followsServerIdNotConfig() {
        SelectedCountryStore.saveSelection(ctx, "CountryA", listOf(srv(1, "old-1"), srv(2, "old-2"), srv(3, "old-3")))
        SelectedCountryStore.setCurrentIndex(ctx, 1)

        // Same servers (same ids / same IP), different config representation.
        SelectedCountryStore.saveSelectionPreservingIndex(
            ctx, "CountryA", listOf(srv(1, "new-1"), srv(2, "new-2"), srv(3, "new-3"))
        )

        assertEquals("new-2", currentConfig())
        assertEquals(2 to 3, SelectedCountryStore.getCurrentPosition(ctx))
    }

    @Test
    fun refreshReorderMidCycle_doesNotRetryAttemptedServersOrFalseWrap() {
        SelectedCountryStore.saveSelection(ctx, "CountryA", listOf(srv(1, "A"), srv(2, "B"), srv(3, "C"), srv(4, "D")))
        val visited = startCycle() // A
        assertEquals("B", advance(visited))

        SelectedCountryStore.saveSelectionPreservingIndex(
            ctx, "CountryA", listOf(srv(4, "D"), srv(3, "C"), srv(2, "B"), srv(1, "A"))
        )

        assertEquals("the persisted index must follow B to its new position", "B", currentConfig())
        assertEquals(listOf("D", "C"), drain(visited))
    }

    @Test
    fun refreshAddingServersMidCycle_offersNewServerOnceAndStillTerminates() {
        SelectedCountryStore.saveSelection(ctx, "CountryA", listOf(srv(1, "A"), srv(2, "B")))
        val visited = startCycle()
        assertEquals("B", advance(visited)) // the cycle would have ended here

        SelectedCountryStore.saveSelectionPreservingIndex(
            ctx, "CountryA", listOf(srv(9, "NEW"), srv(1, "A"), srv(2, "B"))
        )

        assertEquals("B", currentConfig())
        assertEquals(listOf("NEW"), drain(visited))
    }

    @Test
    fun refreshRemovingUnvisitedServerMidCycle_endsCycleWithoutRetry() {
        SelectedCountryStore.saveSelection(ctx, "CountryA", listOf(srv(1, "A"), srv(2, "B"), srv(3, "C")))
        val visited = startCycle()
        assertEquals("B", advance(visited))

        // Count shrinks from 3 to 2: a position-based wrap check would conclude differently.
        SelectedCountryStore.saveSelectionPreservingIndex(ctx, "CountryA", listOf(srv(1, "A"), srv(2, "B")))

        assertEquals(emptyList<String>(), drain(visited))
    }

    @Test
    fun refreshRemovingCurrentServer_resumesAtItsSuccessorWithoutSkipping() {
        SelectedCountryStore.saveSelection(ctx, "CountryA", listOf(srv(1, "A"), srv(2, "B"), srv(3, "C"), srv(4, "D")))
        val visited = startCycle()
        assertEquals("B", advance(visited)) // B is the in-flight attempt

        SelectedCountryStore.saveSelectionPreservingIndex(
            ctx, "CountryA", listOf(srv(1, "A"), srv(3, "C"), srv(4, "D"))
        )

        assertEquals(listOf("C", "D"), drain(visited))
    }

    @Test
    fun refreshRemovingCurrentServerAtListHead_wrapsToItsSuccessor() {
        SelectedCountryStore.saveSelection(ctx, "CountryA", listOf(srv(1, "A"), srv(2, "B"), srv(3, "C")))
        val visited = startCycle() // A in flight, then removed

        SelectedCountryStore.saveSelectionPreservingIndex(ctx, "CountryA", listOf(srv(2, "B"), srv(3, "C")))

        assertEquals(listOf("B", "C"), drain(visited))
    }

    @Test
    fun refreshDuringCycle_uiCounterAndTargetStayConsistent() {
        SelectedCountryStore.saveSelection(ctx, "CountryA", listOf(srv(1, "A"), srv(2, "B"), srv(3, "C")))
        val visited = startCycle()
        val target = SelectedCountryStore.nextUnvisitedServer(ctx, visited)!!

        SelectedCountryStore.saveSelectionPreservingIndex(
            ctx, "CountryA", listOf(srv(7, "X"), srv(3, "C"), srv(2, "B"), srv(1, "A"))
        )

        val position = SelectedCountryStore.getCurrentPosition(ctx)!!
        assertEquals(target.server.config, SelectedCountryStore.getServers(ctx)[position.first - 1].config)
        assertEquals(4, position.second)
    }

    @Test
    fun refreshWhoseGuardRejects_leavesSelectionAndIndexUntouched() {
        SelectedCountryStore.saveSelection(ctx, "CountryA", listOf(srv(1, "A"), srv(2, "B")))
        SelectedCountryStore.setCurrentIndex(ctx, 1)

        SelectedCountryStore.saveSelectionPreservingIndex(ctx, "CountryA", listOf(srv(7, "Z"))) { false }

        assertEquals(listOf("A", "B"), configs())
        assertEquals(1, SelectedCountryStore.getCurrentIndex(ctx))
    }

    @Test
    fun refreshForSupersededCountry_doesNotOverwriteNewerSelection() {
        SelectedCountryStore.saveSelection(ctx, "CountryB", listOf(srv(8, "X"), srv(9, "Y")))
        SelectedCountryStore.setCurrentIndex(ctx, 1)

        // A background sync that captured CountryA lands after the user picked CountryB.
        SelectedCountryStore.saveSelectionPreservingIndex(ctx, "CountryA", listOf(srv(1, "A"), srv(2, "B")))

        assertEquals("CountryB", SelectedCountryStore.getSelectedCountry(ctx))
        assertEquals(listOf("X", "Y"), configs())
        assertEquals(1, SelectedCountryStore.getCurrentIndex(ctx))
    }

    @Test
    fun setCurrentIndexByKey_restoresByIdentity() {
        SelectedCountryStore.saveSelection(ctx, "CountryA", listOf(srv(0, "c1"), srv(0, "c2"), srv(0, "c3")))
        SelectedCountryStore.setCurrentIndex(ctx, 2)
        val key = SelectedCountryStore.currentServerKey(ctx)!!
        SelectedCountryStore.saveSelectionPreservingIndex(
            ctx, "CountryA", listOf(srv(0, "c3"), srv(0, "c1"), srv(0, "c2"))
        )
        SelectedCountryStore.setCurrentIndex(ctx, 1)

        assertTrue(SelectedCountryStore.setCurrentIndexByKey(ctx, key))
        assertEquals("c3", currentConfig())
        assertFalse(SelectedCountryStore.setCurrentIndexByKey(ctx, "id:12345"))
        assertEquals("c3", currentConfig())
    }
}
