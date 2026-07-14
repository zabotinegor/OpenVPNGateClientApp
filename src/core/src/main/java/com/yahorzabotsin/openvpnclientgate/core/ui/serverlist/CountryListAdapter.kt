package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.countryFlagEmoji
import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.UiText
import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.resolve

data class CountryWithServers(
    val country: Country,
    val serverCount: Int
)

/**
 * Flattened list item used by [CountryListAdapter] so a single RecyclerView can render both
 * the pinned favorites section and the regular alphabetical country list.
 */
sealed interface CountryListItem {
    /**
     * @param showFavoriteIcon true only for the pinned "Favorites" section header (SUB-09);
     * the "All countries" header shown below the pinned block does not get the star icon.
     */
    data class SectionHeader(val title: UiText, val showFavoriteIcon: Boolean = false) : CountryListItem

    /**
     * @param isPinnedSection true only for the row instance rendered inside the pinned
     * "Favorites" block at the top of the list (immediately after [SectionHeader]). A
     * favorited country also appears a second time at its normal alphabetical position in
     * the regular list below with [isPinnedSection] = false (see [CountryListAdapter] doc).
     * Used purely for visual framing (SUB-06); does not affect click/long-click behavior.
     */
    data class CountryRow(
        val countryWithServers: CountryWithServers,
        val isFavorite: Boolean,
        val isPinnedSection: Boolean = false
    ) : CountryListItem
}

class CountryListAdapter(
    private var items: List<CountryListItem>,
    private val onClick: (Country) -> Unit,
    private val onLongClick: (view: View, country: Country, isFavorite: Boolean) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    fun updateItems(newItems: List<CountryListItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is CountryListItem.SectionHeader -> VIEW_TYPE_HEADER
        is CountryListItem.CountryRow -> VIEW_TYPE_COUNTRY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val v = inflater.inflate(R.layout.item_country_section_header, parent, false)
                HeaderViewHolder(v)
            }
            else -> {
                val v = inflater.inflate(R.layout.item_country_row, parent, false)
                ViewHolder(v)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is CountryListItem.SectionHeader -> (holder as HeaderViewHolder).bind(item)
            is CountryListItem.CountryRow -> {
                val rowHolder = holder as ViewHolder
                rowHolder.bind(item.countryWithServers, item.isFavorite)
                rowHolder.itemView.setOnClickListener { onClick(item.countryWithServers.country) }
                rowHolder.itemView.setOnLongClickListener {
                    onLongClick(rowHolder.itemView, item.countryWithServers.country, item.isFavorite)
                    true
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    /**
     * Number of leading items (the [CountryListItem.SectionHeader] plus its pinned
     * [CountryListItem.CountryRow] entries) that make up the pinned "Favorites" block, or 0
     * when the section is hidden (no favorites). Used by [com.yahorzabotsin.openvpnclientgate.core.ui.common.decor.FavoritesSectionCardDecoration]
     * to draw a filled card behind exactly that block (SUB-09; SUB-06 originally). The second
     * "All countries" header inserted below the pinned block (SUB-09) is a [CountryListItem.SectionHeader],
     * not a pinned [CountryListItem.CountryRow], so it naturally stops this count.
     */
    fun pinnedSectionItemCount(): Int {
        if (items.isEmpty() || items[0] !is CountryListItem.SectionHeader) return 0
        var count = 1
        for (i in 1 until items.size) {
            val item = items[i]
            if (item is CountryListItem.CountryRow && item.isPinnedSection) {
                count++
            } else {
                break
            }
        }
        // Return 0 if header has no pinned rows following it (only header alone doesn't count)
        return if (count > 1) count else 0
    }

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.section_header_title)
        private val icon: View? = itemView.findViewById(R.id.section_header_icon)
        fun bind(header: CountryListItem.SectionHeader) {
            title.text = title.context.resolve(header.title)
            icon?.visibility = if (header.showFavoriteIcon) View.VISIBLE else View.GONE
        }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.country_name)
        private val flagView: TextView = itemView.findViewById(R.id.country_flag)
        private val serverCountView: TextView = itemView.findViewById(R.id.server_count)
        private val chevronIcon: ImageView = itemView.findViewById(R.id.chevron_icon)
        private val favoriteStar: ImageView? = itemView.findViewById(R.id.row_favorite_star)
        fun bind(country: CountryWithServers, isFavorite: Boolean = false) {
            name.text = country.country.name
            val flag = countryFlagEmoji(country.country.code)
            if (!flag.isNullOrEmpty()) {
                flagView.text = flag
                flagView.visibility = View.VISIBLE
            } else {
                flagView.text = ""
                flagView.visibility = View.GONE
            }
            serverCountView.text = itemView.context.resources.getQuantityString(
                R.plurals.server_count,
                country.serverCount,
                country.serverCount
            )
            chevronIcon.visibility = View.VISIBLE
            // SUB-09 AC8: per-row favorite indicator, shown on this row both inside the pinned
            // Favorites card and again at its normal position in the full list below.
            favoriteStar?.visibility = if (isFavorite) View.VISIBLE else View.GONE
        }
    }

    private companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_COUNTRY = 1
    }
}
