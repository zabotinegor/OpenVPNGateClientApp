package com.yahorzabotsin.openvpnclientgate.mobile

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.contrib.DrawerActions
import androidx.test.espresso.contrib.DrawerMatchers
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

    @Test
    fun openDrawer_and_clickServerItem_closesDrawer() {
        ActivityScenario.launch(MainActivity::class.java)

        // Open the navigation drawer
        onView(withId(R.id.drawer_layout)).perform(DrawerActions.open())
        onView(withId(R.id.drawer_layout)).check(matches(DrawerMatchers.isOpen()))

        // Click on the first menu item (Server)
        onView(withText(R.string.menu_server)).perform(click())

        // Drawer should be closed after click
        onView(withId(R.id.drawer_layout)).check(matches(DrawerMatchers.isClosed()))
    }
}

