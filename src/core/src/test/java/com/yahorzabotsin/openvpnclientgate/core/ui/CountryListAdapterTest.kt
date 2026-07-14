package com.yahorzabotsin.openvpnclientgate.core.ui

import android.view.View
import android.widget.TextView
import android.widget.ImageView
import android.widget.FrameLayout
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.UiText
import com.yahorzabotsin.openvpnclientgate.core.ui.serverlist.CountryListAdapter
import com.yahorzabotsin.openvpnclientgate.core.ui.serverlist.CountryListItem
import com.yahorzabotsin.openvpnclientgate.core.ui.serverlist.CountryWithServers
import org.junit.Assert.assertEquals
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
        container.addView(ImageView(context).apply { id = R.id.row_favorite_star })
        return container
    }

    private fun buildHeaderView(context: android.content.Context): FrameLayout {
        val container = FrameLayout(context)
        container.addView(TextView(context).apply { id = R.id.section_header_title })
        container.addView(ImageView(context).apply { id = R.id.section_header_icon })
        return container
    }

    // --- SUB-02: sealed list items (pinned favorites section + long-press) ---

    @Test
    fun `renders section header then country rows with correct view types`() {
        val items = listOf(
            CountryListItem.SectionHeader(UiText.Res(R.string.favorites_section_title)),
            CountryListItem.CountryRow(CountryWithServers(Country("United States", "US"), 5), isFavorite = true),
            CountryListItem.CountryRow(CountryWithServers(Country("Canada", "CA"), 3), isFavorite = false)
        )
        val adapter = CountryListAdapter(items, onClick = {}, onLongClick = { _, _, _ -> })

        assertEquals(3, adapter.itemCount)
        assertEquals(0, adapter.getItemViewType(0))
        assertEquals(1, adapter.getItemViewType(1))
        assertEquals(1, adapter.getItemViewType(2))
    }

    @Test
    fun `short tap invokes onClick with the row's country`() {
        val context = RuntimeEnvironment.getApplication()
        val items = listOf(
            CountryListItem.CountryRow(CountryWithServers(Country("Canada", "CA"), 3), isFavorite = false)
        )
        var clicked: Country? = null
        val adapter = CountryListAdapter(items, onClick = { clicked = it }, onLongClick = { _, _, _ -> })
        val holder = CountryListAdapter.ViewHolder(buildItemView(context))
        adapter.onBindViewHolder(holder, 0)

        holder.itemView.performClick()

        assertEquals(Country("Canada", "CA"), clicked)
    }

    @Test
    fun `long press invokes onLongClick with country and current favorite state`() {
        val context = RuntimeEnvironment.getApplication()
        val items = listOf(
            CountryListItem.CountryRow(CountryWithServers(Country("Canada", "CA"), 3), isFavorite = true)
        )
        var longPressedCountry: Country? = null
        var longPressedIsFavorite: Boolean? = null
        val adapter = CountryListAdapter(
            items,
            onClick = {},
            onLongClick = { _, country, isFavorite ->
                longPressedCountry = country
                longPressedIsFavorite = isFavorite
            }
        )
        val holder = CountryListAdapter.ViewHolder(buildItemView(context))
        adapter.onBindViewHolder(holder, 0)

        val handled = holder.itemView.performLongClick()

        assertTrue(handled)
        assertEquals(Country("Canada", "CA"), longPressedCountry)
        assertEquals(true, longPressedIsFavorite)
    }

    @Test
    fun `section header binds title text`() {
        val context = RuntimeEnvironment.getApplication()
        val items = listOf(CountryListItem.SectionHeader(UiText.Res(R.string.favorites_section_title)))
        val adapter = CountryListAdapter(items, onClick = {}, onLongClick = { _, _, _ -> })
        val holder = CountryListAdapter.HeaderViewHolder(buildHeaderView(context))
        adapter.onBindViewHolder(holder, 0)

        val titleView = holder.itemView.findViewById<TextView>(R.id.section_header_title)
        assertEquals(context.getString(R.string.favorites_section_title), titleView.text.toString())
    }

    // --- SUB-09: star icon shown only on the pinned Favorites header ---

    @Test
    fun `section header shows star icon only when showFavoriteIcon is true`() {
        val context = RuntimeEnvironment.getApplication()
        val favoritesHeader = listOf(
            CountryListItem.SectionHeader(UiText.Res(R.string.favorites_section_title), showFavoriteIcon = true)
        )
        val adapter = CountryListAdapter(favoritesHeader, onClick = {}, onLongClick = { _, _, _ -> })
        val holder = CountryListAdapter.HeaderViewHolder(buildHeaderView(context))
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.itemView.findViewById<ImageView>(R.id.section_header_icon).visibility)

        val allCountriesHeader = listOf(
            CountryListItem.SectionHeader(UiText.Res(R.string.all_countries_section_title))
        )
        val adapter2 = CountryListAdapter(allCountriesHeader, onClick = {}, onLongClick = { _, _, _ -> })
        val holder2 = CountryListAdapter.HeaderViewHolder(buildHeaderView(context))
        adapter2.onBindViewHolder(holder2, 0)
        assertEquals(View.GONE, holder2.itemView.findViewById<ImageView>(R.id.section_header_icon).visibility)
    }

    // --- SUB-06: pinned section frame boundary (isPinnedSection / pinnedSectionItemCount) ---

    @Test
    fun `pinnedSectionItemCount is zero when favorites section is hidden`() {
        val items = listOf(
            CountryListItem.CountryRow(CountryWithServers(Country("Canada", "CA"), 3), isFavorite = false)
        )
        val adapter = CountryListAdapter(items, onClick = {}, onLongClick = { _, _, _ -> })

        assertEquals(0, adapter.pinnedSectionItemCount())
    }

    @Test
    fun `pinnedSectionItemCount counts header plus pinned rows only, excluding regular list rows`() {
        val items = listOf(
            CountryListItem.SectionHeader(UiText.Res(R.string.favorites_section_title)),
            CountryListItem.CountryRow(CountryWithServers(Country("United States", "US"), 5), isFavorite = true, isPinnedSection = true),
            CountryListItem.CountryRow(CountryWithServers(Country("Zimbabwe", "ZW"), 2), isFavorite = true, isPinnedSection = true),
            // Regular (unheaded) list below: includes the same favorite again at its
            // alphabetical position, but isPinnedSection = false so it must not extend the frame.
            CountryListItem.CountryRow(CountryWithServers(Country("Canada", "CA"), 3), isFavorite = false),
            CountryListItem.CountryRow(CountryWithServers(Country("United States", "US"), 5), isFavorite = true),
            CountryListItem.CountryRow(CountryWithServers(Country("Zimbabwe", "ZW"), 2), isFavorite = true)
        )
        val adapter = CountryListAdapter(items, onClick = {}, onLongClick = { _, _, _ -> })

        // header (1) + 2 pinned favorite rows = 3; the 3 regular-list rows are excluded.
        assertEquals(3, adapter.pinnedSectionItemCount())
    }

    @Test
    fun `pinnedSectionItemCount updates to zero after last favorite is removed via updateItems`() {
        val initialItems = listOf(
            CountryListItem.SectionHeader(UiText.Res(R.string.favorites_section_title)),
            CountryListItem.CountryRow(CountryWithServers(Country("Canada", "CA"), 3), isFavorite = true, isPinnedSection = true),
            CountryListItem.CountryRow(CountryWithServers(Country("Canada", "CA"), 3), isFavorite = true)
        )
        val adapter = CountryListAdapter(initialItems, onClick = {}, onLongClick = { _, _, _ -> })
        assertEquals(2, adapter.pinnedSectionItemCount())

        adapter.updateItems(
            listOf(CountryListItem.CountryRow(CountryWithServers(Country("Canada", "CA"), 3), isFavorite = false))
        )

        assertEquals(0, adapter.pinnedSectionItemCount())
    }

    // --- SUB-09 AC8: per-row favorite star indicator in the full country list ---

    @Test
    fun `row shows favorite star only when isFavorite is true`() {
        val context = RuntimeEnvironment.getApplication()
        val country = Country(name = "United States", code = "US")
        val holder = CountryListAdapter.ViewHolder(buildItemView(context))

        holder.bind(CountryWithServers(country, serverCount = 1), isFavorite = true)
        assertEquals(
            View.VISIBLE,
            holder.itemView.findViewById<ImageView>(R.id.row_favorite_star).visibility
        )

        holder.bind(CountryWithServers(country, serverCount = 1), isFavorite = false)
        assertEquals(
            View.GONE,
            holder.itemView.findViewById<ImageView>(R.id.row_favorite_star).visibility
        )
    }

    @Test
    fun `favorite star toggles live as adapter items update`() {
        val context = RuntimeEnvironment.getApplication()
        val country = CountryWithServers(Country("Canada", "CA"), 3)
        val adapter = CountryListAdapter(
            listOf(CountryListItem.CountryRow(country, isFavorite = false)),
            onClick = {},
            onLongClick = { _, _, _ -> }
        )
        val holder = CountryListAdapter.ViewHolder(buildItemView(context))
        adapter.onBindViewHolder(holder, 0)
        assertEquals(
            View.GONE,
            holder.itemView.findViewById<ImageView>(R.id.row_favorite_star).visibility
        )

        adapter.updateItems(listOf(CountryListItem.CountryRow(country, isFavorite = true)))
        adapter.onBindViewHolder(holder, 0)
        assertEquals(
            View.VISIBLE,
            holder.itemView.findViewById<ImageView>(R.id.row_favorite_star).visibility
        )
    }

    @Test
    fun `favorite star shows on both the pinned row and its matching row in the full list`() {
        val context = RuntimeEnvironment.getApplication()
        val country = CountryWithServers(Country("Zimbabwe", "ZW"), 2)
        val items = listOf(
            CountryListItem.SectionHeader(UiText.Res(R.string.favorites_section_title)),
            CountryListItem.CountryRow(country, isFavorite = true, isPinnedSection = true),
            CountryListItem.CountryRow(country, isFavorite = true)
        )
        val adapter = CountryListAdapter(items, onClick = {}, onLongClick = { _, _, _ -> })

        val pinnedHolder = CountryListAdapter.ViewHolder(buildItemView(context))
        adapter.onBindViewHolder(pinnedHolder, 1)
        assertEquals(
            View.VISIBLE,
            pinnedHolder.itemView.findViewById<ImageView>(R.id.row_favorite_star).visibility
        )

        val regularHolder = CountryListAdapter.ViewHolder(buildItemView(context))
        adapter.onBindViewHolder(regularHolder, 2)
        assertEquals(
            View.VISIBLE,
            regularHolder.itemView.findViewById<ImageView>(R.id.row_favorite_star).visibility
        )
    }

    @Test
    fun `Finding 1 - updateItems changes items without recreating adapter`() {
        val context = RuntimeEnvironment.getApplication()
        val initialItems = listOf(
            CountryListItem.CountryRow(CountryWithServers(Country("Canada", "CA"), 3), isFavorite = false)
        )
        val adapter = CountryListAdapter(initialItems, onClick = {}, onLongClick = { _, _, _ -> })

        assertEquals(1, adapter.itemCount)

        val newItems = listOf(
            CountryListItem.SectionHeader(UiText.Res(R.string.favorites_section_title)),
            CountryListItem.CountryRow(CountryWithServers(Country("Canada", "CA"), 3), isFavorite = true),
            CountryListItem.CountryRow(CountryWithServers(Country("United States", "US"), 5), isFavorite = false)
        )
        adapter.updateItems(newItems)

        // Item count should reflect the update without recreating the adapter instance
        assertEquals(3, adapter.itemCount)
        assertEquals(0, adapter.getItemViewType(0))  // SectionHeader
        assertEquals(1, adapter.getItemViewType(1))  // CountryRow
        assertEquals(1, adapter.getItemViewType(2))  // CountryRow
    }
}
