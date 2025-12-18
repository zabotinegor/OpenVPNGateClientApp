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
import androidx.core.widget.addTextChangedListener
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.databinding.ContentSettingsBinding
import com.yahorzabotsin.openvpnclient.core.settings.LanguageOption
import com.yahorzabotsin.openvpnclient.core.settings.ServerSource
import com.yahorzabotsin.openvpnclient.core.settings.ThemeOption
import com.yahorzabotsin.openvpnclient.core.settings.UserSettings
import com.yahorzabotsin.openvpnclient.core.settings.UserSettingsStore

class SettingsActivity : BaseTemplateActivity(R.string.menu_settings) {
    private lateinit var binding: ContentSettingsBinding
    private var isUpdatingUi = false
    private var currentCacheTtlMs: Long = UserSettingsStore.DEFAULT_CACHE_TTL_MS

    override fun inflateContent(inflater: LayoutInflater, container: ViewGroup) {
        binding = ContentSettingsBinding.inflate(inflater, container, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSettingsIntoUi(UserSettingsStore.load(this))
        setupCollapsibles()
        setupRadioGroups()
        setupCustomInputWatcher()
        setupCacheInputWatcher()
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
        setupCollapsibleSection(
            header = binding.autoSwitchHeader,
            content = binding.autoSwitchRadioGroup,
            chevron = binding.autoSwitchChevron
        )
        setupCollapsibleSection(
            header = binding.cacheHeader,
            content = binding.cacheInputLayout,
            chevron = binding.cacheChevron
        )
    }

    private fun setupRadioGroups() {
        binding.languageRadioGroup.setOnCheckedChangeListener { _, _ ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            UserSettingsStore.saveLanguage(this, when (binding.languageRadioGroup.checkedRadioButtonId) {
                binding.languageSystem.id -> LanguageOption.SYSTEM
                binding.languageEn.id -> LanguageOption.ENGLISH
                binding.languageRu.id -> LanguageOption.RUSSIAN
                binding.languagePl.id -> LanguageOption.POLISH
                else -> LanguageOption.SYSTEM
            })
            UserSettingsStore.applyThemeAndLocale(this)
            updateSummaries()
        }
        binding.themeRadioGroup.setOnCheckedChangeListener { _, _ ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            UserSettingsStore.saveTheme(this, when (binding.themeRadioGroup.checkedRadioButtonId) {
                binding.themeSystem.id -> ThemeOption.SYSTEM
                binding.themeLight.id -> ThemeOption.LIGHT
                binding.themeDark.id -> ThemeOption.DARK
                else -> ThemeOption.SYSTEM
            })
            UserSettingsStore.applyThemeAndLocale(this)
            updateSummaries()
        }
        binding.serverRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            val isCustom = checkedId == binding.serverCustom.id
            binding.customServerInputLayout.visibility = if (isCustom) View.VISIBLE else View.GONE
            UserSettingsStore.saveServerSource(this, when (checkedId) {
                binding.serverDefault.id -> ServerSource.DEFAULT
                binding.serverVpngate.id -> ServerSource.VPNGATE
                binding.serverCustom.id -> ServerSource.CUSTOM
                else -> ServerSource.DEFAULT
            })
            updateSummaries()
        }
        binding.autoSwitchRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            val enabled = checkedId == binding.autoSwitchOn.id
            UserSettingsStore.saveAutoSwitchWithinCountry(this, enabled)
            updateSummaries()
        }
    }

    private fun setupCustomInputWatcher() {
        binding.customServerInput.addTextChangedListener {
            if (isUpdatingUi) return@addTextChangedListener
            if (binding.serverCustom.isChecked) {
                val value = it?.toString()?.trim() ?: ""
                UserSettingsStore.saveCustomServerUrl(this, value)
                updateSummaries()
            }
        }
    }

    private fun setupCacheInputWatcher() {
        binding.cacheInput.addTextChangedListener { text ->
            if (isUpdatingUi) return@addTextChangedListener
        val minutes = text?.toString()?.toLongOrNull() ?: return@addTextChangedListener
        if (minutes <= 0) return@addTextChangedListener
        val ttlMs = minutes * 60 * 1000L
            currentCacheTtlMs = ttlMs
            UserSettingsStore.saveCacheTtlMs(this, ttlMs)
            updateSummaries()
        }
    }

    private fun updateSummaries() {
        binding.languageSummary.text = selectedRadioText(binding.languageRadioGroup)
        binding.themeSummary.text = selectedRadioText(binding.themeRadioGroup)
        binding.serverSummary.text = if (binding.serverCustom.isChecked) {
            val url = binding.customServerInput.text?.toString()?.takeIf { it.isNotBlank() }
            url ?: selectedRadioText(binding.serverRadioGroup)
        } else {
            selectedRadioText(binding.serverRadioGroup)
        }
        binding.autoSwitchSummary.text = selectedRadioText(binding.autoSwitchRadioGroup)
        binding.cacheSummary.text = formatMinutesSummary(currentCacheTtlMs)
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

    private fun loadSettingsIntoUi(settings: UserSettings) {
        isUpdatingUi = true
        when (settings.language) {
            LanguageOption.SYSTEM -> binding.languageRadioGroup.check(binding.languageSystem.id)
            LanguageOption.ENGLISH -> binding.languageRadioGroup.check(binding.languageEn.id)
            LanguageOption.RUSSIAN -> binding.languageRadioGroup.check(binding.languageRu.id)
            LanguageOption.POLISH -> binding.languageRadioGroup.check(binding.languagePl.id)
        }

        when (settings.theme) {
            ThemeOption.SYSTEM -> binding.themeRadioGroup.check(binding.themeSystem.id)
            ThemeOption.LIGHT -> binding.themeRadioGroup.check(binding.themeLight.id)
            ThemeOption.DARK -> binding.themeRadioGroup.check(binding.themeDark.id)
        }

        when (settings.serverSource) {
            ServerSource.DEFAULT -> binding.serverRadioGroup.check(binding.serverDefault.id)
            ServerSource.VPNGATE -> binding.serverRadioGroup.check(binding.serverVpngate.id)
            ServerSource.CUSTOM -> binding.serverRadioGroup.check(binding.serverCustom.id)
        }

        binding.customServerInput.setText(settings.customServerUrl)
        binding.customServerInputLayout.visibility =
            if (settings.serverSource == ServerSource.CUSTOM) View.VISIBLE else View.GONE

        currentCacheTtlMs = settings.cacheTtlMs
        val cacheMinutes = (settings.cacheTtlMs / 60000).coerceAtLeast(1)
        binding.cacheInput.setText(cacheMinutes.toString())
        binding.autoSwitchRadioGroup.check(
            if (settings.autoSwitchWithinCountry) binding.autoSwitchOn.id else binding.autoSwitchOff.id
        )

        isUpdatingUi = false
    }

    private fun formatMinutesSummary(ttlMs: Long): String {
        val minutes = (ttlMs / 60000).coerceAtLeast(1)
        return getString(R.string.settings_cache_summary_format, minutes)
    }
}
