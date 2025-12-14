package com.yahorzabotsin.openvpnclient.core.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.view.isVisible
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.databinding.ContentSettingsBinding

class SettingsActivity : BaseTemplateActivity(R.string.menu_settings) {
    private lateinit var binding: ContentSettingsBinding

    override fun inflateContent(inflater: LayoutInflater, container: ViewGroup) {
        binding = ContentSettingsBinding.inflate(inflater, container, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupCollapsibles()
        setupRadioGroups()
        updateSummaries()
    }

    private fun setupCollapsibles() {
        setupCollapsibleSection(
            header = binding.languageHeader,
            content = binding.languageRadioGroup,
            chevron = binding.languageChevron
        )
        setupCollapsibleSection(
            header = binding.themeHeader,
            content = binding.themeRadioGroup,
            chevron = binding.themeChevron
        )
        setupCollapsibleSection(
            header = binding.serverHeader,
            content = binding.serverContent,
            chevron = binding.serverChevron
        )
    }

    private fun setupRadioGroups() {
        binding.languageRadioGroup.setOnCheckedChangeListener { _, _ ->
            updateSummaries()
        }
        binding.themeRadioGroup.setOnCheckedChangeListener { _, _ ->
            updateSummaries()
        }
        binding.serverRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val isCustom = checkedId == binding.serverCustom.id
            binding.customServerInputLayout.visibility = if (isCustom) View.VISIBLE else View.GONE
            updateSummaries()
        }
    }

    private fun updateSummaries() {
        binding.languageSummary.text = selectedRadioText(binding.languageRadioGroup)
        binding.themeSummary.text = selectedRadioText(binding.themeRadioGroup)
        binding.serverSummary.text = selectedRadioText(binding.serverRadioGroup)
    }

    private fun setupCollapsibleSection(header: View, content: View, chevron: ImageView) {
        setExpanded(content, chevron, false)
        header.setOnClickListener {
            val expanded = !content.isVisible
            setExpanded(content, chevron, expanded)
        }
    }

    private fun setExpanded(content: View, chevron: ImageView, expanded: Boolean) {
        content.isVisible = expanded
        chevron.animate().rotation(if (expanded) 90f else 0f).setDuration(150).start()
    }

    private fun selectedRadioText(group: RadioGroup): String {
        val selectedId = group.checkedRadioButtonId
        val radio = group.findViewById<RadioButton>(selectedId)
        return radio?.text?.toString().orEmpty()
    }
}
