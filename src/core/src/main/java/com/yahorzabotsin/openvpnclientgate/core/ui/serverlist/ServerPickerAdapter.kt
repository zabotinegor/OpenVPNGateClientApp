package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.countryFlagEmoji
import com.yahorzabotsin.openvpnclientgate.core.servers.SignalStrength
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.ServerDisplayFormatter
import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.UiText
import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.resolve

/**
 * Flattened list item used by [ServerPickerAdapter] so a single RecyclerView can render both
 * the pinned favorites section and the regular server list.
 */
sealed interface ServerListItem {
    /**
     * @param showFavoriteIcon true only for the pinned "Favorites" section header (SUB-09);
     * the "All servers" header shown below the pinned block does not get the star icon.
     */
    data class SectionHeader(val title: UiText, val showFavoriteIcon: Boolean = false) : ServerListItem

    /**
     * @param isPinnedSection true only for the row instance rendered inside the pinned
     * "Favorites" block at the top of the list (immediately after [SectionHeader]). A
     * favorited server also appears a second time at its normal position in the regular
     * list below with [isPinnedSection] = false (see [ServerPickerAdapter] doc). Used
     * purely for visual framing (SUB-06); does not affect click/long-click behavior.
     */
    data class ServerRow(
        val server: Server,
        val isFavorite: Boolean,
        val isPinnedSection: Boolean = false
    ) : ServerListItem
}

class ServerPickerAdapter(
    private var items: List<ServerListItem>,
    private val isDefaultV2Source: Boolean,
    private val onClick: (Server) -> Unit,
    private val onLongClick: (view: View, server: Server, isFavorite: Boolean) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    fun updateItems(newItems: List<ServerListItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is ServerListItem.SectionHeader -> VIEW_TYPE_HEADER
        is ServerListItem.ServerRow -> VIEW_TYPE_SERVER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val v = inflater.inflate(R.layout.item_country_section_header, parent, false)
                HeaderViewHolder(v)
            }
            else -> {
                val v = inflater.inflate(R.layout.item_server_row, parent, false)
                ViewHolder(v, isDefaultV2Source)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ServerListItem.SectionHeader -> (holder as HeaderViewHolder).bind(item)
            is ServerListItem.ServerRow -> {
                val rowHolder = holder as ViewHolder
                rowHolder.bind(item.server)
                rowHolder.itemView.setOnClickListener { onClick(item.server) }
                rowHolder.itemView.setOnLongClickListener {
                    onLongClick(rowHolder.itemView, item.server, item.isFavorite)
                    true
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    /**
     * Number of leading items (the [ServerListItem.SectionHeader] plus its pinned
     * [ServerListItem.ServerRow] entries) that make up the pinned "Favorites" block, or 0
     * when the section is hidden (no favorites). Used by [com.yahorzabotsin.openvpnclientgate.core.ui.common.decor.FavoritesSectionCardDecoration]
     * to draw a filled card behind exactly that block (SUB-09; SUB-06 originally). The second
     * "All servers" header inserted below the pinned block (SUB-09) is a [ServerListItem.SectionHeader],
     * not a pinned [ServerListItem.ServerRow], so it naturally stops this count.
     */
    fun pinnedSectionItemCount(): Int {
        if (items.isEmpty() || items[0] !is ServerListItem.SectionHeader) return 0
        var count = 1
        for (i in 1 until items.size) {
            val item = items[i]
            if (item is ServerListItem.ServerRow && item.isPinnedSection) {
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
        fun bind(header: ServerListItem.SectionHeader) {
            title.text = title.context.resolve(header.title)
            icon?.visibility = if (header.showFavoriteIcon) View.VISIBLE else View.GONE
        }
    }

    class ViewHolder(
        itemView: View,
        private val isDefaultV2Source: Boolean
    ) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.server_title)
        private val subtitle: TextView = itemView.findViewById(R.id.server_subtitle)
        private val chevron: ImageView = itemView.findViewById(R.id.chevron_icon)
        private val flag: TextView = itemView.findViewById(R.id.server_flag)
        private val pingView: TextView = itemView.findViewById(R.id.server_ping)
        private val signalView: ImageView = itemView.findViewById(R.id.server_signal)
        fun bind(server: Server) {
            if (isDefaultV2Source) {
                val city = server.city.trim()
                val utc = ServerDisplayFormatter.formatUtc(server.utc)
                when {
                    city.isNotEmpty() && utc != null -> {
                        title.text = city
                        subtitle.text = utc
                        subtitle.visibility = View.VISIBLE
                    }

                    city.isNotEmpty() -> {
                        title.text = city
                        subtitle.text = ""
                        subtitle.visibility = View.GONE
                    }

                    else -> {
                        title.text = server.ip
                        subtitle.text = ""
                        subtitle.visibility = View.GONE
                    }
                }
            } else {
                title.text = server.city.takeIf { it.isNotBlank() } ?: server.name
                subtitle.text = server.ip
                subtitle.visibility = View.VISIBLE
            }

            chevron.visibility = View.VISIBLE
            val flagEmoji = countryFlagEmoji(server.country.code)
            flag.text = flagEmoji ?: ""
            flag.visibility = if (flagEmoji.isNullOrEmpty()) View.GONE else View.VISIBLE
            pingView.text = itemView.context.getString(R.string.ping_ms_format, server.ping)
            signalView.setImageResource(
                when (server.signalStrength) {
                    SignalStrength.STRONG -> R.drawable.signal_strong
                    SignalStrength.MEDIUM -> R.drawable.signal_medium
                    SignalStrength.WEAK -> R.drawable.signal_weak
                }
            )
        }
    }

    private companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_SERVER = 1
    }
}
