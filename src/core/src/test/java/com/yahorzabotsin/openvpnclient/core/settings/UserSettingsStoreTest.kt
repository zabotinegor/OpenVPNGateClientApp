package com.yahorzabotsin.openvpnclient.core.settings

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class UserSettingsStoreTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun load_uses_legacy_timeout_when_new_key_missing() {
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)
            .edit()
            .putInt("auto_switch_timeout_seconds", 7)
            .commit()

        val settings = UserSettingsStore.load(context)
        assertEquals(7, settings.statusStallTimeoutSeconds)
    }

    @Test
    fun load_clamps_legacy_timeout_to_minimum() {
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)
            .edit()
            .putInt("auto_switch_timeout_seconds", 0)
            .commit()

        val settings = UserSettingsStore.load(context)
        assertEquals(1, settings.statusStallTimeoutSeconds)
    }

    @Test
    fun save_status_stall_timeout_clamps_to_minimum() {
        UserSettingsStore.saveStatusStallTimeoutSeconds(context, 0)

        val settings = UserSettingsStore.load(context)
        assertEquals(1, settings.statusStallTimeoutSeconds)
    }
}
