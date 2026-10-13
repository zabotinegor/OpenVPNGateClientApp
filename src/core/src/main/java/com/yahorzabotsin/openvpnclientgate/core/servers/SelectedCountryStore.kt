package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import android.content.SharedPreferences
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.logging.LogTags
import com.yahorzabotsin.openvpnclientgate.core.settings.SelectionWriteLock
import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject

data class StoredServer(
    val city: String,
    val config: String,
    val countryCode: String? = null,
    val ip: String? = null,
    val utc: String? = null,
    val id: Int = 0
)

data class LastConfig(
    val country: String?,
    val config: String?,
    val ip: String?
)

object SelectedCountryStore {
    private const val PREFS_NAME = "vpn_selection_prefs"
    private const val KEY_COUNTRY = "selected_country"
    private const val KEY_SERVERS = "selected_country_servers"
    private const val KEY_INDEX = "selected_country_index"
    private const val KEY_LAST_SUCCESS_COUNTRY = "last_success_country"
    private const val KEY_LAST_SUCCESS_CONFIG = "last_success_config"
    private const val KEY_LAST_STARTED_COUNTRY = "last_started_country"
    // Shared with UserSettingsStore's server-source writes: a source transition and a guarded
    // selection commit must never interleave, or a pool written for the old source can land after
    // the source has already changed. See [SelectionWriteLock].
    private val selectionRenameLock = SelectionWriteLock.monitor
    private const val KEY_LAST_STARTED_CONFIG = "last_started_config"
    private const val KEY_LAST_SUCCESS_IP = "last_success_ip"
    private const val KEY_LAST_SUCCESS_SERVER_KEY = "last_success_server_key"
    private const val KEY_LAST_STARTED_IP = "last_started_ip"
    private val TAG = LogTags.APP + ":SelectedCountryStore"
    private const val KEY_JSON_CITY = "city"
    private const val KEY_JSON_CONFIG = "config"
    private const val KEY_JSON_CODE = "code"
    private const val KEY_JSON_IP = "ip"
    private const val KEY_JSON_UTC = "utc"
    private const val KEY_JSON_SERVER_ID = "id"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * @param expectedCountry when non-null, the write is skipped unless this is still the stored
     * country at the moment the monitor is held. Background/deferred writers (sync, silent
     * backfill, startup hydration) MUST pass it -- see the critical-section note below.
     * @return `true` when the selection was written, `false` when the [expectedCountry] guard
     * rejected it. Callers that follow up with a dependent write (e.g. [setCurrentIndex]) must
     * check this: applying an index that belongs to the skipped payload would corrupt whichever
     * newer selection won the race.
     */
    fun saveSelection(
        ctx: Context,
        country: String,
        servers: List<Server>,
        expectedCountry: String? = null
    ): Boolean {
        val arr = JSONArray()
        servers.forEach { s ->
            val o = JSONObject()
                .put(KEY_JSON_CITY, s.city)
                .put(KEY_JSON_CONFIG, s.configData)
                .put(KEY_JSON_CODE, s.country.code)
                .put(KEY_JSON_IP, s.ip)
                .put(KEY_JSON_UTC, s.utc)
                .put(KEY_JSON_SERVER_ID, s.id)
            arr.put(o)
        }
        // Serialize BEFORE taking the lock: JSON building is the expensive part and it needs no
        // mutual exclusion. The serialized string is reused in the write so the guard check and
        // the write see the same payload.
        val serialized = arr.toString()
        // The expected-country guard and the write must be ONE critical section. A plain
        // check-then-write lets another selection commit in between, after which this (now
        // stale) write resurrects the old country's server pool and resets the index — the
        // user's newer choice is silently lost. selectionRenameLock is the same monitor every
        // other guarded selection write takes, so all of them are serialized against each other.
        synchronized(selectionRenameLock) {
            if (expectedCountry != null && getSelectedCountry(ctx) != expectedCountry) {
                AppLog.w(
                    TAG,
                    "saveSelection: superseded before the guarded write, skipping (country=$country, expected=$expectedCountry)"
                )
                return false
            }
            val editor = prefs(ctx).edit()
                .putString(KEY_COUNTRY, country)
                .putString(KEY_SERVERS, serialized)
                .putInt(KEY_INDEX, 0)
            if (expectedCountry != null) {
                // Guarded writes come from background jobs (the silent backfill / sync), where a
                // synchronous commit is affordable and makes the write durable before the lock
                // is released. Unguarded foreground selections keep apply() to stay off the
                // caller's critical path — they are the newest intent by definition, so there is
                // nothing for them to lose a race against.
                editor.commit()
            } else {
                editor.apply()
            }
            return true
        }
    }

    /**
     * Guarded selection write **plus** its dependent index write, as ONE critical section.
     *
     * [saveSelection] on its own only makes the guard and the list write atomic. A caller that
     * follows it with a separate [setCurrentIndex] still has a gap between the two: a newer
     * selection can commit in that gap, and the index — computed against the *old* server list —
     * then lands on the *new* country's pool, leaving the persisted "current server" pointing at
     * an arbitrary entry of a country the index was never measured against. Doing both under the
     * monitor closes that gap: the newer selection either lands entirely before this pair or
     * blocks until the pair has been applied, in which case it overwrites both consistently.
     *
     * The guard covers the **country and the selected server together** ([isCurrentSelection]), not
     * just the country name. A deferred writer derives both the pool it writes and [selectedIndex]
     * from the selection it captured at entry; picking a different server *inside the same country*
     * while that write is in flight leaves the country name equal, so a country-only guard would
     * let this write land and re-point the persisted current server at the captured (now
     * superseded) one. The post-write freshness check cannot recover from that either: it would be
     * comparing against the very server this write just persisted.
     *
     * @param selectedIndex index into [servers] (i.e. computed against the list being written,
     * not against whatever is currently persisted).
     * @param expectedCountry the selected country captured at entry.
     * @param expectedConfig / @param expectedIp identity of the current server captured at entry.
     * @return `true` when both writes were applied, `false` when the guard rejected the write — in
     * which case neither the list nor the index was touched, and the caller must discard whatever
     * it derived from [servers].
     */
    fun saveSelectionAndSetIndexIfCurrent(
        ctx: Context,
        country: String,
        servers: List<Server>,
        selectedIndex: Int,
        expectedCountry: String,
        expectedConfig: String?,
        expectedIp: String?
    ): Boolean {
        // The monitor is reentrant, so the nested isCurrentSelection/saveSelection/setCurrentIndex
        // take it again without deadlocking while this frame keeps it held across all three --
        // guard and both writes are therefore ONE critical section.
        synchronized(selectionRenameLock) {
            if (!isCurrentSelection(ctx, expectedCountry, expectedConfig, expectedIp)) {
                AppLog.w(
                    TAG,
                    "saveSelectionAndSetIndexIfCurrent: selection superseded before the guarded write, skipping (country=$country, expected=$expectedCountry)"
                )
                return false
            }
            if (!saveSelection(ctx, country, servers, expectedCountry)) return false
            setCurrentIndex(ctx, selectedIndex)
            return true
        }
    }

    /**
     * @param isStillCurrent evaluated **inside** the selection monitor, immediately before the
     * write. Callers whose freshness is tracked outside SharedPreferences (the silent backfill's
     * [CountrySyncGenerations] ticket) must pass their guard here rather than checking it before
     * the call: a bare pre-check is a TOCTOU window in which a newer selection can bump the
     * generation and commit its own pool, only for this now-stale write to land on top of it.
     * Since every selection write takes this same monitor, a guard evaluated under it either sees
     * the newer selection's bump (and stands down) or runs entirely before that selection's write
     * (which then wins by landing later). Defaults to "always current" for callers with no such
     * external freshness token.
     */
    fun saveSelectionPreservingIndex(
        ctx: Context,
        country: String,
        servers: List<Server>,
        isStillCurrent: () -> Boolean = { true }
    ) {
        // The whole check → write → restore-index sequence runs under the same monitor as
        // saveSelection (reentrant), so a concurrent selection can neither slip between the
        // country check and the write nor observe the intermediate index=0 state that
        // saveSelection writes before restoreIndexByIdentity restores the previous position.
        synchronized(selectionRenameLock) {
            if (!isStillCurrent()) {
                AppLog.w(
                    TAG,
                    "saveSelectionPreservingIndex: superseded before the guarded write, skipping (country=$country)"
                )
                return
            }
            val selectedCountry = getSelectedCountry(ctx)
            if (selectedCountry != country) return

            val previousList = getServers(ctx)
            val previousCount = previousList.size
            val previousIndex = getIndex(ctx)
            val previousCurrent = previousList.getOrNull(previousIndex)

            if (!saveSelection(ctx, country, servers, expectedCountry = country)) return

            // Re-point the persisted index by server IDENTITY, not by config/ip: the same IP may
            // appear on several entries and a refresh can add/remove/reorder entries, so a
            // position (or an indexOfFirst on ip) would silently land on a different server.
            val currentRestored = previousCurrent != null &&
                restoreIndexByIdentity(ctx, previousList, previousIndex)

            val newCount = getServers(ctx).size
            AppLog.i(
                TAG,
                "saveSelectionPreservingIndex: country=$country, count=$previousCount->$newCount, current_restored=$currentRestored"
            )
            SelectedCountryVersionSignal.bump()
        }
    }

    /**
     * Points the freshly written list's index at the server that was current in [previousList] at
     * [previousIndex], matching by [ServerIdentity]. When that server no longer exists, the index is
     * parked on the PREDECESSOR (in the old order) of the first surviving successor, so a circular
     * scan that starts after the index resumes exactly at that successor: no survivor is skipped and
     * none is revisited. Returns whether the very same server was found.
     */
    private fun restoreIndexByIdentity(ctx: Context, previousList: List<StoredServer>, previousIndex: Int): Boolean {
        val newKeys = ServerIdentity.keys(getServers(ctx))
        if (newKeys.isEmpty() || previousIndex !in previousList.indices) return false
        val previousKeys = ServerIdentity.keys(previousList)
        val sameServer = newKeys.indexOf(previousKeys[previousIndex])
        if (sameServer >= 0) {
            setIndex(ctx, sameServer)
            return true
        }
        for (step in 1 until previousList.size) {
            val survivor = newKeys.indexOf(previousKeys[(previousIndex + step) % previousList.size])
            if (survivor >= 0) {
                setIndex(ctx, (survivor - 1 + newKeys.size) % newKeys.size)
                return false
            }
        }
        return false
    }

    fun getSelectedCountry(ctx: Context): String? = prefs(ctx).getString(KEY_COUNTRY, null)

    fun getServers(ctx: Context): List<StoredServer> {
        val raw = prefs(ctx).getString(KEY_SERVERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                StoredServer(
                    city = o.optString(KEY_JSON_CITY),
                    config = o.optString(KEY_JSON_CONFIG),
                    countryCode = o.optString(KEY_JSON_CODE, null),
                    ip = o.optString(KEY_JSON_IP, null),
                    utc = o.optString(KEY_JSON_UTC, null),
                    id = o.optInt(KEY_JSON_SERVER_ID, 0)
                )
            }
        } catch (e: JSONException) {
            AppLog.e(TAG, "Error parsing servers JSON from SharedPreferences", e)
            emptyList()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Index accessors.
    //
    // The server list (KEY_SERVERS) and the current index (KEY_INDEX) are two SharedPreferences
    // entries describing ONE piece of state: "which server of this country are we on". Every
    // read-modify-write that spans both therefore runs under selectionRenameLock -- the same
    // monitor the guarded selection writes take.
    //
    // Without that, ServerAutoSwitcher's nextUnvisitedServer() could interleave with the silent
    // backfill's saveSelectionPreservingIndex(), which briefly resets the index to 0 (inside
    // saveSelection) before restoreIndexByIdentity restores the previous position. The auto-switch
    // would then advance off that transient 0 -- and dispatch the server it computed -- only for
    // the restore to overwrite the index right afterwards, leaving the persisted current
    // server different from the one actually connected to and corrupting every subsequent
    // cycle/wrap decision. The monitor is reentrant, so the nested calls below
    // (setCurrentIndex → setIndex, ensureIndexForConfig → setIndex,
    // saveSelectionPreservingIndex → restoreIndexByIdentity → setIndex) are safe.
    //
    // The read-only accessors take it too: they read the list and the index as a pair, and a
    // torn pair (new list, old index) is exactly the mismatch this lock exists to prevent.
    // ---------------------------------------------------------------------------------------

    fun resetIndex(ctx: Context) {
        synchronized(selectionRenameLock) { prefs(ctx).edit().putInt(KEY_INDEX, 0).apply() }
    }

    fun prepareAutoSwitchFromStart(ctx: Context) {
        synchronized(selectionRenameLock) { prefs(ctx).edit().putInt(KEY_INDEX, -1).apply() }
    }

    private fun getIndex(ctx: Context): Int = prefs(ctx).getInt(KEY_INDEX, 0)

    private fun setIndex(ctx: Context, index: Int) {
        synchronized(selectionRenameLock) { prefs(ctx).edit().putInt(KEY_INDEX, index).apply() }
    }

    fun setCurrentIndex(ctx: Context, index: Int) {
        synchronized(selectionRenameLock) {
            val list = getServers(ctx)
            if (index in list.indices) {
                setIndex(ctx, index)
                val current = list[index]
                AppLog.d(
                    TAG,
                    "setCurrentIndex: index=${index + 1}/${list.size} ip=${current.ip ?: "<none>"} city=${current.city.ifBlank { "<none>" }}"
                )
            }
        }
    }

    fun getCurrentPosition(ctx: Context): Pair<Int, Int>? = synchronized(selectionRenameLock) {
        val list = getServers(ctx)
        if (list.isEmpty()) return null
        val idx = getIndex(ctx)
        return if (idx in list.indices) (idx + 1) to list.size else null
    }

    fun getCurrentIndex(ctx: Context): Int? = synchronized(selectionRenameLock) {
        val list = getServers(ctx)
        val idx = getIndex(ctx)
        return if (idx in list.indices) idx else null
    }

    fun currentServer(ctx: Context): StoredServer? = synchronized(selectionRenameLock) {
        val list = getServers(ctx)
        val idx = getIndex(ctx)
        return if (idx in list.indices) list[idx] else null
    }

    /**
     * Atomic "is this still the live selection?" check over the **country and the selected server
     * together**.
     *
     * A country-only comparison is not enough for deferred writers: picking a different server
     * inside the same country leaves [getSelectedCountry] equal, so a stale country/server pairing
     * would pass such a check and be handed back to the caller (which reconnects to it). The
     * country name, the server list and the index are three preference entries describing one
     * piece of state, so they are read under the selection monitor -- the same one every selection
     * write takes -- otherwise the pair could be torn by a selection landing between the reads.
     *
     * @return `true` only when [country] is still the selected country **and** the persisted
     * current server is the one identified by [config]/[ip].
     */
    fun isCurrentSelection(ctx: Context, country: String, config: String?, ip: String?): Boolean =
        synchronized(selectionRenameLock) {
            if (getSelectedCountry(ctx) != country) return false
            val current = currentServer(ctx) ?: return false
            return current.config == config && current.ip == ip
        }

    fun nextServer(ctx: Context): StoredServer? = synchronized(selectionRenameLock) {
        val list = getServers(ctx)
        val idx = getIndex(ctx) + 1
        return if (idx in list.indices) {
            setIndex(ctx, idx)
            list[idx]
        } else null
    }

    /** Identity keys ([ServerIdentity]) of the persisted list, in list order. */
    fun currentServerKeys(ctx: Context): List<String> = synchronized(selectionRenameLock) {
        ServerIdentity.keys(getServers(ctx))
    }

    /** Identity key of the persisted current server, or null when the index is out of range. */
    fun currentServerKey(ctx: Context): String? = synchronized(selectionRenameLock) {
        val list = getServers(ctx)
        val idx = getIndex(ctx)
        if (idx in list.indices) ServerIdentity.keys(list)[idx] else null
    }

    /**
     * Next auto-switch candidate: scanning circularly from the persisted current index, the first
     * server whose identity key is not in [visited]. Persists the index of the returned candidate
     * (so the UI counter follows the connection target) and returns null when every server in the
     * list has already been attempted -- i.e. the cycle is exhausted.
     *
     * Tracking attempts by identity rather than by position means a refresh that adds, removes or
     * reorders servers mid-cycle can neither get an already-attempted server retried nor make the
     * wrap look complete too early.
     */
    fun nextUnvisitedServer(ctx: Context, visited: Set<String>): CycleCandidate? =
        synchronized(selectionRenameLock) {
            val list = getServers(ctx)
            if (list.isEmpty()) return null
            val keys = ServerIdentity.keys(list)
            val idx = getIndex(ctx)
            // An out-of-range index (e.g. -1 from prepareAutoSwitchFromStart) scans from position 0.
            val origin = if (idx in list.indices) idx else -1
            for (step in 1..list.size) {
                val i = Math.floorMod(origin + step, list.size)
                if (keys[i] !in visited) {
                    setIndex(ctx, i)
                    return CycleCandidate(list[i], keys[i], i)
                }
            }
            null
        }

    /** Points the persisted index at the server with identity [key]; false when it is gone. */
    fun setCurrentIndexByKey(ctx: Context, key: String): Boolean = synchronized(selectionRenameLock) {
        val i = ServerIdentity.keys(getServers(ctx)).indexOf(key)
        if (i < 0) false else { setIndex(ctx, i); true }
    }

    private fun resolveIpForConfig(ctx: Context, config: String?): String? {
        if (config.isNullOrBlank()) return null
        return getServers(ctx).firstOrNull { it.config == config }?.ip
    }

    fun getIpForConfig(ctx: Context, config: String?): String? = resolveIpForConfig(ctx, config)

    fun saveLastSuccessfulConfig(
        ctx: Context,
        country: String?,
        config: String,
        ip: String? = null,
        alignIndex: Boolean = true
    ) {
        if (config.isBlank()) return
        // Resolving the IP reads the server list and the index alignment then writes against
        // that same list, so both steps share one critical section.
        synchronized(selectionRenameLock) {
            val ipToStore = ip ?: resolveIpForConfig(ctx, config)
            prefs(ctx).edit()
                .putString(KEY_LAST_SUCCESS_CONFIG, config)
                .putString(KEY_LAST_SUCCESS_COUNTRY, country)
                .putString(KEY_LAST_SUCCESS_IP, ipToStore)
                .apply()
            if (alignIndex) {
                ensureIndexForConfig(ctx, config, ipToStore)
            }
            // The persisted config is the DECODED one from ACTION_START and can never be matched
            // against the stored (encoded) list, and several entries may share one IP. The identity
            // of the server the index points at lets the next start restore exactly this entry.
            prefs(ctx).edit().putString(KEY_LAST_SUCCESS_SERVER_KEY, currentServerKey(ctx)).apply()
        }
    }

    /**
     * Points the index at the server recorded by the last successful connection (by identity).
     * Returns false when nothing was recorded (older persisted data) or that server is gone, so the
     * caller can fall back to config/ip alignment.
     */
    fun restoreLastSuccessfulServerIndex(ctx: Context): Boolean = synchronized(selectionRenameLock) {
        val key = prefs(ctx).getString(KEY_LAST_SUCCESS_SERVER_KEY, null)
        !key.isNullOrBlank() && setCurrentIndexByKey(ctx, key)
    }

    fun getLastSuccessfulConfigForSelected(ctx: Context): String? {
        val prefs = prefs(ctx)
        val config = prefs.getString(KEY_LAST_SUCCESS_CONFIG, null)
        val selected = getSelectedCountry(ctx)
        val country = prefs.getString(KEY_LAST_SUCCESS_COUNTRY, null)
        AppLog.d(TAG, "getLastSuccessfulConfigForSelected: selected=${selected ?: "<none>"} storedCountry=${country ?: "<none>"} hasConfig=${config != null}")
        if (config.isNullOrBlank() || selected.isNullOrBlank()) return null
        return if (selected == country) config else null
    }

    fun getLastSuccessfulIpForSelected(ctx: Context): String? {
        val prefs = prefs(ctx)
        val selected = getSelectedCountry(ctx)
        val country = prefs.getString(KEY_LAST_SUCCESS_COUNTRY, null)
        val ip = prefs.getString(KEY_LAST_SUCCESS_IP, null)
        if (ip.isNullOrBlank() || selected.isNullOrBlank()) return null
        return if (selected == country) ip else null
    }

    fun saveLastStartedConfig(
        ctx: Context, country: String?, config: String, ip: String? = null, alignIndex: Boolean = true
    ) {
        if (config.isBlank()) return
        synchronized(selectionRenameLock) {
            val ipToStore = ip ?: resolveIpForConfig(ctx, config)
            prefs(ctx).edit()
                .putString(KEY_LAST_STARTED_CONFIG, config)
                .putString(KEY_LAST_STARTED_COUNTRY, country)
                .putString(KEY_LAST_STARTED_IP, ipToStore)
                .apply()
            if (alignIndex) {
                ensureIndexForConfig(ctx, config, ipToStore)
            }
        }
    }

    fun getLastStartedConfig(ctx: Context): LastConfig? {
        val prefs = prefs(ctx)
        val cfg = prefs.getString(KEY_LAST_STARTED_CONFIG, null)
        val country = prefs.getString(KEY_LAST_STARTED_COUNTRY, null)
        val ip = prefs.getString(KEY_LAST_STARTED_IP, null)
        AppLog.d(TAG, "getLastStartedConfig: country=${country ?: "<none>"} hasConfig=${cfg != null}")
        return if (cfg.isNullOrBlank()) null else LastConfig(country, cfg, ip)
    }

    fun ensureIndexForConfig(ctx: Context, config: String?, ip: String? = null) {
        if (config.isNullOrBlank() && ip.isNullOrBlank()) return
        // Reads the list, reads the index and writes the index: one read-modify-write over the
        // list/index pair, so it belongs in the same critical section as every other one.
        synchronized(selectionRenameLock) {
            val list = getServers(ctx)
            if (list.isEmpty()) return
            val current = getIndex(ctx)
            if (current in list.indices &&
                list[current].config == config &&
                (ip.isNullOrBlank() || list[current].ip == ip)
            ) return

            if (!config.isNullOrBlank() && !ip.isNullOrBlank()) {
                val foundByConfigAndIp = list.indexOfFirst { it.config == config && it.ip == ip }
                if (foundByConfigAndIp >= 0) {
                    setIndex(ctx, foundByConfigAndIp)
                    AppLog.d(
                        TAG,
                        "ensureIndexForConfig: matched by config+ip index=${foundByConfigAndIp + 1}/${list.size} ip=${ip ?: "<none>"}"
                    )
                    return
                }
            }

            val foundByConfig = config?.let { cfg -> list.indexOfFirst { it.config == cfg } } ?: -1
            if (foundByConfig >= 0) {
                setIndex(ctx, foundByConfig)
                val matchedIp = list[foundByConfig].ip
                AppLog.d(
                    TAG,
                    "ensureIndexForConfig: matched by config index=${foundByConfig + 1}/${list.size} ip=${matchedIp ?: "<none>"}"
                )
                return
            }
            if (!ip.isNullOrBlank()) {
                // ACTION_START carries the DECODED config while the store keeps the encoded one, so
                // a started server often cannot be matched by config. An IP alone cannot tell
                // same-IP entries apart: when the current entry already has this IP, keep the
                // explicit selection instead of collapsing onto the first same-IP entry.
                if (current in list.indices && list[current].ip == ip) return
                val foundByIp = list.indexOfFirst { it.ip == ip }
                if (foundByIp >= 0) {
                    setIndex(ctx, foundByIp)
                    AppLog.d(TAG, "ensureIndexForConfig: matched by ip index=${foundByIp + 1}/${list.size} ip=$ip")
                }
            }
        }
    }

    /**
     * Updates the persisted country name without modifying servers or index.
     * Used for relocalization when the language changes.
     */
    fun updateSelectedCountryName(ctx: Context, newCountryName: String) {
        // Read-check-write on KEY_COUNTRY, so it takes the same monitor as every other
        // selection write; otherwise a concurrent selection could land between the read of
        // currentName and this rename's write and get renamed out from under itself.
        synchronized(selectionRenameLock) {
            val currentName = getSelectedCountry(ctx)
            if (currentName.isNullOrBlank() || currentName == newCountryName) {
                AppLog.d(TAG, "updateSelectedCountryName: no change or no selection (current='$currentName', new='$newCountryName')")
                return
            }
            val prefs = prefs(ctx)
            val editor = prefs.edit().putString(KEY_COUNTRY, newCountryName)

            val lastSuccessCountry = prefs.getString(KEY_LAST_SUCCESS_COUNTRY, null)
            if (lastSuccessCountry == currentName) {
                editor.putString(KEY_LAST_SUCCESS_COUNTRY, newCountryName)
            }

            val lastStartedCountry = prefs.getString(KEY_LAST_STARTED_COUNTRY, null)
            if (lastStartedCountry == currentName) {
                editor.putString(KEY_LAST_STARTED_COUNTRY, newCountryName)
            }

            editor.apply()
            AppLog.i(TAG, "updateSelectedCountryName: '$currentName' -> '$newCountryName'")
            SelectedCountryVersionSignal.bump()
        }
    }

    /**
     * Atomically updates country name only when current selected country is still [expectedCurrentCountryName].
     * Prevents relocalization races from mutating a different active selection via single atomic read-check-write.
     * Must NOT call updateSelectedCountryName() after this check, as that creates TOCTOU race.
     */
    fun updateSelectedCountryNameIfCurrent(
        ctx: Context,
        expectedCurrentCountryName: String,
        newCountryName: String
    ): Boolean {
        val prefs = prefs(ctx)
        synchronized(selectionRenameLock) {
            // Guarded read-check-write block under single process lock.
            val currentName = prefs.getString(KEY_COUNTRY, null)
            if (currentName != expectedCurrentCountryName) {
                AppLog.w(
                    TAG,
                    "updateSelectedCountryNameIfCurrent: selection changed, skip rename expected='$expectedCurrentCountryName' actual='${currentName ?: "<none>"}'"
                )
                return false
            }

            val editor = prefs.edit().putString(KEY_COUNTRY, newCountryName)

            val lastSuccessCountry = prefs.getString(KEY_LAST_SUCCESS_COUNTRY, null)
            if (lastSuccessCountry == currentName) {
                editor.putString(KEY_LAST_SUCCESS_COUNTRY, newCountryName)
            }

            val lastStartedCountry = prefs.getString(KEY_LAST_STARTED_COUNTRY, null)
            if (lastStartedCountry == currentName) {
                editor.putString(KEY_LAST_STARTED_COUNTRY, newCountryName)
            }

            val committed = editor.commit()
            if (!committed) {
                AppLog.w(TAG, "updateSelectedCountryNameIfCurrent: commit failed for '$currentName' -> '$newCountryName'")
                return false
            }
            AppLog.i(TAG, "updateSelectedCountryNameIfCurrent: '$currentName' -> '$newCountryName' (guarded)")
            SelectedCountryVersionSignal.bump()
            return true
        }
    }

    fun getCurrentServerIdIfMatchingLastStarted(context: Context): Int {
        return runCatching {
            val lastStarted = getLastStartedConfig(context)
            val currentServer = currentServer(context)
            if (currentServer != null && lastStarted != null &&
                !currentServer.ip.isNullOrBlank() && currentServer.ip == lastStarted.ip) {
                currentServer.id
            } else {
                0
            }
        }.getOrElse { 0 }
    }
}



