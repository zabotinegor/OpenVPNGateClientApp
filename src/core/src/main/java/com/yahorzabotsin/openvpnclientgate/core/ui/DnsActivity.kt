package com.yahorzabotsin.openvpnclientgate.core.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.databinding.ContentDnsBinding
import com.yahorzabotsin.openvpnclientgate.core.settings.DnsOption
import com.yahorzabotsin.openvpnclientgate.core.ui.dns.DnsAction
import com.yahorzabotsin.openvpnclientgate.core.ui.dns.DnsEffect
import com.yahorzabotsin.openvpnclientgate.core.ui.dns.DnsUiState
import com.yahorzabotsin.openvpnclientgate.core.ui.dns.DnsViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class DnsActivity : BaseTemplateActivity(R.string.menu_dns) {
    private lateinit var binding: ContentDnsBinding
    private var adapter: DnsOptionAdapter? = null
    private val viewModel: DnsViewModel by viewModel()
    private var currentItems: List<DnsOptionItem> = emptyList()
    private var pendingFocusOption: DnsOption? = null

    override fun inflateContent(inflater: LayoutInflater, container: ViewGroup) {
        binding = ContentDnsBinding.inflate(inflater, container, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.dnsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.dnsRecyclerView.addItemDecoration(MarginItemDecoration(UiUtils.dpToPx(8, resources)))
        observeViewModel()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { render(it) }
                }
                launch {
                    viewModel.effects.collect { handleEffect(it) }
                }
            }
        }
    }

    private fun render(state: DnsUiState) {
        currentItems = state.items
        if (adapter == null) {
            adapter = DnsOptionAdapter(state.items, state.selectedOption) { selected ->
                viewModel.onAction(DnsAction.SelectOption(selected))
            }
            binding.dnsRecyclerView.adapter = adapter
        } else {
            adapter?.updateSelectedOption(state.selectedOption)
        }

        if (pendingFocusOption != null && adapter != null) {
            val option = pendingFocusOption
            pendingFocusOption = null
            if (option != null) {
                focusSelected(currentItems, option)
            }
        }
    }

    private fun handleEffect(effect: DnsEffect) {
        when (effect) {
            is DnsEffect.FocusSelected -> {
                if (currentItems.isEmpty() || adapter == null) {
                    pendingFocusOption = effect.option
                } else {
                    focusSelected(currentItems, effect.option)
                }
            }
        }
    }

    private fun focusSelected(items: List<DnsOptionItem>, current: DnsOption) {
        val index = items.indexOfFirst { it.option == current }
        if (index < 0) return
        binding.dnsRecyclerView.post {
            binding.dnsRecyclerView.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
        }
    }
}

