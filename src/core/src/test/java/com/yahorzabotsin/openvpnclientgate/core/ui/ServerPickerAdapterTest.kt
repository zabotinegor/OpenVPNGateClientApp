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
import com.yahorzabotsin.openvpnclientgate.core.ui.serverlist.FooterState
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
        container.addView(ImageView(context).apply { id = R.id.row_favorite_star })
        return container
    }

    private fun buildHeaderView(context: android.content.Context): FrameLayout {
        val container = FrameLayout(context)
        container.addView(TextView(context).apply { id = R.id.section_header_title })
        container.addView(ImageView(context).apply { id = R.id.section_header_icon })
        return container
    }

    private fun buildFooterView(context: android.content.Context): FrameLayout {
        // Plain View/TextView stand-ins for the real item_server_list_footer.xml layout
        // (mirrors buildItemView/buildHeaderView above): avoids inflating MaterialButton/
        // MaterialCardView directly, which requires app theme resolution unavailable to core
        // unit tests running Robolectric in legacy resources mode.
        val container = FrameLayout(context)
        container.addView(View(context).apply { id = R.id.footer_progress })
        val errorGroup = FrameLayout(context).apply { id = R.id.footer_error_group }
        errorGroup.addView(TextView(context).apply { id = R.id.footer_error_text })
        errorGroup.addView(View(context).apply { id = R.id.footer_retry_button })
        container.addView(errorGroup)
        return container
    }

    // --- sealed list items (pinned favorites section + long-press) ---

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

    // --- Review round 1: no long-press affordance for non-favoritable rows (id <= 0) ---

    @Test
    fun `long press is disabled for non-favoritable rows with non-positive id`() {
        val context = RuntimeEnvironment.getApplication()
        val legacyServer = buildServer(city = "Paris", name = "srv-legacy") // default id = 0
        val items = listOf(ServerListItem.ServerRow(legacyServer, isFavorite = false))
        var longPressed = false
        val adapter = ServerPickerAdapter(
            items,
            isDefaultV2Source = false,
            onClick = {},
            onLongClick = { _, _, _ -> longPressed = true }
        )
        val holder = ServerPickerAdapter.ViewHolder(buildItemView(context), isDefaultV2Source = false)
        // Give the row a parent so performLongClick's context-menu fallback is a no-op.
        FrameLayout(context).addView(holder.itemView)
        adapter.onBindViewHolder(holder, 0)

        assertEquals(false, holder.itemView.isLongClickable)
        holder.itemView.performLongClick()
        assertEquals(false, longPressed)
    }

    @Test
    fun `recycled holder loses long-press affordance when rebound to a non-favoritable row`() {
        val context = RuntimeEnvironment.getApplication()
        val favoritable = buildServer(city = "Paris", name = "srv-a").copy(id = 1)
        val legacy = buildServer(city = "Nice", name = "srv-legacy") // default id = 0
        val items = listOf(
            ServerListItem.ServerRow(favoritable, isFavorite = false),
            ServerListItem.ServerRow(legacy, isFavorite = false)
        )
        var longPressed = false
        val adapter = ServerPickerAdapter(
            items,
            isDefaultV2Source = false,
            onClick = {},
            onLongClick = { _, _, _ -> longPressed = true }
        )
        val holder = ServerPickerAdapter.ViewHolder(buildItemView(context), isDefaultV2Source = false)
        // Give the row a parent so performLongClick's context-menu fallback is a no-op.
        FrameLayout(context).addView(holder.itemView)

        adapter.onBindViewHolder(holder, 0)
        assertEquals(true, holder.itemView.isLongClickable)

        // Simulate RecyclerView recycling the same holder for the legacy row.
        adapter.onBindViewHolder(holder, 1)
        assertEquals(false, holder.itemView.isLongClickable)
        holder.itemView.performLongClick()
        assertEquals(false, longPressed)
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

    // --- star icon shown only on the pinned Favorites header ---

    @Test
    fun `section header shows star icon only when showFavoriteIcon is true`() {
        val context = RuntimeEnvironment.getApplication()
        val favoritesHeader = listOf(
            ServerListItem.SectionHeader(UiText.Res(R.string.favorites_section_title), showFavoriteIcon = true)
        )
        val adapter = ServerPickerAdapter(favoritesHeader, isDefaultV2Source = false, onClick = {}, onLongClick = { _, _, _ -> })
        val holder = ServerPickerAdapter.HeaderViewHolder(buildHeaderView(context))
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.itemView.findViewById<ImageView>(R.id.section_header_icon).visibility)

        val allServersHeader = listOf(
            ServerListItem.SectionHeader(UiText.Res(R.string.all_servers_section_title))
        )
        val adapter2 = ServerPickerAdapter(allServersHeader, isDefaultV2Source = false, onClick = {}, onLongClick = { _, _, _ -> })
        val holder2 = ServerPickerAdapter.HeaderViewHolder(buildHeaderView(context))
        adapter2.onBindViewHolder(holder2, 0)
        assertEquals(View.GONE, holder2.itemView.findViewById<ImageView>(R.id.section_header_icon).visibility)
    }

    // --- pinned section frame boundary (isPinnedSection / pinnedSectionItemCount) ---

    @Test
    fun `pinnedSectionItemCount is zero when favorites section is hidden`() {
        val serverA = buildServer(city = "Paris", name = "srv-a").copy(id = 1)
        val items = listOf(ServerListItem.ServerRow(serverA, isFavorite = false))
        val adapter = ServerPickerAdapter(items, isDefaultV2Source = false, onClick = {}, onLongClick = { _, _, _ -> })

        assertEquals(0, adapter.pinnedSectionItemCount())
    }

    @Test
    fun `pinnedSectionItemCount counts header plus pinned rows only, excluding regular list rows`() {
        val serverA = buildServer(city = "Paris", name = "srv-a").copy(id = 1)
        val serverB = buildServer(city = "Nice", name = "srv-b").copy(id = 2)
        val serverC = buildServer(city = "Lyon", name = "srv-c").copy(id = 3)
        val items = listOf(
            ServerListItem.SectionHeader(UiText.Res(R.string.favorites_section_title)),
            ServerListItem.ServerRow(serverA, isFavorite = true, isPinnedSection = true),
            ServerListItem.ServerRow(serverB, isFavorite = true, isPinnedSection = true),
            // Regular (unheaded) list below: includes the same favorites again at their
            // normal position, but isPinnedSection = false so it must not extend the frame.
            ServerListItem.ServerRow(serverA, isFavorite = true),
            ServerListItem.ServerRow(serverB, isFavorite = true),
            ServerListItem.ServerRow(serverC, isFavorite = false)
        )
        val adapter = ServerPickerAdapter(items, isDefaultV2Source = false, onClick = {}, onLongClick = { _, _, _ -> })

        // header (1) + 2 pinned favorite rows = 3; the 3 regular-list rows are excluded.
        assertEquals(3, adapter.pinnedSectionItemCount())
    }

    @Test
    fun `pinnedSectionItemCount updates to zero after last favorite is removed via updateItems`() {
        val serverA = buildServer(city = "Paris", name = "srv-a").copy(id = 1)
        val initialItems = listOf(
            ServerListItem.SectionHeader(UiText.Res(R.string.favorites_section_title)),
            ServerListItem.ServerRow(serverA, isFavorite = true, isPinnedSection = true),
            ServerListItem.ServerRow(serverA, isFavorite = true)
        )
        val adapter = ServerPickerAdapter(initialItems, isDefaultV2Source = false, onClick = {}, onLongClick = { _, _, _ -> })
        assertEquals(2, adapter.pinnedSectionItemCount())

        adapter.updateItems(listOf(ServerListItem.ServerRow(serverA, isFavorite = false)))

        assertEquals(0, adapter.pinnedSectionItemCount())
    }

    // --- per-row favorite star indicator in the full server list ---

    @Test
    fun `row shows favorite star only when isFavorite is true`() {
        val context = RuntimeEnvironment.getApplication()
        val server = buildServer(city = "Seattle", name = "ServerName")
        val holder = ServerPickerAdapter.ViewHolder(buildItemView(context), isDefaultV2Source = false)

        holder.bind(server, isFavorite = true)
        assertEquals(
            View.VISIBLE,
            holder.itemView.findViewById<ImageView>(R.id.row_favorite_star).visibility
        )

        holder.bind(server, isFavorite = false)
        assertEquals(
            View.GONE,
            holder.itemView.findViewById<ImageView>(R.id.row_favorite_star).visibility
        )
    }

    @Test
    fun `favorite star toggles live as adapter items update`() {
        val context = RuntimeEnvironment.getApplication()
        val serverA = buildServer(city = "Paris", name = "srv-a").copy(id = 1)
        val adapter = ServerPickerAdapter(
            listOf(ServerListItem.ServerRow(serverA, isFavorite = false)),
            isDefaultV2Source = false,
            onClick = {},
            onLongClick = { _, _, _ -> }
        )
        val holder = ServerPickerAdapter.ViewHolder(buildItemView(context), isDefaultV2Source = false)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(
            View.GONE,
            holder.itemView.findViewById<ImageView>(R.id.row_favorite_star).visibility
        )

        adapter.updateItems(listOf(ServerListItem.ServerRow(serverA, isFavorite = true)))
        adapter.onBindViewHolder(holder, 0)
        assertEquals(
            View.VISIBLE,
            holder.itemView.findViewById<ImageView>(R.id.row_favorite_star).visibility
        )
    }

    @Test
    fun `favorite star shows on both the pinned row and its matching row in the full list`() {
        val context = RuntimeEnvironment.getApplication()
        val serverA = buildServer(city = "Paris", name = "srv-a").copy(id = 1)
        val items = listOf(
            ServerListItem.SectionHeader(UiText.Res(R.string.favorites_section_title)),
            ServerListItem.ServerRow(serverA, isFavorite = true, isPinnedSection = true),
            ServerListItem.ServerRow(serverA, isFavorite = true)
        )
        val adapter = ServerPickerAdapter(items, isDefaultV2Source = false, onClick = {}, onLongClick = { _, _, _ -> })

        val pinnedHolder = ServerPickerAdapter.ViewHolder(buildItemView(context), isDefaultV2Source = false)
        adapter.onBindViewHolder(pinnedHolder, 1)
        assertEquals(
            View.VISIBLE,
            pinnedHolder.itemView.findViewById<ImageView>(R.id.row_favorite_star).visibility
        )

        val regularHolder = ServerPickerAdapter.ViewHolder(buildItemView(context), isDefaultV2Source = false)
        adapter.onBindViewHolder(regularHolder, 2)
        assertEquals(
            View.VISIBLE,
            regularHolder.itemView.findViewById<ImageView>(R.id.row_favorite_star).visibility
        )
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

    @Test
    fun `favorite star has content description reflecting favorite state for accessibility`() {
        val context = RuntimeEnvironment.getApplication()
        val server = buildServer(city = "Seattle", name = "ServerName")
        val holder = ServerPickerAdapter.ViewHolder(buildItemView(context), isDefaultV2Source = false)
        val favoriteStar = holder.itemView.findViewById<ImageView>(R.id.row_favorite_star)

        // When favorited, content description should be set
        holder.bind(server, isFavorite = true)
        assertEquals(
            context.getString(R.string.favorites_section_title),
            favoriteStar?.contentDescription.toString()
        )

        // When not favorited, content description should be null (set to non-null first to test it clears)
        favoriteStar?.contentDescription = "sentinel_value"
        holder.bind(server, isFavorite = false)
        assertEquals(null, favoriteStar?.contentDescription)
    }

    // ==================== loading-footer row (lazy loading) ====================

    @Test
    fun `loading footer is the last view type when appended after server rows`() {
        val serverA = buildServer(city = "Paris", name = "srv-a").copy(id = 1)
        val items = listOf(
            ServerListItem.ServerRow(serverA, isFavorite = false),
            ServerListItem.LoadingFooter(FooterState.LOADING)
        )
        val adapter = ServerPickerAdapter(items, isDefaultV2Source = false, onClick = {}, onLongClick = { _, _, _ -> })

        assertEquals(2, adapter.itemCount)
        assertEquals(1, adapter.getItemViewType(0))
        assertEquals(2, adapter.getItemViewType(1))
    }

    @Test
    fun `loading footer shows progress and hides the error group when state is LOADING`() {
        val context = RuntimeEnvironment.getApplication()
        val items = listOf(ServerListItem.LoadingFooter(FooterState.LOADING))
        val adapter = ServerPickerAdapter(items, isDefaultV2Source = false, onClick = {}, onLongClick = { _, _, _ -> })
        val holder = ServerPickerAdapter.FooterViewHolder(buildFooterView(context), onRetry = {})

        adapter.onBindViewHolder(holder, 0)

        assertEquals(View.VISIBLE, holder.itemView.findViewById<View>(R.id.footer_progress).visibility)
        assertEquals(View.GONE, holder.itemView.findViewById<View>(R.id.footer_error_group).visibility)
    }

    @Test
    fun `loading footer shows error group with a working retry callback when state is ERROR`() {
        val context = RuntimeEnvironment.getApplication()
        val items = listOf(ServerListItem.LoadingFooter(FooterState.ERROR))
        var retried = false
        val adapter = ServerPickerAdapter(
            items,
            isDefaultV2Source = false,
            onClick = {},
            onLongClick = { _, _, _ -> },
            onRetryLoadMore = { retried = true }
        )
        val holder = ServerPickerAdapter.FooterViewHolder(buildFooterView(context)) { retried = true }

        adapter.onBindViewHolder(holder, 0)

        assertEquals(View.GONE, holder.itemView.findViewById<View>(R.id.footer_progress).visibility)
        assertEquals(View.VISIBLE, holder.itemView.findViewById<View>(R.id.footer_error_group).visibility)

        holder.itemView.findViewById<View>(R.id.footer_retry_button).performClick()
        assertTrue(retried)
    }

    // (code-review fix cycle, minor/deferred): the test above builds its own
    // FooterViewHolder by hand with its own separate `onRetry` lambda, so it does not exercise
    // the adapter's own onCreateViewHolder() wiring (`FooterViewHolder(v, onRetryLoadMore)`).
    // Closing that gap properly requires going through the adapter's real onCreateViewHolder(),
    // which inflates the real item_server_list_footer.xml -- but that layout pulls in
    // MaterialButton/MaterialCardView styles that fail to resolve under this module's Robolectric
    // legacy-resources setup (see buildFooterView's comment above, which stands the layout in by
    // hand for the same reason). Attempted and reverted: not closable without either an app-theme
    // fix to the test Robolectric config or a production-code seam, both out of scope for this
    // fix cycle's "minor, only if time permits" item.

    @Test
    fun `pinnedSectionItemCount ignores a trailing loading footer`() {
        val serverA = buildServer(city = "Paris", name = "srv-a").copy(id = 1)
        val items = listOf(
            ServerListItem.SectionHeader(UiText.Res(R.string.favorites_section_title)),
            ServerListItem.ServerRow(serverA, isFavorite = true, isPinnedSection = true),
            ServerListItem.ServerRow(serverA, isFavorite = true),
            ServerListItem.LoadingFooter(FooterState.LOADING)
        )
        val adapter = ServerPickerAdapter(items, isDefaultV2Source = false, onClick = {}, onLongClick = { _, _, _ -> })

        assertEquals(2, adapter.pinnedSectionItemCount())
    }
}
