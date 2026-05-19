package com.yahorzabotsin.openvpnclientgate.core.ui

import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.SignalStrength
import com.yahorzabotsin.openvpnclientgate.core.ui.serverlist.ServerPickerAdapter
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
class ServerPickerAdapterCityUtcTest {

    // AC-2.1 — Server card renders city and UTC when both present
    @Test
    fun bind_rendersCityAndUtc_whenBothPresent() {
        val context = RuntimeEnvironment.getApplication()
        val server = buildServer(city = "Tokyo", utc = "UTC+9", name = "ServerName")
        val holder = ServerPickerAdapter.ViewHolder(buildItemView(context))
        holder.bind(server)

        val title = holder.itemView.findViewById<TextView>(R.id.server_title)
        val subtitle = holder.itemView.findViewById<TextView>(R.id.server_subtitle)

        assertEquals("Tokyo (UTC+9)", title.text.toString())
        assertEquals("10.0.0.1", subtitle.text.toString())
    }

    // AC-2.1 — Server card renders city and UTC in expected format
    @Test
    fun bind_rendersCityAndUtc_inCorrectFormat() {
        val context = RuntimeEnvironment.getApplication()
        val server = buildServer(city = "Paris", utc = "UTC+1", name = "ServerName")
        val holder = ServerPickerAdapter.ViewHolder(buildItemView(context))
        holder.bind(server)

        val title = holder.itemView.findViewById<TextView>(R.id.server_title)
        assertEquals("Paris (UTC+1)", title.text.toString())
    }

    // AC-2.2 — Server card falls back to IP only when UTC is null
    @Test
    fun bind_fallsBackToIpOnly_whenUtcIsNull() {
        val context = RuntimeEnvironment.getApplication()
        val server = buildServer(city = "Berlin", utc = null, name = "ServerName")
        val holder = ServerPickerAdapter.ViewHolder(buildItemView(context))
        holder.bind(server)

        val title = holder.itemView.findViewById<TextView>(R.id.server_title)
        val subtitle = holder.itemView.findViewById<TextView>(R.id.server_subtitle)
        assertEquals("Berlin", title.text.toString())
        assertEquals("10.0.0.1", subtitle.text.toString())
    }

    // AC-2.2 — Server card falls back to IP only when UTC is blank
    @Test
    fun bind_fallsBackToIpOnly_whenUtcIsBlank() {
        val context = RuntimeEnvironment.getApplication()
        val server = buildServer(city = "Amsterdam", utc = "", name = "ServerName")
        val holder = ServerPickerAdapter.ViewHolder(buildItemView(context))
        holder.bind(server)

        val title = holder.itemView.findViewById<TextView>(R.id.server_title)
        val subtitle = holder.itemView.findViewById<TextView>(R.id.server_subtitle)
        assertEquals("Amsterdam", title.text.toString())
        assertEquals("10.0.0.1", subtitle.text.toString())
    }

    // AC-2.2 — Server card shows IP and blank UTC doesn't show empty parentheses
    @Test
    fun bind_doesNotShowEmptyParentheses_whenUtcBlank() {
        val context = RuntimeEnvironment.getApplication()
        val server = buildServer(city = "Madrid", utc = "   ", name = "ServerName")
        val holder = ServerPickerAdapter.ViewHolder(buildItemView(context))
        holder.bind(server)

        val title = holder.itemView.findViewById<TextView>(R.id.server_title)
        val subtitle = holder.itemView.findViewById<TextView>(R.id.server_subtitle)
        assertEquals("Madrid", title.text.toString())
        assertEquals("10.0.0.1", subtitle.text.toString())
    }

    // AC-5.1 — Legacy server without UTC shows IP as before
    @Test
    fun bind_legacyServer_showsIpOnly() {
        val context = RuntimeEnvironment.getApplication()
        // Simulate legacy server with no UTC
        val server = buildServer(city = "NewYork", utc = null, name = "LegacyServer")
        val holder = ServerPickerAdapter.ViewHolder(buildItemView(context))
        holder.bind(server)

        val subtitle = holder.itemView.findViewById<TextView>(R.id.server_subtitle)
        assertEquals("10.0.0.1", subtitle.text.toString())
    }

    // AC-2.1 — Negative UTC values are handled correctly
    @Test
    fun bind_handlesNegativeUtc() {
        val context = RuntimeEnvironment.getApplication()
        val server = buildServer(city = "SanFrancisco", utc = "UTC-8", name = "ServerName")
        val holder = ServerPickerAdapter.ViewHolder(buildItemView(context))
        holder.bind(server)

        val title = holder.itemView.findViewById<TextView>(R.id.server_title)
        val subtitle = holder.itemView.findViewById<TextView>(R.id.server_subtitle)
        assertEquals("SanFrancisco (UTC-8)", title.text.toString())
        assertEquals("10.0.0.1", subtitle.text.toString())
    }

    // AC-2.3 — Ping and signal behavior remain unchanged with UTC display
    @Test
    fun bind_preservesPingAndSignal_withUtc() {
        val context = RuntimeEnvironment.getApplication()
        val server = buildServer(city = "Sydney", utc = "UTC+10", name = "ServerName", ping = 55)
        val holder = ServerPickerAdapter.ViewHolder(buildItemView(context))
        holder.bind(server)

        val ping = holder.itemView.findViewById<TextView>(R.id.server_ping)
        val signal = holder.itemView.findViewById<ImageView>(R.id.server_signal)

        assertEquals(context.getString(R.string.ping_ms_format, 55), ping.text.toString())
        assertNotNull(signal.drawable)
    }

    // AC-2.1 — Multiple UTC formats are supported
    @Test
    fun bind_supportsVariousUtcFormats() {
        val context = RuntimeEnvironment.getApplication()

        val formats = listOf(
            Pair("UTC+0", "Test (UTC+0)"),
            Pair("UTC+5:30", "Test (UTC+5:30)"),
            Pair("GMT+12", "Test (GMT+12)"),
            Pair("+05:00", "Test (+05:00)")
        )

        for ((utc, expected) in formats) {
            val server = buildServer(city = "Test", utc = utc, name = "ServerName")
            val holder = ServerPickerAdapter.ViewHolder(buildItemView(context))
            holder.bind(server)

            val title = holder.itemView.findViewById<TextView>(R.id.server_title)
            val subtitle = holder.itemView.findViewById<TextView>(R.id.server_subtitle)
            assertEquals("Expected format for $utc", expected, title.text.toString())
            assertEquals("10.0.0.1", subtitle.text.toString())
        }
    }

    private fun buildServer(city: String, utc: String? = null, name: String, ping: Int = 42): Server = Server(
        lineIndex = 0,
        name = name,
        city = city,
        country = Country(name = "United States", code = "US"),
        ping = ping,
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
        configData = "",
        utc = utc
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
