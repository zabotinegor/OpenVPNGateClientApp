package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Identity caching and the start/success identity persistence of [SelectedCountryStore]: derivation
 * counts on the connected-polling path, parked-cursor clearing, legacy prefs and atomic preference
 * edits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SelectedCountryStoreIdentityPersistenceTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val prefsName = "vpn_selection_prefs"
    private val country = "CountryA"

    @Before
    fun setUp() {
        rawPrefs().edit().clear().commit()
    }

    private fun rawPrefs() = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private fun vpnText(port: Int, host: String = "5.5.5.5") =
        "client\nproto udp\nremote $host $port\n"

    private fun encode(text: String) = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)

    private fun srv(config: String, ip: String = "5.5.5.5", id: Int = 0) = Server(
        lineIndex = 1, name = "n", city = "city", country = Country(country), ping = 1,
        signalStrength = SignalStrength.STRONG, ip = ip, score = 1, speed = 1, numVpnSessions = 1,
        uptime = 1, totalUsers = 1, totalTraffic = 1, logType = "", operator = "", message = "",
        configData = config, id = id
    )

    private fun select(vararg servers: Server, index: Int = 0) {
        SelectedCountryStore.saveSelection(ctx, country, servers.toList())
        SelectedCountryStore.setCurrentIndex(ctx, index)
    }

    private fun lastSuccessKey() = rawPrefs().getString("last_success_server_key", null)

    // ---- the connected polling path does no whole-list derivation -------------------------

    @Test
    fun connectedPolling_reusesCachedKeysAndNeverDerivesAgain() {
        select(srv(encode(vpnText(1001))), srv(encode(vpnText(1002))), srv(encode(vpnText(1003))), index = 1)
        SelectedCountryStore.saveLastStartedConfig(ctx, country, vpnText(1002), "5.5.5.5")
        val afterStart = SelectedCountryStore.identityKeyDerivationsForTest()

        repeat(150) {
            SelectedCountryStore.saveLastSuccessfulConfig(ctx, country, vpnText(1002), "5.5.5.5", alignIndex = false)
        }

        assertEquals("polling must not derive keys", afterStart, SelectedCountryStore.identityKeyDerivationsForTest())
        assertEquals(SelectedCountryStore.currentServerKey(ctx), lastSuccessKey())
    }

    @Test
    fun repeatedKeyReads_deriveOncePerDistinctPersistedList() {
        // A host used by no other test: the cache is process-wide, so a shared list could already be warm.
        select(srv(encode(vpnText(1001, "7.7.7.7")), ip = "7.7.7.7"), srv(encode(vpnText(1002, "7.7.7.7")), ip = "7.7.7.7"))
        val before = SelectedCountryStore.identityKeyDerivationsForTest()
        repeat(50) {
            SelectedCountryStore.currentServerKeys(ctx)
            SelectedCountryStore.currentServerKey(ctx)
            SelectedCountryStore.currentAttemptedServerKey(ctx)
        }
        assertEquals(1, SelectedCountryStore.identityKeyDerivationsForTest() - before)
    }

    @Test
    fun cache_invalidatesOnReorderAndOnContentChange() {
        val a = srv(encode(vpnText(1001)))
        val b = srv(encode(vpnText(1002)))
        select(a, b)
        val original = SelectedCountryStore.currentServerKeys(ctx)

        SelectedCountryStore.saveSelection(ctx, country, listOf(b, a))
        assertEquals(original.reversed(), SelectedCountryStore.currentServerKeys(ctx))

        // Same size and order, one endpoint changed: the id-less key must follow the new endpoint.
        SelectedCountryStore.saveSelection(ctx, country, listOf(b, srv(encode(vpnText(2002)))))
        val changed = SelectedCountryStore.currentServerKeys(ctx)
        assertEquals(original.reversed()[0], changed[0])
        assertNotEquals(original.reversed()[1], changed[1])
    }

    // ---- parked cursor clearing --------------------------------------------------------------

    /**
     * The current server [removed] disappears in a refresh. The cursor is parked on the predecessor of
     * its first survivor (wrapping), i.e. on the LAST of [survivors].
     */
    private fun parkOn(removed: Server, vararg survivors: Server) {
        select(removed, *survivors, index = 0)
        SelectedCountryStore.saveSelectionPreservingIndex(ctx, country, survivors.toList())
        assertEquals(survivors.size - 1, SelectedCountryStore.getCurrentIndex(ctx))
        assertNull(SelectedCountryStore.currentAttemptedServerKey(ctx))
    }

    @Test
    fun parked_startOfDecodedConfigWithoutRemoteLine_unparksByConfigEquivalence() {
        val decoded = "client\nverb 3\n"
        parkOn(srv("gone-config"), srv(encode(decoded)))
        SelectedCountryStore.saveLastStartedConfig(ctx, country, decoded, "5.5.5.5")
        assertEquals(SelectedCountryStore.currentServerKey(ctx), SelectedCountryStore.currentAttemptedServerKey(ctx))
    }

    @Test
    fun parked_startOfAnotherListedSameIpServer_movesToItAndRecordsItsKey() {
        val parkedText = vpnText(1001)
        val otherText = vpnText(1002)
        parkOn(srv("gone-config"), srv(encode(otherText)), srv(encode(parkedText)))
        // The started server is listed (matched by its decoded config), so it is proven and the
        // cursor follows it; the parked entry it left was never started.
        SelectedCountryStore.saveLastStartedConfig(ctx, country, otherText, "5.5.5.5", alignIndex = true)
        val otherKey = SelectedCountryStore.currentServerKeys(ctx)[0]
        assertEquals(0, SelectedCountryStore.getCurrentIndex(ctx))
        assertEquals(otherKey, SelectedCountryStore.currentAttemptedServerKey(ctx))
        assertEquals(otherKey, startedKey())
        SelectedCountryStore.saveLastSuccessfulConfig(ctx, country, otherText, "5.5.5.5", alignIndex = false)
        assertEquals(otherKey, lastSuccessKey())
    }

    @Test
    fun parked_sameConfigButDifferentIp_staysParked() {
        val decoded = vpnText(1001)
        parkOn(srv("gone-config"), srv(encode(decoded), ip = "5.5.5.5"))
        SelectedCountryStore.saveLastStartedConfig(ctx, country, decoded, "6.6.6.6", alignIndex = true)
        assertNull(SelectedCountryStore.currentAttemptedServerKey(ctx))
    }

    @Test
    fun parked_reconnectStartDoesNotUnparkAndRecordsNoIdentity() {
        val decoded = vpnText(1001)
        parkOn(srv("gone-config"), srv(encode(decoded)))
        SelectedCountryStore.saveLastStartedConfig(ctx, country, decoded, "5.5.5.5", alignIndex = false)
        assertNull(SelectedCountryStore.currentAttemptedServerKey(ctx))

        // The UI selection moves while the session connects; the success must not adopt it.
        SelectedCountryStore.saveSelectionPreservingIndex(
            ctx, country, listOf(srv(encode(decoded)), srv(encode(vpnText(1002))))
        )
        SelectedCountryStore.setCurrentIndex(ctx, 1)
        SelectedCountryStore.saveLastSuccessfulConfig(ctx, country, decoded, "5.5.5.5", alignIndex = false)
        assertNull("a start without identity stays without identity", lastSuccessKey())
    }

    // ---- captured start identity ---------------------------------------------------------------

    @Test
    fun legacyPrefsWithoutStartedKey_fallBackToTheCurrentAttemptedServer() {
        select(srv(encode(vpnText(1001))), srv(encode(vpnText(1002))), index = 1)
        rawPrefs().edit()
            .putString("last_started_config", vpnText(1002))
            .putString("last_started_country", country)
            .putString("last_started_ip", "5.5.5.5")
            .commit()
        assertFalse(rawPrefs().contains("last_started_server_key"))

        SelectedCountryStore.saveLastSuccessfulConfig(ctx, country, vpnText(1002), "5.5.5.5", alignIndex = false)

        assertEquals(SelectedCountryStore.currentServerKey(ctx), lastSuccessKey())
    }

    @Test
    fun successWithoutAnyStartRecord_usesTheCurrentAttemptedServer() {
        select(srv(encode(vpnText(1001))), srv(encode(vpnText(1002))), index = 1)

        // A direct caller with no start record at all keeps the live-selection fallback.
        SelectedCountryStore.saveLastSuccessfulConfig(ctx, country, vpnText(1002), "5.5.5.5", alignIndex = false)

        assertEquals(SelectedCountryStore.currentServerKey(ctx), lastSuccessKey())
    }

    @Test
    fun successNotMatchingTheRecordedStart_recordsNoIdentity() {
        select(srv(encode(vpnText(1001))), srv(encode(vpnText(1002))), index = 0)
        SelectedCountryStore.saveLastStartedConfig(ctx, country, vpnText(1001), "5.5.5.5")
        SelectedCountryStore.setCurrentIndex(ctx, 1)

        SelectedCountryStore.saveLastSuccessfulConfig(ctx, country, vpnText(1002), "5.5.5.5", alignIndex = false)

        assertNull(lastSuccessKey())
    }

    @Test
    fun newerStartOverwritesCapturedIdentity_staleSuccessSnapshotNeverPairsConfigWithAnotherAttemptsKey() {
        select(srv(encode(vpnText(1001))), srv(encode(vpnText(1002))), index = 0)
        val firstKey = SelectedCountryStore.currentServerKey(ctx)
        SelectedCountryStore.saveLastStartedConfig(ctx, country, vpnText(1001), "5.5.5.5")

        SelectedCountryStore.setCurrentIndex(ctx, 1)
        val secondKey = SelectedCountryStore.currentServerKey(ctx)
        SelectedCountryStore.saveLastStartedConfig(ctx, country, vpnText(1002), "5.5.5.5")

        // A snapshot of the first session arrives after the second start was recorded.
        SelectedCountryStore.saveLastSuccessfulConfig(ctx, country, vpnText(1001), "5.5.5.5", alignIndex = false)
        assertNull("a snapshot that is not the recorded start must not adopt any identity", lastSuccessKey())
        assertEquals(vpnText(1001), rawPrefs().getString("last_success_config", null))

        // The second session's own snapshot adopts its captured identity.
        SelectedCountryStore.setCurrentIndex(ctx, 0)
        SelectedCountryStore.saveLastSuccessfulConfig(ctx, country, vpnText(1002), "5.5.5.5", alignIndex = false)
        assertEquals(secondKey, lastSuccessKey())
        assertNotEquals(firstKey, lastSuccessKey())
    }

    // ---- IP-only alignment never creates an identity ---------------------------------------------

    private fun startedKey() = rawPrefs().getString("last_started_server_key", null)

    /** [X, Y] share an IP with different endpoints; P has another IP. X is removed and the cursor parks on P. */
    private fun parkedOnOtherIpWithSameIpSurvivor(): String {
        val xText = vpnText(1194, "1.1.1.1")
        val x = srv(encode(xText), ip = "1.1.1.1")
        val y = srv(encode(vpnText(443, "1.1.1.1")), ip = "1.1.1.1")
        val p = srv(encode(vpnText(1194, "2.2.2.2")), ip = "2.2.2.2")
        select(x, y, p, index = 0)
        SelectedCountryStore.saveLastStartedConfig(ctx, country, xText, "1.1.1.1")
        SelectedCountryStore.saveLastSuccessfulConfig(ctx, country, xText, "1.1.1.1", alignIndex = false)
        SelectedCountryStore.saveSelectionPreservingIndex(ctx, country, listOf(y, p))
        assertEquals(1, SelectedCountryStore.getCurrentIndex(ctx))
        assertNull(SelectedCountryStore.currentAttemptedServerKey(ctx))
        return xText
    }

    @Test
    fun parkedStartOfRemovedServer_ipOnlyMatchRecordsNoIdentityAndKeepsTheCursorParked() {
        val xText = parkedOnOtherIpWithSameIpSurvivor()

        SelectedCountryStore.saveLastStartedConfig(ctx, country, xText, "1.1.1.1", alignIndex = true)
        assertEquals("", startedKey())
        assertEquals(1, SelectedCountryStore.getCurrentIndex(ctx))
        assertNull(SelectedCountryStore.currentAttemptedServerKey(ctx))

        SelectedCountryStore.saveLastSuccessfulConfig(ctx, country, xText, "1.1.1.1", alignIndex = false)
        assertNull(lastSuccessKey())
    }

    @Test
    fun notParkedStart_ipOnlyAlignmentMovesTheCursorParkedAndRecordsNoIdentity() {
        val a = srv(encode(vpnText(1001, "3.3.3.3")), ip = "3.3.3.3")
        val y = srv(encode(vpnText(443, "1.1.1.1")), ip = "1.1.1.1")
        select(a, y, index = 0)

        SelectedCountryStore.saveLastStartedConfig(ctx, country, vpnText(1194, "1.1.1.1"), "1.1.1.1", alignIndex = true)

        assertEquals("the legacy alignment still positions the counter", 1, SelectedCountryStore.getCurrentIndex(ctx))
        assertNull("but the entry is not proven to be the started one", SelectedCountryStore.currentAttemptedServerKey(ctx))
        assertEquals("", startedKey())
    }

    @Test
    fun parkedStart_matchingTheSameIpSurvivorByEndpoint_capturesItsKeyAndUnparks() {
        parkedOnOtherIpWithSameIpSurvivor()
        // The cursor sits on P; starting P's own endpoint proves the parked entry was started.
        SelectedCountryStore.saveLastStartedConfig(ctx, country, vpnText(1194, "2.2.2.2"), "2.2.2.2", alignIndex = true)

        val pKey = SelectedCountryStore.currentServerKeys(ctx)[1]
        assertEquals(pKey, startedKey())
        assertEquals(pKey, SelectedCountryStore.currentAttemptedServerKey(ctx))
    }

    @Test
    fun parkedStart_idBackedSurvivorWithSameIpAndEndpointButOtherId_isNotProvenByEndpoint() {
        val removedText = vpnText(1194)
        val survivorText = removedText + "verb 4\n"
        parkOn(srv(encode(removedText), id = 11), srv(encode(survivorText), id = 12))

        SelectedCountryStore.saveLastStartedConfig(ctx, country, removedText, "5.5.5.5", alignIndex = true)

        assertEquals("", startedKey())
        assertNull(SelectedCountryStore.currentAttemptedServerKey(ctx))
        SelectedCountryStore.saveLastSuccessfulConfig(ctx, country, removedText, "5.5.5.5", alignIndex = false)
        assertNull(lastSuccessKey())
    }

    @Test
    fun parkedStart_idBackedSurvivorWithSameDecodedConfig_capturesItsIdKeyAndUnparks() {
        val text = vpnText(1194)
        parkOn(srv(encode(vpnText(1195)), id = 11), srv(encode(text), id = 12))

        SelectedCountryStore.saveLastStartedConfig(ctx, country, text, "5.5.5.5", alignIndex = true)

        assertEquals("id:12", startedKey())
        assertEquals("id:12", SelectedCountryStore.currentAttemptedServerKey(ctx))
    }

    // ---- an unparked cursor is held to the same proof as a parked one ----------------------------

    @Test
    fun unparkedSameIpCursorMovedAfterPrepare_isCorrectedToTheProvenStartedEntry() {
        val xText = vpnText(1194, "1.1.1.1")
        val x = srv(encode(xText), ip = "1.1.1.1")
        val z = srv(encode(vpnText(443, "1.1.1.1")), ip = "1.1.1.1")
        select(x, z, index = 0)
        // prepareStart captures X; the selection then moves to the unparked same-IP Z before the
        // service records the start.
        SelectedCountryStore.saveLastStartedConfig(ctx, country, xText, "1.1.1.1")
        val xKey = SelectedCountryStore.currentServerKeys(ctx)[0]
        assertEquals(xKey, startedKey())
        SelectedCountryStore.setCurrentIndex(ctx, 1)

        SelectedCountryStore.ensureIndexForConfig(ctx, xText, "1.1.1.1")
        SelectedCountryStore.saveLastStartedConfig(ctx, country, xText, "1.1.1.1", alignIndex = true)

        assertEquals(0, SelectedCountryStore.getCurrentIndex(ctx))
        assertEquals(xKey, startedKey())
        SelectedCountryStore.saveLastSuccessfulConfig(ctx, country, xText, "1.1.1.1", alignIndex = false)
        assertEquals(xKey, lastSuccessKey())
    }

    @Test
    fun unparkedSameIpCursor_startedConfigNotInTheList_recordsNoIdentityAndKeepsTheCursor() {
        val y = srv(encode(vpnText(443, "1.1.1.1")), ip = "1.1.1.1")
        val w = srv(encode(vpnText(80, "1.1.1.1")), ip = "1.1.1.1")
        select(y, w, index = 1)

        SelectedCountryStore.saveLastStartedConfig(ctx, country, vpnText(1194, "1.1.1.1"), "1.1.1.1", alignIndex = true)

        assertEquals("", startedKey())
        assertEquals(1, SelectedCountryStore.getCurrentIndex(ctx))
        SelectedCountryStore.saveLastSuccessfulConfig(ctx, country, vpnText(1194, "1.1.1.1"), "1.1.1.1", alignIndex = false)
        assertNull(lastSuccessKey())
    }

    @Test
    fun lastSuccessBehindAnEarlierSameIpSibling_isFoundByDecodedConfigAndKeepsItsIdentity() {
        val xText = vpnText(1194, "1.1.1.1")
        val y = srv(encode(vpnText(443, "1.1.1.1")), ip = "1.1.1.1")
        val x = srv(encode(xText), ip = "1.1.1.1")
        val p = srv(encode(vpnText(1194, "2.2.2.2")), ip = "2.2.2.2")
        select(y, x, p, index = 2)

        SelectedCountryStore.saveLastStartedConfig(ctx, country, xText, "1.1.1.1", alignIndex = true)

        val xKey = SelectedCountryStore.currentServerKeys(ctx)[1]
        assertEquals(1, SelectedCountryStore.getCurrentIndex(ctx))
        assertEquals(xKey, startedKey())
        assertEquals(xKey, SelectedCountryStore.currentAttemptedServerKey(ctx))
        SelectedCountryStore.saveLastSuccessfulConfig(ctx, country, xText, "1.1.1.1", alignIndex = false)
        assertEquals(xKey, lastSuccessKey())
    }

    @Test
    fun successWithoutStartRecord_doesNotAdoptACursorThatIsNotTheSucceededServer() {
        val y = srv(encode(vpnText(443, "1.1.1.1")), ip = "1.1.1.1")
        val x = srv(encode(vpnText(1194, "1.1.1.1")), ip = "1.1.1.1")
        select(y, x, index = 0)

        SelectedCountryStore.saveLastSuccessfulConfig(ctx, country, vpnText(1194, "1.1.1.1"), "1.1.1.1", alignIndex = false)

        assertNull(lastSuccessKey())
    }

    // ---- id-less duplicates, shared IP, restoration -----------------------------------------

    @Test
    fun idlessDuplicates_secondEntryIsRestoredAfterReconnectAndRelaunch() {
        val same = encode(vpnText(1001))
        select(srv(same), srv(same), index = 1)
        val secondKey = SelectedCountryStore.currentServerKey(ctx)
        assertTrue(secondKey!!.endsWith("#1"))
        SelectedCountryStore.saveLastStartedConfig(ctx, country, vpnText(1001), "5.5.5.5", alignIndex = false)
        SelectedCountryStore.saveLastSuccessfulConfig(ctx, country, vpnText(1001), "5.5.5.5", alignIndex = false)
        assertEquals(secondKey, lastSuccessKey())

        // Relaunch / reconnect: the index is reset, then restored from the persisted identity.
        SelectedCountryStore.setCurrentIndex(ctx, 0)
        assertTrue(SelectedCountryStore.restoreLastSuccessfulServerIndex(ctx))
        assertEquals(1, SelectedCountryStore.getCurrentIndex(ctx))
    }

    @Test
    fun lastSuccess_isRestoredByIdentityAfterARefreshReorder() {
        val a = srv(encode(vpnText(1001)))
        val b = srv(encode(vpnText(1002)))
        val c = srv(encode(vpnText(1003)))
        select(a, b, c, index = 2)
        SelectedCountryStore.saveLastStartedConfig(ctx, country, vpnText(1003), "5.5.5.5", alignIndex = false)
        SelectedCountryStore.saveLastSuccessfulConfig(ctx, country, vpnText(1003), "5.5.5.5", alignIndex = false)

        SelectedCountryStore.saveSelectionPreservingIndex(ctx, country, listOf(c, a, b))
        SelectedCountryStore.setCurrentIndex(ctx, 1)

        assertTrue(SelectedCountryStore.restoreLastSuccessfulServerIndex(ctx))
        assertEquals(0, SelectedCountryStore.getCurrentIndex(ctx))
    }

    @Test
    fun restoreLastSuccess_isFalseWhenTheServerWasRemoved() {
        val a = srv(encode(vpnText(1001)))
        val b = srv(encode(vpnText(1002)))
        select(a, b, index = 1)
        SelectedCountryStore.saveLastStartedConfig(ctx, country, vpnText(1002), "5.5.5.5", alignIndex = false)
        SelectedCountryStore.saveLastSuccessfulConfig(ctx, country, vpnText(1002), "5.5.5.5", alignIndex = false)
        SelectedCountryStore.saveSelectionPreservingIndex(ctx, country, listOf(a))
        assertFalse(SelectedCountryStore.restoreLastSuccessfulServerIndex(ctx))
    }

    // ---- preference consistency (single edit per logical update) ---------------------------------

    private class RecordingEditor(private val delegate: SharedPreferences.Editor, private val log: MutableList<Set<String>>) :
        SharedPreferences.Editor {
        private val touched = linkedSetOf<String>()
        private fun record(key: String): RecordingEditor { touched += key; return this }
        override fun putString(key: String, value: String?): SharedPreferences.Editor { delegate.putString(key, value); return record(key) }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor { delegate.putStringSet(key, values); return record(key) }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor { delegate.putInt(key, value); return record(key) }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor { delegate.putLong(key, value); return record(key) }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor { delegate.putFloat(key, value); return record(key) }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { delegate.putBoolean(key, value); return record(key) }
        override fun remove(key: String): SharedPreferences.Editor { delegate.remove(key); return record(key) }
        override fun clear(): SharedPreferences.Editor { delegate.clear(); return this }
        override fun commit(): Boolean { log += touched.toSet(); return delegate.commit() }
        override fun apply() { log += touched.toSet(); delegate.apply() }
    }

    private class RecordingPrefs(private val delegate: SharedPreferences, val log: MutableList<Set<String>>) :
        SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor = RecordingEditor(delegate.edit(), log)
    }

    private fun recordingContext(log: MutableList<Set<String>>): Context {
        val base = ctx
        val recording = RecordingPrefs(base.getSharedPreferences(prefsName, Context.MODE_PRIVATE), log)
        return object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == prefsName) recording else base.getSharedPreferences(name, mode)
        }
    }

    @Test
    fun lastSuccess_configCountryIpAndKeyAreOneEdit() {
        select(srv(encode(vpnText(1001))), srv(encode(vpnText(1002))), index = 1)
        SelectedCountryStore.saveLastStartedConfig(ctx, country, vpnText(1002), "5.5.5.5")
        val log = mutableListOf<Set<String>>()

        SelectedCountryStore.saveLastSuccessfulConfig(
            recordingContext(log), country, vpnText(1002), "5.5.5.5", alignIndex = false
        )

        val all = setOf("last_success_config", "last_success_country", "last_success_ip", "last_success_server_key")
        assertEquals(listOf(all), log)
    }

    @Test
    fun refreshRestore_indexAndParkedFlagAreCommittedTogether() {
        val a = srv(encode(vpnText(1001)))
        val b = srv(encode(vpnText(1002)))
        val c = srv(encode(vpnText(1003)))
        select(a, b, c, index = 0)
        val log = mutableListOf<Set<String>>()

        // A removed: the cursor is parked on the predecessor of its successor, in one edit.
        SelectedCountryStore.saveSelectionPreservingIndex(recordingContext(log), country, listOf(b, c))

        val indexEdits = log.filter { "selected_country_index" in it }
        assertTrue(indexEdits.isNotEmpty())
        indexEdits.forEach { assertTrue("index edit must carry the parked flag: $it", "selected_country_index_parked" in it) }
        assertEquals(2 to 2, SelectedCountryStore.getCurrentPosition(ctx))
        assertNull(SelectedCountryStore.currentAttemptedServerKey(ctx))
    }

    @Test
    fun parkedStart_unparkAndStartedIdentityAreOneEdit() {
        val decoded = vpnText(1001)
        parkOn(srv("gone-config"), srv(encode(decoded)))
        val log = mutableListOf<Set<String>>()

        SelectedCountryStore.saveLastStartedConfig(recordingContext(log), country, decoded, "5.5.5.5")

        val edit = log.single { "last_started_server_key" in it }
        assertTrue("selected_country_index_parked" in edit)
        assertTrue("last_started_config" in edit && "last_started_ip" in edit && "last_started_country" in edit)
    }
}
