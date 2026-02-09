package com.yahorzabotsin.openvpnclientgate.core.ui.dns

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.databinding.ActivityTemplateBinding
import com.yahorzabotsin.openvpnclientgate.core.databinding.ContentDnsBinding
import com.yahorzabotsin.openvpnclientgate.core.dns.DnsOption
import com.yahorzabotsin.openvpnclientgate.core.ui.common.decor.MarginItemDecoration
import com.yahorzabotsin.openvpnclientgate.core.ui.common.navigation.TemplatePage
import com.yahorzabotsin.openvpnclientgate.core.ui.common.utils.UiUtils
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class DnsActivity : AppCompatActivity() {
    private lateinit var templateBinding: ActivityTemplateBinding
    private lateinit var binding: ContentDnsBinding
    private var adapter: DnsOptionAdapter? = null
    private val viewModel: DnsViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        templateBinding = TemplatePage.create(this, R.string.menu_dns, null)
        binding = ContentDnsBinding.inflate(layoutInflater, templateBinding.contentContainer, true)
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
        if (adapter == null) {
            adapter = DnsOptionAdapter(state.items, state.selectedOption) { selected ->
                viewModel.onAction(DnsAction.SelectOption(selected))
            }
            binding.dnsRecyclerView.adapter = adapter
        } else {
            adapter?.updateSelectedOption(state.selectedOption)
        }
    }

    private fun handleEffect(effect: DnsEffect) {
        when (effect) {
            is DnsEffect.FocusSelected -> focusSelected(effect.option)
        }
    }

    private fun focusSelected(current: DnsOption) {
        val items = adapter?.items().orEmpty()
        val index = items.indexOfFirst { it.option == current }
        if (index < 0) return
        focusAdapterPositionWhenReady(index, attemptsLeft = 10)
    }

    private fun focusAdapterPositionWhenReady(position: Int, attemptsLeft: Int) {
        binding.dnsRecyclerView.post {
            val holder = binding.dnsRecyclerView.findViewHolderForAdapterPosition(position)
            if (holder != null) {
                holder.itemView.requestFocus()
            } else if (attemptsLeft > 0) {
                focusAdapterPositionWhenReady(position, attemptsLeft - 1)
            }
        }
    }
}


