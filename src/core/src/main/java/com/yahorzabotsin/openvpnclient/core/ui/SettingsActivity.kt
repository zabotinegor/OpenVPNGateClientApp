package com.yahorzabotsin.openvpnclient.core.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.databinding.ContentSettingsBinding

class SettingsActivity : BaseTemplateActivity(R.string.menu_settings) {
    override fun inflateContent(inflater: LayoutInflater, container: ViewGroup) {
        ContentSettingsBinding.inflate(inflater, container, true)
    }
}
