package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog

class SelectedCountryServerSync(
    private val appContext: Context,
    private val serverRepository: ServerRepository
) {

    private val tag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "SelectedCountryServerSync"

    suspend fun syncAfterRefresh(freshServers: List<Server>) {
        val selectedCountry = SelectedCountryStore.getSelectedCountry(appContext)
        if (selectedCountry.isNullOrBlank()) return

        val selectedCountryCode = SelectedCountryStore.currentServer(appContext)?.countryCode
            ?: SelectedCountryStore.getServers(appContext).firstOrNull()?.countryCode

        val countryServers = selectedCountryCode?.let { code ->
            freshServers.filter { it.country.code.equals(code, ignoreCase = true) }
        }.orEmpty().ifEmpty {
            freshServers.filter { it.country.name.equals(selectedCountry, ignoreCase = true) }
        }
        if (countryServers.isEmpty()) {
            AppLog.w(
                tag,
                "Skipping selected country sync: country not found in fresh list (selectedCountry=$selectedCountry, selectedCountryCode=${selectedCountryCode ?: "<none>"})"
            )
            return
        }

        val configs = serverRepository.loadConfigs(appContext, countryServers)
        if (configs.isEmpty()) {
            AppLog.w(tag, "Skipping selected country sync: configs could not be loaded")
            return
        }

        val resolved = countryServers.mapNotNull { server ->
            configs[server.lineIndex]
                ?.takeIf { it.isNotBlank() }
                ?.let { config ->
                    server.copy(configData = config)
                }
        }
        if (resolved.isEmpty()) {
            AppLog.w(tag, "Skipping selected country sync: no valid server configs resolved")
            return
        }

        val localizedCountryName = resolved.first().country.name

        // This rewrite replaces the selected country's whole candidate pool with data from the
        // *currently active* source, so it supersedes any silent DEFAULT_V2 backfill still in
        // flight for the same country -- most visibly when the user just switched the source
        // setting (DEFAULT_V2 -> VPN Gate), which routes straight here. Without this bump the
        // backfill's generation guard still reads as current and its V2 pages land on top of the
        // pool written below, resetting the active server to index 0 whenever its config is
        // absent from that older V2 data.
        //
        // Bumped here rather than at the top of this method on purpose: everything above can
        // return early (country missing from the fresh list, configs unavailable), and
        // invalidating an otherwise useful backfill for a sync that never writes would strand the
        // candidate pool partial. Bumping immediately before the write is still race-free -- the
        // backfill re-evaluates its guard inside SelectedCountryStore's selection monitor, so it
        // either sees this bump and stands down, or completes before this write, which then wins
        // by landing last.
        //
        // All three names/codes are passed because a backfill keys its generation by
        // `countryCode ?: countryName`, and the stored name may differ from the freshly localized
        // one; supersede() de-duplicates and ignores blanks.
        CountrySyncGenerations.supersede(selectedCountry, selectedCountryCode, localizedCountryName)

        SelectedCountryStore.saveSelectionPreservingIndex(
            ctx = appContext,
            country = selectedCountry,
            servers = resolved
        )

        if (localizedCountryName != selectedCountry) {
            SelectedCountryStore.updateSelectedCountryNameIfCurrent(
                ctx = appContext,
                expectedCurrentCountryName = selectedCountry,
                newCountryName = localizedCountryName
            )
        }

        AppLog.i(
            tag,
            "Selected country sync completed. country=$localizedCountryName, selectedCountryCode=${selectedCountryCode ?: "<none>"}, servers=${resolved.size}"
        )
    }
}
