package com.yahorzabotsin.openvpnclient.core.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.util.Log
import androidx.recyclerview.widget.LinearLayoutManager
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.databinding.ContentDnsBinding
import com.yahorzabotsin.openvpnclient.core.settings.DnsOption
import com.yahorzabotsin.openvpnclient.core.settings.DnsOptions
import com.yahorzabotsin.openvpnclient.core.settings.UserSettingsStore

class DnsActivity : BaseTemplateActivity(R.string.menu_dns) {
    private companion object {
        private val TAG = com.yahorzabotsin.openvpnclient.core.logging.LogTags.APP + ':' + "DnsActivity"
    }

    private lateinit var binding: ContentDnsBinding
    private lateinit var adapter: DnsOptionAdapter
    private var currentOption: DnsOption = DnsOption.SERVER

    override fun inflateContent(inflater: LayoutInflater, container: ViewGroup) {
        binding = ContentDnsBinding.inflate(inflater, container, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val providerByOption = DnsOptions.providers.associateBy { it.option }
        val items = DnsOptions.providers.map { provider ->
            DnsOptionItem(provider.option, provider.label, null)
        }
        currentOption = UserSettingsStore.load(this).dnsOption
        Log.i(TAG, "DNS screen opened: providers=${items.size}, current=${currentOption.name}")
        adapter = DnsOptionAdapter(items, currentOption) { selected ->
            val old = currentOption
            currentOption = selected
            val label = providerByOption[selected]?.label ?: selected.name
            Log.i(TAG, "DNS selection changed: ${old.name} -> ${selected.name} (${label})")
            UserSettingsStore.saveDnsOption(this, selected)
        }
        binding.dnsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.dnsRecyclerView.addItemDecoration(MarginItemDecoration(UiUtils.dpToPx(8, resources)))
        binding.dnsRecyclerView.adapter = adapter
        focusSelected(items, currentOption)
    }

    private fun focusSelected(items: List<DnsOptionItem>, current: DnsOption) {
        val index = items.indexOfFirst { it.option == current }
        if (index < 0) return
        binding.dnsRecyclerView.post {
            binding.dnsRecyclerView.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
        }
    }
}
