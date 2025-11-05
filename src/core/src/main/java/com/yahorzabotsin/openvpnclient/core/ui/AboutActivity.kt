package com.yahorzabotsin.openvpnclient.core.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.databinding.ContentAboutBinding

class AboutActivity : BaseTemplateActivity(R.string.menu_about) {
    override fun inflateContent(inflater: LayoutInflater, container: ViewGroup) {
        ContentAboutBinding.inflate(inflater, container, true)
    }
}
