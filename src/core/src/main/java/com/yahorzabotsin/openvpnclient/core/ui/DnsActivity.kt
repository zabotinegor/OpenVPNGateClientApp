package com.yahorzabotsin.openvpnclient.core.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.databinding.ContentDnsBinding

class DnsActivity : BaseTemplateActivity(R.string.menu_dns) {
    override fun inflateContent(inflater: LayoutInflater, container: ViewGroup) {
        ContentDnsBinding.inflate(inflater, container, true)
    }
}
