package com.yahorzabotsin.openvpnclientgate.core.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.databinding.ItemAppFilterBinding
import com.yahorzabotsin.openvpnclientgate.core.databinding.ItemFilterSelectAllBinding
import com.yahorzabotsin.openvpnclientgate.core.ui.filter.FilterUiItem

class FilterListAdapter(
    private val onSelectAllToggle: (Boolean) -> Unit,
    private val onAppToggle: (packageName: String, isEnabled: Boolean) -> Unit,
    private val onItemFocus: (Int) -> Unit = {}
) : ListAdapter<FilterUiItem, RecyclerView.ViewHolder>(DiffCallback()) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is FilterUiItem.SelectAll -> VIEW_SELECT_ALL
        is FilterUiItem.App -> VIEW_APP
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_SELECT_ALL -> SelectAllViewHolder(ItemFilterSelectAllBinding.inflate(inflater, parent, false))
            else -> AppViewHolder(ItemAppFilterBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is FilterUiItem.SelectAll -> (holder as SelectAllViewHolder).bind(item)
            is FilterUiItem.App -> (holder as AppViewHolder).bind(item)
        }
    }

    override fun getItemId(position: Int): Long = when (val item = getItem(position)) {
        is FilterUiItem.SelectAll -> "select_all".hashCode().toLong()
        is FilterUiItem.App -> item.packageName.hashCode().toLong()
    }

    init {
        setHasStableIds(true)
    }

    private class DiffCallback : DiffUtil.ItemCallback<FilterUiItem>() {
        override fun areItemsTheSame(oldItem: FilterUiItem, newItem: FilterUiItem): Boolean = when {
            oldItem is FilterUiItem.SelectAll && newItem is FilterUiItem.SelectAll -> true
            oldItem is FilterUiItem.App && newItem is FilterUiItem.App -> oldItem.packageName == newItem.packageName
            else -> false
        }

        override fun areContentsTheSame(oldItem: FilterUiItem, newItem: FilterUiItem): Boolean = oldItem == newItem
    }

    private inner class SelectAllViewHolder(
        private val binding: ItemFilterSelectAllBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.isFocusable = true
            binding.root.nextFocusUpId = com.yahorzabotsin.openvpnclientgate.core.R.id.back_button
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onItemFocus(pos)
                    }
                }
            }
        }

        fun bind(item: FilterUiItem.SelectAll) {
            binding.root.tag = "select_all"
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

        init {
            binding.root.isFocusable = true
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onItemFocus(pos)
                    }
                }
            }
        }

        fun bind(item: FilterUiItem.App) {
            binding.root.tag = "app:${item.packageName}"
            binding.appName.text = item.label
            binding.appSwitch.setOnCheckedChangeListener(null)
            binding.appSwitch.isChecked = item.isEnabled
            binding.appSwitch.contentDescription = binding.root.context.getString(
                R.string.filter_switch_content_description,
                item.label
            )
            val icon = loadIcon(item.packageName)
            binding.appIcon.setImageDrawable(icon)

            binding.appSwitch.setOnCheckedChangeListener { _, isChecked ->
                onAppToggle(item.packageName, isChecked)
            }
            binding.root.setOnClickListener {
                binding.appSwitch.performClick()
            }
        }

        private fun loadIcon(packageName: String): Drawable? {
            val context = binding.root.context
            val icon = try {
                context.packageManager.getApplicationIcon(packageName)
            } catch (_: Exception) {
                null
            }
            return icon ?: ContextCompat.getDrawable(context, R.drawable.ic_icon_system)
        }
    }

    private companion object {
        const val VIEW_SELECT_ALL = 0
        const val VIEW_APP = 1
    }
}

