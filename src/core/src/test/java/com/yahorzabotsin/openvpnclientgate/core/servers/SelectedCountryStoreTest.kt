package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SelectedCountryStoreTest {

    private fun server(
        name: String,
        city: String,
        country: Country = Country("CountryA"),
        config: String,
        lineIndex: Int = 1,
        signalStrength: SignalStrength = SignalStrength.STRONG,
        ip: String = "1.1.1.1"
    ) = Server(
        lineIndex = lineIndex,
        name = name,
        city = city,
        country = country,
        ping = 10,
        signalStrength = signalStrength,
        ip = ip,
        score = 100,
        speed = 1000,
        numVpnSessions = 1,
        uptime = 100,
        totalUsers = 10,
        totalTraffic = 1000,
        logType = "",
        operator = "",
        message = "",
        configData = config
    )

    @Test
    fun save_and_iterate_servers() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val servers = listOf(
            server(name = "srv-1", city = "City1", config = "config1", lineIndex = 1),
            server(name = "srv-2", city = "City2", config = "config2", lineIndex = 2, signalStrength = SignalStrength.MEDIUM)
        )

        SelectedCountryStore.saveSelection(ctx, "CountryA", servers)

        assertEquals("CountryA", SelectedCountryStore.getSelectedCountry(ctx))
        val stored = SelectedCountryStore.getServers(ctx)
        assertEquals(2, stored.size)
        assertEquals("City1", stored[0].city)
        assertEquals("config1", stored[0].config)

        val current = SelectedCountryStore.currentServer(ctx)
        assertNotNull(current)
        assertEquals("City1", current!!.city)

        val next = SelectedCountryStore.nextServer(ctx)
        assertNotNull(next)
        assertEquals("City2", next!!.city)

        val none = SelectedCountryStore.nextServer(ctx)
        assertNull(none)

        SelectedCountryStore.resetIndex(ctx)
        val again = SelectedCountryStore.currentServer(ctx)
        assertEquals("City1", again!!.city)
    }

    @Test
    fun last_successful_config_is_scoped_to_selected_country() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val serversA = listOf(server(name = "srv-1", city = "City1", country = Country("CountryA"), config = "configA1", lineIndex = 1))

        SelectedCountryStore.saveSelection(ctx, "CountryA", serversA)
        SelectedCountryStore.resetIndex(ctx)

        SelectedCountryStore.saveLastSuccessfulConfig(ctx, "CountryA", "configA1")
        val forA = SelectedCountryStore.getLastSuccessfulConfigForSelected(ctx)
        assertEquals("configA1", forA)

        val serversB = listOf(server(name = "srv-2", city = "City2", country = Country("CountryB"), config = "configB1", lineIndex = 1, signalStrength = SignalStrength.MEDIUM))

        SelectedCountryStore.saveSelection(ctx, "CountryB", serversB)
        SelectedCountryStore.resetIndex(ctx)

        val forB = SelectedCountryStore.getLastSuccessfulConfigForSelected(ctx)
        assertNull(forB)
    }

    @Test
    fun last_started_config_persists_and_is_read_back() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        SelectedCountryStore.saveLastStartedConfig(ctx, "CountryA", "conf-start-A")
        val last = SelectedCountryStore.getLastStartedConfig(ctx)
        assertNotNull(last)
        assertEquals("CountryA", last!!.country)
        assertEquals("conf-start-A", last.config)
        assertNull(last.ip)

        // Blank configs should be ignored
        SelectedCountryStore.saveLastStartedConfig(ctx, "CountryA", "")
        val stillLast = SelectedCountryStore.getLastStartedConfig(ctx)
        assertNotNull(stillLast)
        assertEquals("CountryA", stillLast!!.country)
        assertEquals("conf-start-A", stillLast.config)
    }

    @Test
    fun next_server_circular_stops_after_full_cycle() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val servers = listOf(
            server(name = "srv-1", city = "City1", config = "config1", lineIndex = 1),
            server(name = "srv-2", city = "City2", config = "config2", lineIndex = 2),
            server(name = "srv-3", city = "City3", config = "config3", lineIndex = 3)
        )

        SelectedCountryStore.saveSelection(ctx, "CountryA", servers)
        SelectedCountryStore.setCurrentIndex(ctx, 1)

        val first = SelectedCountryStore.nextServerCircular(ctx, 1)
        assertEquals("City3", first?.city)
        val second = SelectedCountryStore.nextServerCircular(ctx, 1)
        assertEquals("City1", second?.city)
        val third = SelectedCountryStore.nextServerCircular(ctx, 1)
        assertNull(third)
    }

    @Test
    fun ensure_index_for_config_recovers_from_invalid_index() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val servers = listOf(
            server(name = "srv-1", city = "City1", config = "config1", lineIndex = 1),
            server(name = "srv-2", city = "City2", config = "config2", lineIndex = 2, signalStrength = SignalStrength.MEDIUM)
        )

        SelectedCountryStore.saveSelection(ctx, "CountryA", servers)
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("selected_country_index", -1)
            .commit()

        SelectedCountryStore.ensureIndexForConfig(ctx, "config2")

        val current = SelectedCountryStore.currentServer(ctx)
        assertNotNull(current)
        assertEquals("City2", current!!.city)
    }

    @Test
    fun ensure_index_for_config_prefers_matching_ip_when_config_duplicates() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val servers = listOf(
            server(name = "srv-1", city = "City1", config = "dup-config", lineIndex = 1, ip = "10.0.0.1"),
            server(name = "srv-2", city = "City2", config = "dup-config", lineIndex = 2, ip = "10.0.0.2")
        )
        SelectedCountryStore.saveSelection(ctx, "CountryA", servers)
        SelectedCountryStore.resetIndex(ctx)

        SelectedCountryStore.ensureIndexForConfig(ctx, "dup-config", "10.0.0.2")

        val current = SelectedCountryStore.currentServer(ctx)
        assertNotNull(current)
        assertEquals("City2", current!!.city)
        assertEquals("10.0.0.2", current.ip)
    }

    @Test
    fun saveSelection_persists_country_code_in_stored_servers() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val servers = listOf(server(name = "srv-1", city = "City1", country = Country("CountryA", "AA"), config = "config1", lineIndex = 1))

        SelectedCountryStore.saveSelection(ctx, "CountryA", servers)

        val stored = SelectedCountryStore.getServers(ctx)
        assertEquals(1, stored.size)
        assertEquals("City1", stored[0].city)
        assertEquals("config1", stored[0].config)
        assertEquals("AA", stored[0].countryCode)
    }

    @Test
    fun saveSelectionPreservingIndex_preserves_current_server_and_bumps_version() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val initial = listOf(
            server(name = "srv-1", city = "City1", country = Country("CountryA", "AA"), config = "config1", lineIndex = 1, ip = "10.0.0.1"),
            server(name = "srv-2", city = "City2", country = Country("CountryA", "AA"), config = "config2", lineIndex = 2, ip = "10.0.0.2")
        )
        SelectedCountryStore.saveSelection(ctx, "CountryA", initial)
        SelectedCountryStore.setCurrentIndex(ctx, 1)

        val versionBefore = SelectedCountryVersionSignal.version.value

        val refreshed = listOf(
            server(name = "srv-x", city = "CityX", country = Country("CountryA", "AA"), config = "configX", lineIndex = 3, ip = "10.0.0.9"),
            server(name = "srv-2", city = "City2-new", country = Country("CountryA", "AA"), config = "config2", lineIndex = 4, ip = "10.0.0.2"),
            server(name = "srv-3", city = "City3", country = Country("CountryA", "AA"), config = "config3", lineIndex = 5, ip = "10.0.0.3")
        )

        SelectedCountryStore.saveSelectionPreservingIndex(ctx, "CountryA", refreshed)

        val current = SelectedCountryStore.currentServer(ctx)
        assertNotNull(current)
        assertEquals("config2", current!!.config)
        assertEquals("10.0.0.2", current.ip)
        assertEquals(3, SelectedCountryStore.getServers(ctx).size)
        assertEquals((2 to 3), SelectedCountryStore.getCurrentPosition(ctx))
        assertTrue(SelectedCountryVersionSignal.version.value > versionBefore)
    }

    @Test
    fun saveSelectionPreservingIndex_ignores_other_country() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val initial = listOf(
            server(name = "srv-1", city = "City1", country = Country("CountryA"), config = "config1", lineIndex = 1, ip = "10.0.0.1"),
            server(name = "srv-2", city = "City2", country = Country("CountryA"), config = "config2", lineIndex = 2, ip = "10.0.0.2")
        )
        SelectedCountryStore.saveSelection(ctx, "CountryA", initial)
        SelectedCountryStore.setCurrentIndex(ctx, 1)

        val versionBefore = SelectedCountryVersionSignal.version.value

        val otherCountryServers = listOf(
            server(name = "srv-3", city = "City3", country = Country("CountryB"), config = "config3", lineIndex = 3, ip = "10.0.1.3")
        )

        SelectedCountryStore.saveSelectionPreservingIndex(ctx, "CountryB", otherCountryServers)

        assertEquals("CountryA", SelectedCountryStore.getSelectedCountry(ctx))
        assertEquals(2, SelectedCountryStore.getServers(ctx).size)
        assertEquals((2 to 2), SelectedCountryStore.getCurrentPosition(ctx))
        assertEquals(versionBefore, SelectedCountryVersionSignal.version.value)
    }

    @Test
    fun getCurrentPosition_returns_null_when_servers_json_is_malformed() {
        val ctx = RuntimeEnvironment.getApplication()
        val prefs = ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        prefs.edit()
            .putString("selected_country", "CountryA")
            .putString("selected_country_servers", "{bad-json")
            .putInt("selected_country_index", 0)
            .commit()

        assertTrue(SelectedCountryStore.getServers(ctx).isEmpty())
        assertNull(SelectedCountryStore.getCurrentPosition(ctx))
    }

    @Test
    fun updateSelectedCountryName_changes_country_name_and_bumps_version() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val servers = listOf(
            server(name = "srv-1", city = "City1", country = Country("Russia", "RU"), config = "config1", lineIndex = 1)
        )
        SelectedCountryStore.saveSelection(ctx, "Russia", servers)

        val versionBefore = SelectedCountryVersionSignal.version.value

        // Simulate language change: update country name to Russian locale
        SelectedCountryStore.updateSelectedCountryName(ctx, "Россия")

        assertEquals("Россия", SelectedCountryStore.getSelectedCountry(ctx))
        val stored = SelectedCountryStore.getServers(ctx)
        assertEquals(1, stored.size)
        assertEquals("City1", stored[0].city)
        assertEquals("config1", stored[0].config)
        assertEquals("RU", stored[0].countryCode)
        assertTrue(SelectedCountryVersionSignal.version.value > versionBefore)
    }

    @Test
    fun updateSelectedCountryName_noop_when_no_selection() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        // No country is selected
        assertNull(SelectedCountryStore.getSelectedCountry(ctx))

        val versionBefore = SelectedCountryVersionSignal.version.value

        // Try to update when no selection exists
        SelectedCountryStore.updateSelectedCountryName(ctx, "Russia")

        // Should not create a selection or update metadata
        assertNull(SelectedCountryStore.getSelectedCountry(ctx))
        assertTrue(SelectedCountryStore.getServers(ctx).isEmpty())
        assertEquals(versionBefore, SelectedCountryVersionSignal.version.value)
    }

    @Test
    fun updateSelectedCountryName_noop_when_name_unchanged() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val servers = listOf(
            server(name = "srv-1", city = "City1", config = "config1", lineIndex = 1)
        )
        SelectedCountryStore.saveSelection(ctx, "Russia", servers)

        val versionBefore = SelectedCountryVersionSignal.version.value

        // Try to update to the same name
        SelectedCountryStore.updateSelectedCountryName(ctx, "Russia")

        assertEquals("Russia", SelectedCountryStore.getSelectedCountry(ctx))
        assertEquals(versionBefore, SelectedCountryVersionSignal.version.value)
    }

    @Test
    fun updateSelectedCountryName_preserves_server_index() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val servers = listOf(
            server(name = "srv-1", city = "City1", config = "config1", lineIndex = 1),
            server(name = "srv-2", city = "City2", config = "config2", lineIndex = 2),
            server(name = "srv-3", city = "City3", config = "config3", lineIndex = 3)
        )
        SelectedCountryStore.saveSelection(ctx, "Russia", servers)
        SelectedCountryStore.setCurrentIndex(ctx, 2)

        SelectedCountryStore.updateSelectedCountryName(ctx, "Россия")

        assertEquals("Россия", SelectedCountryStore.getSelectedCountry(ctx))
        assertEquals(3, SelectedCountryStore.getServers(ctx).size)
        val current = SelectedCountryStore.currentServer(ctx)
        assertNotNull(current)
        assertEquals("City3", current!!.city)
    }

    @Test
    fun updateSelectedCountryName_updates_last_country_metadata_when_matching_selection() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val servers = listOf(
            server(name = "srv-1", city = "City1", country = Country("Russia", "RU"), config = "config1", lineIndex = 1, ip = "1.1.1.1")
        )
        SelectedCountryStore.saveSelection(ctx, "Russia", servers)
        SelectedCountryStore.saveLastStartedConfig(ctx, "Russia", "config1", "1.1.1.1")
        SelectedCountryStore.saveLastSuccessfulConfig(ctx, "Russia", "config1", "1.1.1.1")

        SelectedCountryStore.updateSelectedCountryName(ctx, "Россия")

        assertEquals("Россия", SelectedCountryStore.getSelectedCountry(ctx))

        val lastStarted = SelectedCountryStore.getLastStartedConfig(ctx)
        assertNotNull(lastStarted)
        assertEquals("Россия", lastStarted!!.country)
        assertEquals("config1", lastStarted.config)

        assertEquals("config1", SelectedCountryStore.getLastSuccessfulConfigForSelected(ctx))
        assertEquals("1.1.1.1", SelectedCountryStore.getLastSuccessfulIpForSelected(ctx))
    }

    @Test
    fun updateSelectedCountryNameIfCurrent_skips_when_selection_changed() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val russiaServers = listOf(
            server(name = "srv-1", city = "City1", country = Country("Russia", "RU"), config = "config-ru", lineIndex = 1)
        )
        val germanyServers = listOf(
            server(name = "srv-2", city = "City2", country = Country("Germany", "DE"), config = "config-de", lineIndex = 2)
        )

        SelectedCountryStore.saveSelection(ctx, "Russia", russiaServers)
        val versionBefore = SelectedCountryVersionSignal.version.value

        SelectedCountryStore.saveSelection(ctx, "Germany", germanyServers)

        val updated = SelectedCountryStore.updateSelectedCountryNameIfCurrent(
            ctx = ctx,
            expectedCurrentCountryName = "Russia",
            newCountryName = "Россия"
        )

        assertFalse(updated)
        assertEquals("Germany", SelectedCountryStore.getSelectedCountry(ctx))
        assertEquals("config-de", SelectedCountryStore.currentServer(ctx)?.config)
        assertEquals(versionBefore, SelectedCountryVersionSignal.version.value)
    }

    @Test
    fun getCurrentServerIdIfMatchingLastStarted_matches_by_ip_not_config() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val servers = listOf(
            server(name = "srv-1", city = "City1", config = "config-v1", lineIndex = 1, ip = "10.0.0.1").copy(id = 42)
        )
        SelectedCountryStore.saveSelection(ctx, "CountryA", servers)
        SelectedCountryStore.resetIndex(ctx)

        // Save last-started with same IP but different config (simulating config formatting drift)
        SelectedCountryStore.saveLastStartedConfig(ctx, "CountryA", "config-v2", "10.0.0.1")

        val id = SelectedCountryStore.getCurrentServerIdIfMatchingLastStarted(ctx)
        assertEquals(42, id)
    }

    @Test
    fun getCurrentServerIdIfMatchingLastStarted_returns_zero_when_ip_differs() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val servers = listOf(
            server(name = "srv-1", city = "City1", config = "config1", lineIndex = 1, ip = "10.0.0.1")
        )
        SelectedCountryStore.saveSelection(ctx, "CountryA", servers)
        SelectedCountryStore.resetIndex(ctx)

        SelectedCountryStore.saveLastStartedConfig(ctx, "CountryA", "config1", "10.0.0.99")

        val id = SelectedCountryStore.getCurrentServerIdIfMatchingLastStarted(ctx)
        assertEquals(0, id)
    }

    @Test
    fun getCurrentServerIdIfMatchingLastStarted_returns_zero_when_ip_is_null() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val servers = listOf(
            server(name = "srv-1", city = "City1", config = "config1", lineIndex = 1, ip = "10.0.0.1")
        )
        SelectedCountryStore.saveSelection(ctx, "CountryA", servers)
        SelectedCountryStore.resetIndex(ctx)

        // Save last-started with null IP (legacy data)
        SelectedCountryStore.saveLastStartedConfig(ctx, "CountryA", "config1")

        val id = SelectedCountryStore.getCurrentServerIdIfMatchingLastStarted(ctx)
        assertEquals(0, id)
    }

    @Test
    fun updateSelectedCountryNameIfCurrent_atomicity_prevents_toctou_race() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val russiaServers = listOf(
            server(name = "srv-1", city = "City1", country = Country("Russia", "RU"), config = "config-ru", lineIndex = 1, ip = "1.1.1.1")
        )
        val germanyServers = listOf(
            server(name = "srv-2", city = "City2", country = Country("Germany", "DE"), config = "config-de", lineIndex = 2, ip = "2.2.2.2")
        )

        SelectedCountryStore.saveSelection(ctx, "Russia", russiaServers)
        SelectedCountryStore.saveLastSuccessfulConfig(ctx, "Russia", "config-ru", "1.1.1.1")
        SelectedCountryStore.saveLastStartedConfig(ctx, "Russia", "config-ru", "1.1.1.1")

        // Simulate TOCTOU race: call updateSelectedCountryNameIfCurrent with expected="Russia"
        // but BEFORE the write happens, simulate user selecting Germany
        val versionBefore = SelectedCountryVersionSignal.version.value

        // Atomically attempt rename of Russia to Россия
        val updated = SelectedCountryStore.updateSelectedCountryNameIfCurrent(
            ctx = ctx,
            expectedCurrentCountryName = "Russia",
            newCountryName = "Россия"
        )

        // Should succeed because current is still Russia
        assertTrue(updated)
        assertEquals("Россия", SelectedCountryStore.getSelectedCountry(ctx))

        // Now user changes selection to Germany
        SelectedCountryStore.saveSelection(ctx, "Germany", germanyServers)
        assertEquals("Germany", SelectedCountryStore.getSelectedCountry(ctx))

        // Attempt to update from Россия to something else while current is Germany
        val versionBeforeFailedSecondRename = SelectedCountryVersionSignal.version.value
        val updated2 = SelectedCountryStore.updateSelectedCountryNameIfCurrent(
            ctx = ctx,
            expectedCurrentCountryName = "Россия",
            newCountryName = "Russia"
        )

        // Should fail because current is now Germany, not Россия
        assertFalse(updated2)
        assertEquals("Germany", SelectedCountryStore.getSelectedCountry(ctx))
        assertEquals(versionBeforeFailedSecondRename, SelectedCountryVersionSignal.version.value)

        // Verify that atomic check prevented metadata corruption:
        // Germany's servers and config should not have been renamed
        val current = SelectedCountryStore.currentServer(ctx)
        assertNotNull(current)
        assertEquals("config-de", current!!.config)
    }

    // saveSelection's expectedCountry guard was a check-then-write.
    // A stale background backfill could pass the guard, the user could then select a different
    // country, and the backfill's write would land afterwards and resurrect the old country's
    // server pool with index reset to 0 -- silently discarding the newer selection.
    //
    // The write is instrumented through a SharedPreferences wrapper that parks the backfill
    // thread exactly at its edit() call, i.e. between the guard check and the write. A
    // concurrent foreground selection must NOT be able to complete while the backfill sits
    // there: with the check and the write in one critical section the racer blocks on the
    // monitor and only lands afterwards, so the newest selection is the one that survives.
    @Test(timeout = 30_000)
    fun saveSelection_guardedWrite_blocksConcurrentSelection_andNewestSelectionWins() {
        val base = RuntimeEnvironment.getApplication()
        base.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val russiaServers = listOf(
            server(name = "srv-1", city = "City1", country = Country("Russia", "RU"), config = "config-ru", lineIndex = 1, ip = "1.1.1.1")
        )
        val germanyServers = listOf(
            server(name = "srv-2", city = "City2", country = Country("Germany", "DE"), config = "config-de", lineIndex = 2, ip = "2.2.2.2")
        )

        SelectedCountryStore.saveSelection(base, "Russia", russiaServers)

        val backfillReachedWrite = CountDownLatch(1)
        val releaseBackfillWrite = CountDownLatch(1)
        val armed = AtomicBoolean(true)

        val instrumentedCtx = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val delegate = base.getSharedPreferences(name, mode)
                return object : SharedPreferences by delegate {
                    override fun edit(): SharedPreferences.Editor {
                        if (Thread.currentThread().name == BACKFILL_THREAD && armed.compareAndSet(true, false)) {
                            backfillReachedWrite.countDown()
                            releaseBackfillWrite.await(20, TimeUnit.SECONDS)
                        }
                        return delegate.edit()
                    }
                }
            }
        }

        val backfill = Thread({
            SelectedCountryStore.saveSelection(
                instrumentedCtx,
                "Russia",
                russiaServers,
                expectedCountry = "Russia"
            )
        }, BACKFILL_THREAD)
        backfill.start()
        assertTrue(
            "backfill thread never reached its guarded write",
            backfillReachedWrite.await(20, TimeUnit.SECONDS)
        )

        // The user selects Germany while the stale backfill is parked mid-write.
        val selectionCompleted = CountDownLatch(1)
        val userSelection = Thread {
            SelectedCountryStore.saveSelection(base, "Germany", germanyServers)
            selectionCompleted.countDown()
        }
        userSelection.start()
        val slippedIntoCriticalSection = selectionCompleted.await(1, TimeUnit.SECONDS)

        releaseBackfillWrite.countDown()
        backfill.join(20_000)
        userSelection.join(20_000)

        assertFalse(
            "a concurrent selection must not complete while a guarded write holds the lock",
            slippedIntoCriticalSection
        )
        assertEquals("Germany", SelectedCountryStore.getSelectedCountry(base))
        assertEquals(
            "the stale backfill must not resurrect the previous country's server pool",
            listOf("config-de"),
            SelectedCountryStore.getServers(base).map { it.config }
        )
    }

    // saveSelectionPreservingIndex made its own check/write/restore sequence
    // atomic, but the auto-switch index mutations (nextServerCircular -> setIndex) did not take
    // the same monitor. saveSelection writes index=0 before ensureIndexForConfig restores the
    // previous position, so ServerAutoSwitcher could advance off that transient 0, dispatch the
    // server it computed, and then have its advance overwritten by ensureIndexForConfig --
    // leaving the persisted current server different from the one actually connected to.
    //
    // The backfill thread is parked at its SECOND prefs write, i.e. exactly in the window where
    // index=0 has been committed and the restore has not run yet. A concurrent nextServerCircular
    // must not be able to observe or mutate the index there.
    @Test(timeout = 30_000)
    fun autoSwitchAdvance_cannotInterleaveWithBackfillIndexRestore() {
        val base = RuntimeEnvironment.getApplication()
        base.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val servers = listOf(
            server(name = "s1", city = "C1", config = "config-1", lineIndex = 1, ip = "1.1.1.1"),
            server(name = "s2", city = "C2", config = "config-2", lineIndex = 2, ip = "2.2.2.2"),
            server(name = "s3", city = "C3", config = "config-3", lineIndex = 3, ip = "3.3.3.3")
        )
        SelectedCountryStore.saveSelection(base, "CountryA", servers)
        // The user is on the last server; the auto-switch cycle started from there.
        SelectedCountryStore.setCurrentIndex(base, 2)
        val cycleStartIndex = SelectedCountryStore.getCurrentIndex(base)
        assertEquals(2, cycleStartIndex)

        val backfillReachedRestoreWindow = CountDownLatch(1)
        val releaseBackfill = CountDownLatch(1)
        val editCallsOnBackfillThread = java.util.concurrent.atomic.AtomicInteger(0)

        val instrumentedCtx = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val delegate = base.getSharedPreferences(name, mode)
                return object : SharedPreferences by delegate {
                    override fun edit(): SharedPreferences.Editor {
                        // 1st write = saveSelection (commits index=0); park before the 2nd,
                        // which is ensureIndexForConfig restoring the previous position.
                        if (Thread.currentThread().name == BACKFILL_THREAD &&
                            editCallsOnBackfillThread.incrementAndGet() == 2
                        ) {
                            backfillReachedRestoreWindow.countDown()
                            releaseBackfill.await(20, TimeUnit.SECONDS)
                        }
                        return delegate.edit()
                    }
                }
            }
        }

        val backfill = Thread({
            SelectedCountryStore.saveSelectionPreservingIndex(instrumentedCtx, "CountryA", servers)
        }, BACKFILL_THREAD)
        backfill.start()
        assertTrue(
            "backfill thread never reached its index-restore window",
            backfillReachedRestoreWindow.await(20, TimeUnit.SECONDS)
        )

        // ServerAutoSwitcher advances while the backfill sits in that window.
        val advanceCompleted = CountDownLatch(1)
        val dispatched = java.util.concurrent.atomic.AtomicReference<StoredServer?>(null)
        val autoSwitch = Thread {
            dispatched.set(SelectedCountryStore.nextServerCircular(base, cycleStartIndex))
            advanceCompleted.countDown()
        }
        autoSwitch.start()
        val slippedIntoCriticalSection = advanceCompleted.await(1, TimeUnit.SECONDS)

        releaseBackfill.countDown()
        backfill.join(20_000)
        autoSwitch.join(20_000)

        assertFalse(
            "an auto-switch advance must not interleave with the backfill's index restore",
            slippedIntoCriticalSection
        )
        val persisted = SelectedCountryStore.currentServer(base)
        val dispatchedServer = dispatched.get()
        assertNotNull("the auto-switch must still find a next server after the backfill", dispatchedServer)
        assertNotNull(persisted)
        assertEquals(
            "the persisted current server must be the one the auto-switch actually dispatched",
            dispatchedServer!!.config,
            persisted!!.config
        )
    }

    // Making only the server-list write guarded is not enough for callers
    // that follow it with a dependent index write (startup hydration). This is the reproduction of
    // that split sequence: the hydration's guarded write lands, a newer selection commits in the
    // gap, and the index -- resolved against the list that just got superseded -- is then applied
    // to the newer country's pool, so the persisted "current server" is an entry the index was
    // never measured against.
    @Test(timeout = 30_000)
    fun splitGuardedWriteThenIndexWrite_appliesStaleIndexToTheNewerCountrysPool() {
        val base = RuntimeEnvironment.getApplication()
        base.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val franceServers = listOf(
            server(name = "fr-1", city = "Paris", country = Country("France", "FR"), config = "cfg-fr-1", lineIndex = 1, ip = "1.1.1.1"),
            server(name = "fr-2", city = "Lyon", country = Country("France", "FR"), config = "cfg-fr-2", lineIndex = 2, ip = "1.1.1.2")
        )
        val germanyServers = listOf(
            server(name = "de-1", city = "Berlin", country = Country("Germany", "DE"), config = "cfg-de-1", lineIndex = 1, ip = "2.2.2.1"),
            server(name = "de-2", city = "Hamburg", country = Country("Germany", "DE"), config = "cfg-de-2", lineIndex = 2, ip = "2.2.2.2"),
            server(name = "de-3", city = "Munich", country = Country("Germany", "DE"), config = "cfg-de-3", lineIndex = 3, ip = "2.2.2.3")
        )

        SelectedCountryStore.saveSelection(base, "France", franceServers)

        val hydrationLeftGuardedWrite = CountDownLatch(1)
        val releaseHydrationIndexWrite = CountDownLatch(1)

        // Exactly what the hydration used to do: guarded list write, then a SEPARATE index write.
        val hydration = Thread({
            val written = SelectedCountryStore.saveSelection(
                base,
                "France",
                franceServers,
                expectedCountry = "France"
            )
            assertTrue(written)
            hydrationLeftGuardedWrite.countDown()
            releaseHydrationIndexWrite.await(20, TimeUnit.SECONDS)
            // index 1 == "cfg-fr-2", i.e. resolved against France's list.
            SelectedCountryStore.setCurrentIndex(base, 1)
        }, BACKFILL_THREAD)
        hydration.start()
        assertTrue(hydrationLeftGuardedWrite.await(20, TimeUnit.SECONDS))

        // The user picks Germany in the gap between the two writes.
        SelectedCountryStore.saveSelection(base, "Germany", germanyServers)
        releaseHydrationIndexWrite.countDown()
        hydration.join(20_000)

        assertEquals("Germany", SelectedCountryStore.getSelectedCountry(base))
        // The damage: France's index landed on Germany's pool.
        assertEquals("cfg-de-2", SelectedCountryStore.currentServer(base)?.config)
    }

    // ...and the fix: the same interleaving attempted against the atomic method cannot corrupt the
    // pair, because the newer selection can only run before the whole list+index write or after
    // it, never between. The racer is confirmed BLOCKED on the monitor before the hydration is
    // released, so it really is contending for it.
    @Test(timeout = 30_000)
    fun saveSelectionAndSetIndexIfCurrent_writesListAndIndexAsOneCriticalSection() {
        val base = RuntimeEnvironment.getApplication()
        base.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val franceServers = listOf(
            server(name = "fr-1", city = "Paris", country = Country("France", "FR"), config = "cfg-fr-1", lineIndex = 1, ip = "1.1.1.1"),
            server(name = "fr-2", city = "Lyon", country = Country("France", "FR"), config = "cfg-fr-2", lineIndex = 2, ip = "1.1.1.2")
        )
        val germanyServers = listOf(
            server(name = "de-1", city = "Berlin", country = Country("Germany", "DE"), config = "cfg-de-1", lineIndex = 1, ip = "2.2.2.1"),
            server(name = "de-2", city = "Hamburg", country = Country("Germany", "DE"), config = "cfg-de-2", lineIndex = 2, ip = "2.2.2.2"),
            server(name = "de-3", city = "Munich", country = Country("Germany", "DE"), config = "cfg-de-3", lineIndex = 3, ip = "2.2.2.3")
        )

        SelectedCountryStore.saveSelection(base, "France", franceServers)

        val hydrationInsideCriticalSection = CountDownLatch(1)
        val releaseHydration = CountDownLatch(1)
        val armed = AtomicBoolean(true)

        val instrumentedCtx = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val delegate = base.getSharedPreferences(name, mode)
                return object : SharedPreferences by delegate {
                    override fun edit(): SharedPreferences.Editor {
                        if (Thread.currentThread().name == BACKFILL_THREAD && armed.compareAndSet(true, false)) {
                            hydrationInsideCriticalSection.countDown()
                            releaseHydration.await(20, TimeUnit.SECONDS)
                        }
                        return delegate.edit()
                    }
                }
            }
        }

        val hydrationWrote = AtomicBoolean(false)
        val hydration = Thread({
            hydrationWrote.set(
                SelectedCountryStore.saveSelectionAndSetIndexIfCurrent(
                    instrumentedCtx,
                    "France",
                    franceServers,
                    selectedIndex = 1,
                    expectedCountry = "France",
                    expectedConfig = "cfg-fr-1",
                    expectedIp = "1.1.1.1"
                )
            )
        }, BACKFILL_THREAD)
        hydration.start()
        assertTrue(hydrationInsideCriticalSection.await(20, TimeUnit.SECONDS))

        val selectionCompleted = CountDownLatch(1)
        val userSelection = Thread {
            SelectedCountryStore.saveSelection(base, "Germany", germanyServers)
            selectionCompleted.countDown()
        }
        userSelection.start()
        assertTrue(
            "the newer selection never queued on the selection monitor",
            awaitBlocked(userSelection)
        )
        assertFalse(
            "a newer selection must not complete while the atomic write holds the lock",
            selectionCompleted.count == 0L
        )

        releaseHydration.countDown()
        hydration.join(20_000)
        userSelection.join(20_000)

        assertTrue("the guarded hydration write should have been applied", hydrationWrote.get())
        assertEquals("Germany", SelectedCountryStore.getSelectedCountry(base))
        assertEquals(
            listOf("cfg-de-1", "cfg-de-2", "cfg-de-3"),
            SelectedCountryStore.getServers(base).map { it.config }
        )
        // Germany's own write set index 0; France's index 1 never reached this pool.
        assertEquals("cfg-de-1", SelectedCountryStore.currentServer(base)?.config)
    }

    // Regression: the write-time guard used to compare only the country name. A deferred writer
    // that captured server A and then found the user on server B of the SAME country still passed
    // the guard, so its pool and its index (measured against A) landed and re-pointed the persisted
    // current server back at A -- reverting the user's choice at the write itself, before any
    // post-write freshness check could observe anything wrong.
    @Test
    fun saveSelectionAndSetIndexIfCurrent_rejectsWhenSameCountryServerChanged() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val franceServers = listOf(
            server(name = "fr-1", city = "Paris", country = Country("France", "FR"), config = "cfg-fr-1", lineIndex = 1, ip = "1.1.1.1"),
            server(name = "fr-2", city = "Lyon", country = Country("France", "FR"), config = "cfg-fr-2", lineIndex = 2, ip = "1.1.1.2")
        )
        SelectedCountryStore.saveSelection(ctx, "France", franceServers)
        // The deferred writer captured server 2...
        SelectedCountryStore.setCurrentIndex(ctx, 1)
        val captured = SelectedCountryStore.currentServer(ctx)
        assertEquals("cfg-fr-2", captured?.config)

        // ...and while it was in flight the user picked server 1 of the same country.
        SelectedCountryStore.setCurrentIndex(ctx, 0)

        val written = SelectedCountryStore.saveSelectionAndSetIndexIfCurrent(
            ctx,
            "France",
            franceServers,
            selectedIndex = 1,
            expectedCountry = "France",
            expectedConfig = captured?.config,
            expectedIp = captured?.ip
        )

        assertFalse("the superseded same-country write must be rejected", written)
        // The user's newer choice stands, untouched.
        assertEquals("cfg-fr-1", SelectedCountryStore.currentServer(ctx)?.config)
    }

    // ...and the guard must still let a genuinely current write through.
    @Test
    fun saveSelectionAndSetIndexIfCurrent_appliesWhenSelectionUnchanged() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val franceServers = listOf(
            server(name = "fr-1", city = "Paris", country = Country("France", "FR"), config = "cfg-fr-1", lineIndex = 1, ip = "1.1.1.1"),
            server(name = "fr-2", city = "Lyon", country = Country("France", "FR"), config = "cfg-fr-2", lineIndex = 2, ip = "1.1.1.2")
        )
        SelectedCountryStore.saveSelection(ctx, "France", franceServers)
        SelectedCountryStore.setCurrentIndex(ctx, 1)

        val written = SelectedCountryStore.saveSelectionAndSetIndexIfCurrent(
            ctx,
            "France",
            franceServers,
            selectedIndex = 1,
            expectedCountry = "France",
            expectedConfig = "cfg-fr-2",
            expectedIp = "1.1.1.2"
        )

        assertTrue("an unsuperseded write must be applied", written)
        assertEquals("cfg-fr-2", SelectedCountryStore.currentServer(ctx)?.config)
    }

    /** Waits until [thread] parks on a monitor, so the race window is entered deterministically. */
    private fun awaitBlocked(thread: Thread, timeoutMs: Long = 20_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (thread.state) {
                Thread.State.BLOCKED -> return true
                Thread.State.TERMINATED -> return false
                else -> Thread.sleep(5)
            }
        }
        return false
    }

    private companion object {
        private const val BACKFILL_THREAD = "selected-country-store-test-backfill"
    }
}

