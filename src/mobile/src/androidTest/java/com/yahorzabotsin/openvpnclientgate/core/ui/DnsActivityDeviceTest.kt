package com.yahorzabotsin.openvpnclientgate.core.ui

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.dns.DnsOption
import com.yahorzabotsin.openvpnclientgate.core.dns.DnsOptions
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import com.yahorzabotsin.openvpnclientgate.core.ui.dns.DnsActivity
import com.yahorzabotsin.openvpnclientgate.core.ui.dns.DnsOptionAdapter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DnsActivityDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE).edit().clear().commit()
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
                assertEquals(DnsOption.SERVER, adapter.selectedOption)
            }
        }
    }

    @Test
    fun selectionUpdatesSettings() {
        val providers = DnsOptions.providers
        require(providers.size >= 2) { "Need at least two DNS providers for selection test" }
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
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }

        val saved = UserSettingsStore.load(context).dnsOption
        assertEquals(providers[1].option, saved)
    }
}
