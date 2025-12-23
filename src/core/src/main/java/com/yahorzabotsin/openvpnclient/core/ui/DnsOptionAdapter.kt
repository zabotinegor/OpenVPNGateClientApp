package com.yahorzabotsin.openvpnclient.core.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.settings.DnsOption

data class DnsOptionItem(
    val option: DnsOption,
    val title: String,
    val subtitle: String?
)

class DnsOptionAdapter(
    private val items: List<DnsOptionItem>,
    selectedOption: DnsOption,
    private val onSelected: (DnsOption) -> Unit
) : RecyclerView.Adapter<DnsOptionAdapter.ViewHolder>() {

    private val indexByOption: Map<DnsOption, Int> =
        items.mapIndexed { index, item -> item.option to index }.toMap()

    var selectedOption: DnsOption = selectedOption
        private set

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_dns_option, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, item.option == selectedOption)
        holder.itemView.setOnClickListener {
            if (item.option == selectedOption) return@setOnClickListener
            val old = selectedOption
            selectedOption = item.option
            indexByOption[old]?.let { notifyItemChanged(it) }
            notifyItemChanged(position)
            onSelected(item.option)
            holder.itemView.requestFocus()
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: MaterialCardView = itemView.findViewById(R.id.dns_option_card)
        private val title: TextView = itemView.findViewById(R.id.dns_option_title)
        private val subtitle: TextView = itemView.findViewById(R.id.dns_option_subtitle)
        private val selectedStrokeWidthPx = UiUtils.dpToPx(2, itemView.resources)
        private val selectedStrokeColor = MaterialColors.getColor(card, androidx.appcompat.R.attr.colorPrimary)

        fun bind(item: DnsOptionItem, isSelected: Boolean) {
            title.text = item.title
            if (item.subtitle.isNullOrBlank()) {
                subtitle.visibility = View.GONE
            } else {
                subtitle.visibility = View.VISIBLE
                subtitle.text = item.subtitle
            }
            card.strokeWidth = if (isSelected) selectedStrokeWidthPx else 0
            card.setStrokeColor(selectedStrokeColor)
        }
    }
}
