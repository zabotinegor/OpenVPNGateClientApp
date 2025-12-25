package com.yahorzabotsin.openvpnclientgate.core.ui

import android.view.View
import android.widget.TextView
import android.widget.ImageView
import android.widget.FrameLayout
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    manifest = "src/main/AndroidManifest.xml",
    sdk = [27],
    packageName = "com.yahorzabotsin.openvpnclientgate.core"
)
class CountryListAdapterTest {

    @Test
    fun bind_populatesFieldsAndHidesMissingFlag() {
        val context = RuntimeEnvironment.getApplication()
        val country = Country(name = "Atlantis", code = "")
        val holder = CountryListAdapter.ViewHolder(buildItemView(context))
        holder.bind(CountryWithServers(country, serverCount = 2))

        val name = holder.itemView.findViewById<TextView>(R.id.country_name)
        val flag = holder.itemView.findViewById<TextView>(R.id.country_flag)
        val count = holder.itemView.findViewById<TextView>(R.id.server_count)
        val chevron = holder.itemView.findViewById<ImageView>(R.id.chevron_icon)

        assertEquals("Atlantis", name.text.toString())
        assertEquals(View.GONE, flag.visibility)
        assertEquals(
            context.resources.getQuantityString(R.plurals.server_count, 2, 2),
            count.text.toString()
        )
        assertEquals(View.VISIBLE, chevron.visibility)
    }

    @Test
    fun bind_showsFlagWhenAvailable() {
        val context = RuntimeEnvironment.getApplication()
        val country = Country(name = "United States", code = "US")
        val holder = CountryListAdapter.ViewHolder(buildItemView(context))
        holder.bind(CountryWithServers(country, serverCount = 1))

        val flag = holder.itemView.findViewById<TextView>(R.id.country_flag)
        assertEquals(View.VISIBLE, flag.visibility)
    }

    private fun buildItemView(context: android.content.Context): FrameLayout {
        val container = FrameLayout(context)
        container.addView(TextView(context).apply { id = R.id.country_name })
        container.addView(TextView(context).apply { id = R.id.country_flag })
        container.addView(TextView(context).apply { id = R.id.server_count })
        container.addView(ImageView(context).apply { id = R.id.chevron_icon })
        return container
    }
}
