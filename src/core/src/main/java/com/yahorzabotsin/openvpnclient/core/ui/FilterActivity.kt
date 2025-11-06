package com.yahorzabotsin.openvpnclient.core.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.databinding.ContentFilterBinding

class FilterActivity : BaseTemplateActivity(R.string.menu_filter) {
    override fun inflateContent(inflater: LayoutInflater, container: ViewGroup) {
        ContentFilterBinding.inflate(inflater, container, true)
    }
}
