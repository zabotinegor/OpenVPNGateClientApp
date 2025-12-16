package com.yahorzabotsin.openvpnclient.core.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.databinding.ItemAppFilterBinding
import com.yahorzabotsin.openvpnclient.core.databinding.ItemFilterInfoBinding
import com.yahorzabotsin.openvpnclient.core.databinding.ItemFilterSelectAllBinding
import com.yahorzabotsin.openvpnclient.core.filter.AppFilterEntry

class FilterListAdapter(
    private val onSelectAllToggle: (Boolean) -> Unit,
    private val onAppToggle: (packageName: String, isEnabled: Boolean) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Item {
        data object Info : Item()
        data class SelectAll(val isChecked: Boolean, val isEnabled: Boolean) : Item()
        data class App(val entry: AppFilterEntry, val isEnabled: Boolean) : Item()
    }

    private var items: List<Item> = emptyList()

    fun submitList(newItems: List<Item>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is Item.Info -> VIEW_INFO
        is Item.SelectAll -> VIEW_SELECT_ALL
        is Item.App -> VIEW_APP
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_INFO -> InfoViewHolder(ItemFilterInfoBinding.inflate(inflater, parent, false))
            VIEW_SELECT_ALL -> SelectAllViewHolder(ItemFilterSelectAllBinding.inflate(inflater, parent, false))
            else -> AppViewHolder(ItemAppFilterBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.Info -> (holder as InfoViewHolder).bind()
            is Item.SelectAll -> (holder as SelectAllViewHolder).bind(item)
            is Item.App -> (holder as AppViewHolder).bind(item)
        }
    }

    override fun getItemId(position: Int): Long = when (val item = items[position]) {
        Item.Info -> "info".hashCode().toLong()
        is Item.SelectAll -> "select_all".hashCode().toLong()
        is Item.App -> item.entry.packageName.hashCode().toLong()
    }

    init {
        setHasStableIds(true)
    }

    private class InfoViewHolder(
        private val binding: ItemFilterInfoBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            // Static text from resources; nothing dynamic needed.
        }
    }

    private inner class SelectAllViewHolder(
        private val binding: ItemFilterSelectAllBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Item.SelectAll) {
            binding.selectAllSwitch.setOnCheckedChangeListener(null)
            binding.selectAllSwitch.isChecked = item.isChecked
            binding.selectAllSwitch.isEnabled = item.isEnabled
            binding.selectAllLabel.isEnabled = item.isEnabled

            val alpha = if (item.isEnabled) 1f else 0.5f
            binding.selectAllLabel.alpha = alpha
            binding.root.alpha = alpha

            binding.selectAllSwitch.setOnCheckedChangeListener { _, isChecked ->
                onSelectAllToggle(isChecked)
            }
            binding.root.setOnClickListener {
                if (binding.selectAllSwitch.isEnabled) binding.selectAllSwitch.performClick()
            }
        }
    }

    private inner class AppViewHolder(
        private val binding: ItemAppFilterBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Item.App) {
            binding.appName.text = item.entry.label
            binding.appSwitch.setOnCheckedChangeListener(null)
            binding.appSwitch.isChecked = item.isEnabled
            binding.appSwitch.contentDescription = binding.root.context.getString(
                R.string.filter_switch_content_description,
                item.entry.label
            )
            val icon = item.entry.icon
                ?: ContextCompat.getDrawable(binding.root.context, R.drawable.ic_icon_system)
            binding.appIcon.setImageDrawable(icon)

            binding.appSwitch.setOnCheckedChangeListener { _, isChecked ->
                onAppToggle(item.entry.packageName, isChecked)
            }
            binding.root.setOnClickListener {
                binding.appSwitch.performClick()
            }
        }
    }

    private companion object {
        const val VIEW_INFO = 0
        const val VIEW_SELECT_ALL = 1
        const val VIEW_APP = 2
    }
}
