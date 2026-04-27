package com.yahorzabotsin.openvpnclientgate.tv

import android.content.Intent
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
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.yahorzabotsin.openvpnclientgate.core.R
import org.hamcrest.CoreMatchers.not
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

    private inline fun withMainActivity(assertions: () -> Unit) {
        val launchIntent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        ActivityScenario.launch<MainActivity>(launchIntent).use {
            dismissUpdatePromptIfVisible()
            assertions()
        }
    }

    // E2E-ANDROID-TV-DRAWER-GUARD-001
    @Test
    fun openDrawer_blocksStartConnectionButton() {
        withMainActivity {
            onView(withId(R.id.start_connection_button)).check(matches(isEnabled()))

            openDrawerReliably()

            onView(withId(R.id.drawer_layout)).check(matches(DrawerMatchers.isOpen()))
            onView(withId(R.id.start_connection_button)).check(matches(not(isEnabled())))
        }
    }

    // E2E-ANDROID-TV-DRAWER-GUARD-002
    @Test
    fun closeDrawer_restoresStartConnectionButton() {
        withMainActivity {
            openDrawerReliably()
            onView(withId(R.id.start_connection_button)).check(matches(not(isEnabled())))

            onView(withId(R.id.drawer_layout)).perform(DrawerActions.close())

            onView(withId(R.id.drawer_layout)).check(matches(DrawerMatchers.isClosed()))
            onView(withId(R.id.start_connection_button)).check(matches(isEnabled()))
        }
    }
}
