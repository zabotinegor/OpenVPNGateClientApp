package com.yahorzabotsin.openvpnclientgate.vpn

import android.content.Context
import de.blinkt.openvpn.VpnProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.yahorzabotsin.openvpnclientgate.core.filter.AppFilterStore

/**
 * applyAppFilter() must establish the safe state -- nothing excluded, and the package list
 * interpreted as a *disallow* list -- before it reads the stored selection, because that read
 * can throw.
 *
 * Historically the read came first, so a corrupted preference left both assignments unexecuted
 * and the profile kept whatever it already carried. That happened to be harmless only because
 * upstream VpnProfile initializes mAllowedAppsVpnAreDisallowed = true with an empty set and the
 * profile always arrived fresh from ConfigParser -- a privacy-relevant guarantee resting on
 * engine-submodule defaults rather than on this client's code. These tests pin it down here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class OpenVpnServiceAppFilterTest {

    private lateinit var service: OpenVpnService

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        service = Robolectric.buildService(OpenVpnService::class.java).create().get()
    }

    /**
     * Guards the premise of the test below: a wrong-typed preference really does make the read
     * throw. If a future SharedPreferences change made getStringSet lenient, this fails loudly
     * rather than letting the failure-path tests pass vacuously.
     */
    @Test
    fun loadExcludedPackages_throwsOnWrongTypedPreference() {
        storeWrongTypedPreference()

        assertThrows(ClassCastException::class.java) {
            AppFilterStore.loadExcludedPackages(RuntimeEnvironment.getApplication())
        }
    }

    @Test
    fun applyAppFilter_readThrows_leavesNothingExcluded() {
        storeWrongTypedPreference()
        val profile = profileWithPreexistingDirectives()

        invokeApplyAppFilter(profile)

        assertTrue(
            "a failed read must not leave app-routing directives applied",
            profile.mAllowedAppsVpn.isEmpty()
        )
        assertTrue(
            "the disallow flag must be set even when the read fails",
            profile.mAllowedAppsVpnAreDisallowed
        )
    }

    @Test
    fun applyAppFilter_readSucceeds_appliesStoredSelection() {
        AppFilterStore.saveExcludedPackages(
            RuntimeEnvironment.getApplication(),
            setOf("com.example.one", "com.example.two")
        )
        val profile = profileWithPreexistingDirectives()

        invokeApplyAppFilter(profile)

        assertEquals(setOf("com.example.one", "com.example.two"), profile.mAllowedAppsVpn)
        assertTrue(profile.mAllowedAppsVpnAreDisallowed)
    }

    @Test
    fun applyAppFilter_emptySelection_clearsPreexistingDirectives() {
        val profile = profileWithPreexistingDirectives()

        invokeApplyAppFilter(profile)

        assertTrue(profile.mAllowedAppsVpn.isEmpty())
        assertTrue(profile.mAllowedAppsVpnAreDisallowed)
    }

    /**
     * A profile carrying directives from some earlier source. The production path always passes a
     * freshly parsed profile, so this is the case the ordering protects against if that ever
     * stops being true.
     */
    private fun profileWithPreexistingDirectives(): VpnProfile =
        VpnProfile("test").apply {
            mAllowedAppsVpn.add("com.stale.leftover")
            mAllowedAppsVpnAreDisallowed = false
        }

    private fun storeWrongTypedPreference() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_EXCLUDED_PACKAGES, "not-a-string-set")
            .commit()
    }

    private fun invokeApplyAppFilter(profile: VpnProfile) {
        val method = OpenVpnService::class.java
            .getDeclaredMethod("applyAppFilter", VpnProfile::class.java)
        method.isAccessible = true
        method.invoke(service, profile)
    }

    companion object {
        private const val PREFS_NAME = "app_filter"
        private const val KEY_EXCLUDED_PACKAGES = "excluded_packages"
    }
}
