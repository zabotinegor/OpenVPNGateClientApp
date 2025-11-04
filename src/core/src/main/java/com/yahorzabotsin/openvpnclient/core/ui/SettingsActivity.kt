package com.yahorzabotsin.openvpnclient.core.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.databinding.ActivityTemplateBinding
import com.yahorzabotsin.openvpnclient.core.databinding.ContentSettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var templateBinding: ActivityTemplateBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        templateBinding = ActivityTemplateBinding.inflate(layoutInflater)
        setContentView(templateBinding.root)

        ContentSettingsBinding.inflate(layoutInflater, templateBinding.contentContainer, true)
        TemplatePage.setupHeader(this, templateBinding, R.string.menu_settings, null)
    }
}

