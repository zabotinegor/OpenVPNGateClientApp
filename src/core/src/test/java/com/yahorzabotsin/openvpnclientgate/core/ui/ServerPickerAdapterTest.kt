package com.yahorzabotsin.openvpnclientgate.core.ui

import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.SignalStrength
import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.UiText
import com.yahorzabotsin.openvpnclientgate.core.ui.serverlist.ServerListItem
import com.yahorzabotsin.openvpnclientgate.core.ui.serverlist.ServerPickerAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
        val holder = ServerPickerAdapter.ViewHolder(buildItemView(context), isDefaultV2Source = false)
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
        val holder = ServerPickerAdapter.ViewHolder(buildItemView(context), isDefaultV2Source = false)
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

    private fun buildHeaderView(context: android.content.Context): FrameLayout {
        val container = FrameLayout(context)
        container.addView(TextView(context).apply { id = R.id.section_header_title })
        return container
    }

    // --- SUB-03: sealed list items (pinned favorites section + long-press) ---

    @Test
    fun `renders section header then server rows with correct view types`() {
        val serverA = buildServer(city = "Paris", name = "srv-a").copy(id = 1)
        val serverB = buildServer(city = "Nice", name = "srv-b").copy(id = 2)
        val items = listOf(
            ServerListItem.SectionHeader(UiText.Res(R.string.favorites_section_title)),
            ServerListItem.ServerRow(serverA, isFavorite = true),
            ServerListItem.ServerRow(serverB, isFavorite = false)
        )
        val adapter = ServerPickerAdapter(items, isDefaultV2Source = false, onClick = {}, onLongClick = { _, _, _ -> })

        assertEquals(3, adapter.itemCount)
        assertEquals(0, adapter.getItemViewType(0))
        assertEquals(1, adapter.getItemViewType(1))
        assertEquals(1, adapter.getItemViewType(2))
    }

    @Test
    fun `short tap invokes onClick with the row's server`() {
        val context = RuntimeEnvironment.getApplication()
        val serverA = buildServer(city = "Paris", name = "srv-a").copy(id = 1)
        val items = listOf(ServerListItem.ServerRow(serverA, isFavorite = false))
        var clicked: Server? = null
        val adapter = ServerPickerAdapter(items, isDefaultV2Source = false, onClick = { clicked = it }, onLongClick = { _, _, _ -> })
        val holder = ServerPickerAdapter.ViewHolder(buildItemView(context), isDefaultV2Source = false)
        adapter.onBindViewHolder(holder, 0)

        holder.itemView.performClick()

        assertEquals(serverA, clicked)
    }

    @Test
    fun `long press invokes onLongClick with server and current favorite state`() {
        val context = RuntimeEnvironment.getApplication()
        val serverA = buildServer(city = "Paris", name = "srv-a").copy(id = 1)
        val items = listOf(ServerListItem.ServerRow(serverA, isFavorite = true))
        var longPressedServer: Server? = null
        var longPressedIsFavorite: Boolean? = null
        val adapter = ServerPickerAdapter(
            items,
            isDefaultV2Source = false,
            onClick = {},
            onLongClick = { _, server, isFavorite ->
                longPressedServer = server
                longPressedIsFavorite = isFavorite
            }
        )
        val holder = ServerPickerAdapter.ViewHolder(buildItemView(context), isDefaultV2Source = false)
        adapter.onBindViewHolder(holder, 0)

        val handled = holder.itemView.performLongClick()

        assertTrue(handled)
        assertEquals(serverA, longPressedServer)
        assertEquals(true, longPressedIsFavorite)
    }

    @Test
    fun `section header binds title text`() {
        val context = RuntimeEnvironment.getApplication()
        val items = listOf(ServerListItem.SectionHeader(UiText.Res(R.string.favorites_section_title)))
        val adapter = ServerPickerAdapter(items, isDefaultV2Source = false, onClick = {}, onLongClick = { _, _, _ -> })
        val holder = ServerPickerAdapter.HeaderViewHolder(buildHeaderView(context))
        adapter.onBindViewHolder(holder, 0)

        val titleView = holder.itemView.findViewById<TextView>(R.id.section_header_title)
        assertEquals(context.getString(R.string.favorites_section_title), titleView.text.toString())
    }

    @Test
    fun `updateItems changes items without recreating adapter`() {
        val serverA = buildServer(city = "Paris", name = "srv-a").copy(id = 1)
        val serverB = buildServer(city = "Nice", name = "srv-b").copy(id = 2)
        val initialItems = listOf(ServerListItem.ServerRow(serverA, isFavorite = false))
        val adapter = ServerPickerAdapter(initialItems, isDefaultV2Source = false, onClick = {}, onLongClick = { _, _, _ -> })

        assertEquals(1, adapter.itemCount)

        val newItems = listOf(
            ServerListItem.SectionHeader(UiText.Res(R.string.favorites_section_title)),
            ServerListItem.ServerRow(serverA, isFavorite = true),
            ServerListItem.ServerRow(serverB, isFavorite = false)
        )
        adapter.updateItems(newItems)

        assertEquals(3, adapter.itemCount)
        assertEquals(0, adapter.getItemViewType(0))
        assertEquals(1, adapter.getItemViewType(1))
        assertEquals(1, adapter.getItemViewType(2))
    }
}
