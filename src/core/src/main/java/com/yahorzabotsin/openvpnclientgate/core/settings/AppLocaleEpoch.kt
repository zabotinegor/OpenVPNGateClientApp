package com.yahorzabotsin.openvpnclientgate.core.settings

import androidx.annotation.VisibleForTesting
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide monotonic ticket advanced every time the persisted app language is written.
 *
 * Background work whose results are only valid for the language it started in (today: the silent
 * V2 backfill in `DefaultCountryServersInteractor`) captures [current] at launch and stands down
 * once it no longer matches. Comparing the *resolved locale* alone is not sufficient:
 *
 * - Every `ServersV2Repository` call resolves the current locale independently, so the language
 *   in force when a page is fetched is decided per request, not once per paging session.
 * - An equality check against the launch locale therefore reads as unchanged after the language
 *   moved away and back (`en -> ru -> en`) while a page request was in flight. Pages fetched in
 *   the intermediate language would then be merged with the launch-language ones, stored in the
 *   selected-country pool, and cached under the launch locale's key with a fresh TTL stamp --
 *   stranding a mixed-language list for the rest of that TTL.
 *
 * The ticket is advanced *before* the new language is published (see [UserSettingsStore]), so a
 * guard can never observe the new language paired with the old ticket. The reverse skew is
 * harmless: a guard that sees the new ticket with the old language simply stands down one instant
 * early.
 *
 * The ticket covers language changes made through the app's own settings. A change of the OS
 * locale under [LanguageOption.SYSTEM] never reaches this store, so guards pair the ticket with a
 * live resolved-locale comparison -- the same belt-and-braces shape [ServerSourceEpoch] uses.
 *
 * Only ever compared for equality with a captured value, so wraparound is not a concern and the
 * counter is deliberately never reset -- monotonicity must survive for the life of the process.
 */
object AppLocaleEpoch {
    private val epoch = AtomicLong(0L)

    /** The current ticket. Capture this before starting language-dependent background work. */
    fun current(): Long = epoch.get()

    /**
     * Advances the ticket. Called by [UserSettingsStore] on every write that can change the
     * persisted language, including writes that happen to store the same value again -- a
     * spurious advance only makes an in-flight job stand down, which is always the safe
     * direction.
     */
    @VisibleForTesting
    internal fun bump(): Long = epoch.incrementAndGet()
}
