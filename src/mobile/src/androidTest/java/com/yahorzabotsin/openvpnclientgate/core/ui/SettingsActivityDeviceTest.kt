package com.yahorzabotsin.openvpnclientgate.core.ui

import android.content.Context
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.textfield.TextInputEditText
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import com.yahorzabotsin.openvpnclientgate.core.ui.settings.SettingsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsActivityDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE).edit().clear().apply()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Test
    fun loadsSavedCacheTtlIntoInput() {
        UserSettingsStore.saveCacheTtlMs(context, 15 * 60 * 1000L)

        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val cacheInput = root.findViewById<TextInputEditText>(R.id.cache_input)
                assertEquals("15", cacheInput.text.toString())
            }
        }
    }

    @Test
    fun savesCacheTtlOnInputChange() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val cacheInput = root.findViewById<TextInputEditText>(R.id.cache_input)
                cacheInput.setText("25")
                cacheInput.clearFocus()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }

        val saved = UserSettingsStore.load(context).cacheTtlMs
        assertEquals(25 * 60 * 1000L, saved)
    }

    @Test
    fun ignoresNonPositiveInput() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val cacheInput = root.findViewById<TextInputEditText>(R.id.cache_input)
                cacheInput.setText("0")
                cacheInput.clearFocus()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }

        val saved = UserSettingsStore.load(context).cacheTtlMs
        assertEquals(UserSettingsStore.DEFAULT_CACHE_TTL_MS, saved)
    }

    @Test
    fun loadsSavedStatusTimerIntoInput() {
        UserSettingsStore.saveStatusStallTimeoutSeconds(context, 12)

        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val statusInput = root.findViewById<TextInputEditText>(R.id.status_timer_input)
                assertEquals("12", statusInput.text.toString())
            }
        }
    }

    @Test
    fun savesStatusTimerOnInputChange() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val statusInput = root.findViewById<TextInputEditText>(R.id.status_timer_input)
                statusInput.setText("9")
                statusInput.clearFocus()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }

        val saved = UserSettingsStore.load(context).statusStallTimeoutSeconds
        assertEquals(9, saved)
    }

    @Test
    fun ignoresNonPositiveStatusTimerInput() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val statusInput = root.findViewById<TextInputEditText>(R.id.status_timer_input)
                statusInput.setText("0")
                statusInput.clearFocus()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }

        val saved = UserSettingsStore.load(context).statusStallTimeoutSeconds
        assertEquals(UserSettingsStore.DEFAULT_STATUS_STALL_TIMEOUT_SECONDS, saved)
    }
}
