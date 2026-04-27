package com.yahorzabotsin.openvpnclientgate.tv

import android.content.Intent
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.PerformException
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.contrib.DrawerActions
import androidx.test.espresso.contrib.DrawerMatchers
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.android.material.navigation.NavigationView
import com.yahorzabotsin.openvpnclientgate.core.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke suite: E2E-ANDROID-TV-DRAWER-GUARD-SMOKE
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MainActivityTvDrawerGuardTest {

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
        } catch (e: RuntimeException) {
            if (e !is NoMatchingViewException && e !is PerformException) throw e
            dismissUpdatePromptIfVisible()
            onView(withId(R.id.drawer_layout)).perform(DrawerActions.open())
            onView(withId(R.id.nav_view)).check(matches(isDisplayed()))
        }
    }

    private inline fun withMainActivity(assertions: (ActivityScenario<MainActivity>) -> Unit) {
        val launchIntent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
            dismissUpdatePromptIfVisible()
            assertions(scenario)
        }
    }

    // E2E-ANDROID-TV-DRAWER-GUARD-001
    @Test
    fun openDrawer_keepsMainContentVisibleWhileDrawerIsOpen() {
        withMainActivity {
            openDrawerReliably()

            onView(withId(R.id.drawer_layout)).check(matches(DrawerMatchers.isOpen()))
            onView(withId(R.id.start_connection_button)).check(matches(isDisplayed()))
        }
    }

    // E2E-ANDROID-TV-DRAWER-GUARD-002
    @Test
    fun closeDrawer_restoresMainLayoutState() {
        withMainActivity {
            openDrawerReliably()

            onView(withId(R.id.drawer_layout)).perform(DrawerActions.close())

            onView(withId(R.id.drawer_layout)).check(matches(DrawerMatchers.isClosed()))
            onView(withId(R.id.start_connection_button)).check(matches(isDisplayed()))
        }
    }

    // E2E-ANDROID-TV-DRAWER-GUARD-003
    @Test
    fun openDrawer_blocksDescendantFocusOnConnectionControls() {
        withMainActivity { scenario ->
            openDrawerReliably()

            scenario.onActivity { activity ->
                val connectionControls = activity.findViewById<ViewGroup>(R.id.connection_controls)
                assertEquals(
                    "connectionControls must block descendants while drawer is open",
                    ViewGroup.FOCUS_BLOCK_DESCENDANTS,
                    connectionControls.descendantFocusability
                )
            }
        }
    }

    // E2E-ANDROID-TV-DRAWER-GUARD-004
    @Test
    fun closeDrawer_unblocksFocusOnConnectionControls() {
        withMainActivity { scenario ->
            var initialFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            scenario.onActivity { activity ->
                val connectionControls = activity.findViewById<ViewGroup>(R.id.connection_controls)
                initialFocusability = connectionControls.descendantFocusability
            }

            openDrawerReliably()

            onView(withId(R.id.drawer_layout)).perform(DrawerActions.close())
            onView(withId(R.id.drawer_layout)).check(matches(DrawerMatchers.isClosed()))

            scenario.onActivity { activity ->
                val connectionControls = activity.findViewById<ViewGroup>(R.id.connection_controls)
                assertEquals(
                    "connectionControls must restore baseline descendant focusability after drawer closes",
                    initialFocusability,
                    connectionControls.descendantFocusability
                )
            }
        }
    }

    // E2E-ANDROID-TV-DRAWER-GUARD-005
    @Test
    fun openDrawer_preservesCheckedDrawerItem() {
        withMainActivity { scenario ->
            scenario.onActivity { activity ->
                val navView = activity.findViewById<NavigationView>(R.id.nav_view)
                navView.setCheckedItem(R.id.nav_settings)
            }

            openDrawerReliably()

            scenario.onActivity { activity ->
                val navView = activity.findViewById<NavigationView>(R.id.nav_view)
                assertEquals(
                    "openDrawer should preserve currently checked drawer item",
                    R.id.nav_settings,
                    navView.checkedItem?.itemId
                )
            }
        }
    }
}
