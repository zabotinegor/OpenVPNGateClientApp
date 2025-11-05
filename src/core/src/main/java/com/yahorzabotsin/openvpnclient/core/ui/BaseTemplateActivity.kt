package com.yahorzabotsin.openvpnclient.core.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.yahorzabotsin.openvpnclient.core.databinding.ActivityTemplateBinding

abstract class BaseTemplateActivity(
    @StringRes private val titleResId: Int
) : AppCompatActivity() {

    protected lateinit var templateBinding: ActivityTemplateBinding
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        templateBinding = ActivityTemplateBinding.inflate(layoutInflater)
        setContentView(templateBinding.root)

        inflateContent(layoutInflater, templateBinding.contentContainer)
        TemplatePage.setupHeader(this, templateBinding, titleResId, headerBackIntent())
    }

    protected open fun headerBackIntent(): Intent? = null

    protected abstract fun inflateContent(inflater: LayoutInflater, container: ViewGroup)
}
