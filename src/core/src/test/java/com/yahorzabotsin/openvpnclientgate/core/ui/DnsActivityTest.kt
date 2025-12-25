package com.yahorzabotsin.openvpnclientgate.core.ui

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.logging.LogTags
import com.yahorzabotsin.openvpnclientgate.core.settings.DnsOptions
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DnsActivityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val logTag = LogTags.APP + ":" + "DnsActivity"

    @Before
    fun setUp() {
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE).edit().clear().commit()
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE).edit().clear().commit()
        ShadowLog.clear()
    }

    @Test
    fun loadsSavedDnsOptionIntoAdapter() {
        val option = DnsOptions.providers.first().option
        UserSettingsStore.saveDnsOption(context, option)

        ActivityScenario.launch(DnsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val recycler = activity.findViewById<RecyclerView>(R.id.dns_recycler_view)
                val adapter = recycler.adapter as DnsOptionAdapter
                assertEquals(option, adapter.selectedOption)
            }
        }
    }

    @Test
    fun defaultsToServerDnsWhenNotSaved() {
        ActivityScenario.launch(DnsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val recycler = activity.findViewById<RecyclerView>(R.id.dns_recycler_view)
                val adapter = recycler.adapter as DnsOptionAdapter
                assertEquals(com.yahorzabotsin.openvpnclientgate.core.settings.DnsOption.SERVER, adapter.selectedOption)
            }
        }
    }

    @Test
    fun selectionUpdatesSettingsAndLogs() {
        val providers = DnsOptions.providers
        assumeTrue(providers.size >= 2)
        UserSettingsStore.saveDnsOption(context, providers[0].option)

        ActivityScenario.launch(DnsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val recycler = activity.findViewById<RecyclerView>(R.id.dns_recycler_view)
                recycler.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST)
                )
                recycler.layout(0, 0, 1080, 1920)
                (recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(1, 0)
                recycler.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST)
                )
                recycler.layout(0, 0, 1080, 1920)
                val holder = recycler.findViewHolderForAdapterPosition(1)
                assertNotNull(holder)
                holder!!.itemView.performClick()
            }
        }

        val saved = UserSettingsStore.load(context).dnsOption
        assertEquals(providers[1].option, saved)

        val logs = ShadowLog.getLogs().filter { it.tag == logTag }.map { it.msg }
        assertTrue(logs.any { it.contains("DNS selection changed") })
    }
}

