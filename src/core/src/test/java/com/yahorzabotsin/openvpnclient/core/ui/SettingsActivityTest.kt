package com.yahorzabotsin.openvpnclient.core.ui

import android.content.Context
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.textfield.TextInputEditText
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.settings.UserSettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsActivityTest {

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
        // Save custom TTL and open activity
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
                cacheInput.clearFocus() // trigger text watcher
            }
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
        }
        val saved = UserSettingsStore.load(context).cacheTtlMs
        assertEquals(UserSettingsStore.DEFAULT_CACHE_TTL_MS, saved)
    }
}
