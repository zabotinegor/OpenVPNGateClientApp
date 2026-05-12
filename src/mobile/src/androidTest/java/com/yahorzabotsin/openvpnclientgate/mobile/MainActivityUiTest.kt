package com.yahorzabotsin.openvpnclientgate.mobile

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBackUnconditionally
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.PerformException
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.contrib.DrawerActions
import androidx.test.espresso.contrib.DrawerMatchers
import androidx.test.espresso.contrib.NavigationViewActions
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.yahorzabotsin.openvpnclientgate.core.R
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class MainActivityUiTest {

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
            onView(withId(R.id.drawer_layout)).check(matches(DrawerMatchers.isOpen()))
        } catch (e: RuntimeException) {
            if (e !is NoMatchingViewException && e !is PerformException) throw e
            dismissUpdatePromptIfVisible()
            onView(withId(R.id.drawer_layout)).perform(DrawerActions.open())
            onView(withId(R.id.drawer_layout)).check(matches(DrawerMatchers.isOpen()))
        }
    }

    @Test
    fun openDrawer_and_clickServerItem_opensServerList() {
        val launchIntent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        ActivityScenario.launch<MainActivity>(launchIntent).use {
            dismissUpdatePromptIfVisible()

            // Open the navigation drawer
            openDrawerReliably()

            // Click on the first menu item (Server)
            onView(withId(R.id.nav_view)).perform(NavigationViewActions.navigateTo(R.id.nav_server))

            // Server list activity should be shown after navigation
            onView(withId(R.id.server_info_card)).check(matches(isDisplayed()))
            pressBackUnconditionally()
        }
    }
}

