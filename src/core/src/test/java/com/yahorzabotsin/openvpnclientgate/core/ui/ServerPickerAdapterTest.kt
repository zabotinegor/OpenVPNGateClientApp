package com.yahorzabotsin.openvpnclientgate.core.ui

import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.SignalStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class ServerPickerAdapterTest {

    @Test
    fun bind_usesCityWhenPresent_andShowsFlag() {
        val context = RuntimeEnvironment.getApplication()
        val server = buildServer(city = "Seattle", name = "ServerName")
        val holder = ServerPickerAdapter.ViewHolder(buildItemView(context))
        holder.bind(server)

        val title = holder.itemView.findViewById<TextView>(R.id.server_title)
        val subtitle = holder.itemView.findViewById<TextView>(R.id.server_subtitle)
        val flag = holder.itemView.findViewById<TextView>(R.id.server_flag)
        val ping = holder.itemView.findViewById<TextView>(R.id.server_ping)
        val signal = holder.itemView.findViewById<ImageView>(R.id.server_signal)

        assertEquals("Seattle", title.text.toString())
        assertEquals("10.0.0.1", subtitle.text.toString())
        assertEquals(View.VISIBLE, flag.visibility)
        assertEquals(context.getString(R.string.ping_ms_format, 42), ping.text.toString())
        assertNotNull(signal.drawable)
    }

    @Test
    fun bind_fallsBackToNameWhenCityBlank() {
        val context = RuntimeEnvironment.getApplication()
        val server = buildServer(city = "", name = "FallbackName")
        val holder = ServerPickerAdapter.ViewHolder(buildItemView(context))
        holder.bind(server)

        val title = holder.itemView.findViewById<TextView>(R.id.server_title)
        assertEquals("FallbackName", title.text.toString())
    }

    private fun buildServer(city: String, name: String): Server = Server(
        lineIndex = 0,
        name = name,
        city = city,
        country = Country(name = "United States", code = "US"),
        ping = 42,
        signalStrength = SignalStrength.STRONG,
        ip = "10.0.0.1",
        score = 100,
        speed = 1000,
        numVpnSessions = 1,
        uptime = 100,
        totalUsers = 1,
        totalTraffic = 1000,
        logType = "",
        operator = "",
        message = "",
        configData = ""
    )

    private fun buildItemView(context: android.content.Context): FrameLayout {
        val container = FrameLayout(context)
        container.addView(TextView(context).apply { id = R.id.server_title })
        container.addView(TextView(context).apply { id = R.id.server_subtitle })
        container.addView(ImageView(context).apply { id = R.id.chevron_icon })
        container.addView(TextView(context).apply { id = R.id.server_flag })
        container.addView(TextView(context).apply { id = R.id.server_ping })
        container.addView(ImageView(context).apply { id = R.id.server_signal })
        return container
    }
}
