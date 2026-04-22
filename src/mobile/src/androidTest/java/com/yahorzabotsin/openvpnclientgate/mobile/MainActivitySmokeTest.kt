package com.yahorzabotsin.openvpnclientgate.mobile

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBackUnconditionally
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.contrib.DrawerActions
import androidx.test.espresso.contrib.DrawerMatchers
import androidx.test.espresso.contrib.NavigationViewActions
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.yahorzabotsin.openvpnclientgate.core.R
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke suite: E2E-ANDROID-MOBILE-SMOKE
 *
 * Covers basic main-screen structure and navigation-drawer routing without
 * triggering a real VPN connection. All cases run on a single connected device
 * via standard Espresso instrumentation (no mocking required).
 *
 * Referenced from: .github/testing/android-device-e2e/suites/SUITE-ANDROID-MOBILE-SMOKE.md
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MainActivitySmokeTest {

    private fun dismissUpdatePromptIfVisible() {
        try {
            onView(withId(android.R.id.button2)).perform(click())
        } catch (_: NoMatchingViewException) {
            // Update prompt is not shown for this run.
        }
    }

    private fun openDrawerReliably() {
        try {
            onView(withId(R.id.drawer_layout)).perform(DrawerActions.open())
            onView(withId(R.id.nav_view)).check(matches(isDisplayed()))
        } catch (_: NoMatchingViewException) {
            dismissUpdatePromptIfVisible()
            onView(withId(R.id.drawer_layout)).perform(DrawerActions.open())
            onView(withId(R.id.nav_view)).check(matches(isDisplayed()))
        }
    }

    private inline fun withMainActivity(assertions: () -> Unit) {
        val launchIntent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        ActivityScenario.launch<MainActivity>(launchIntent).use {
            dismissUpdatePromptIfVisible()
            assertions()
        }
    }

    // E2E-ANDROID-MAIN-LAUNCH-001
    @Test
    fun mainActivity_launches_and_toolbar_is_visible() {
        withMainActivity {
            onView(withId(R.id.toolbar)).check(matches(isDisplayed()))
            onView(withId(R.id.toolbar_title)).check(matches(isDisplayed()))
        }
    }

    // E2E-ANDROID-MAIN-CONTROLS-VISIBLE-005
    @Test
    fun connectionControls_are_visible_on_launch() {
        withMainActivity {
            onView(withId(R.id.connection_controls)).check(matches(isDisplayed()))
        }
    }

    // E2E-ANDROID-MAIN-DRAWER-002
    @Test
    fun openDrawer_drawerOpens_and_navViewVisible() {
        withMainActivity {
            openDrawerReliably()
            onView(withId(R.id.drawer_layout)).check(matches(DrawerMatchers.isOpen()))
            onView(withId(R.id.nav_view)).check(matches(isDisplayed()))
        }
    }

    // E2E-ANDROID-MAIN-NAV-SETTINGS-003
    @Test
    fun openDrawer_and_clickSettings_opensSettingsScreen() {
        withMainActivity {
            openDrawerReliably()
            onView(withId(R.id.nav_view)).perform(NavigationViewActions.navigateTo(R.id.nav_settings))
            onView(withId(R.id.language_header)).check(matches(isDisplayed()))
            pressBackUnconditionally()
        }
    }

    // E2E-ANDROID-MAIN-NAV-ABOUT-004
    @Test
    fun openDrawer_and_clickAbout_opensAboutScreen() {
        withMainActivity {
            openDrawerReliably()
            onView(withId(R.id.nav_view)).perform(NavigationViewActions.navigateTo(R.id.nav_about))
            onView(withId(R.id.about_root)).check(matches(isDisplayed()))
            pressBackUnconditionally()
        }
    }

    // E2E-ANDROID-MAIN-NAV-DNS-007
    @Test
    fun openDrawer_and_clickDns_opensDnsScreen() {
        withMainActivity {
            openDrawerReliably()
            onView(withId(R.id.nav_view)).perform(NavigationViewActions.navigateTo(R.id.nav_dns))
            onView(withId(R.id.dns_info_card)).check(matches(isDisplayed()))
            pressBackUnconditionally()
        }
    }

    // E2E-ANDROID-MAIN-NAV-FILTER-008
    @Test
    fun openDrawer_and_clickFilter_opensFilterScreen() {
        withMainActivity {
            openDrawerReliably()
            onView(withId(R.id.nav_view)).perform(NavigationViewActions.navigateTo(R.id.nav_filter))
            onView(withId(R.id.pager)).check(matches(isDisplayed()))
            pressBackUnconditionally()
        }
    }
}
