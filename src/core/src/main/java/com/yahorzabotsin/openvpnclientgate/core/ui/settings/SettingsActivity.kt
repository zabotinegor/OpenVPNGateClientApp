package com.yahorzabotsin.openvpnclientgate.core.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.databinding.ActivityTemplateBinding
import com.yahorzabotsin.openvpnclientgate.core.databinding.ContentSettingsBinding
import com.yahorzabotsin.openvpnclientgate.core.settings.LanguageOption
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.ThemeOption
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import com.yahorzabotsin.openvpnclientgate.core.ui.common.navigation.TemplatePage
import com.yahorzabotsin.openvpnclientgate.vpn.VpnManager
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class SettingsActivity : AppCompatActivity() {
    private lateinit var templateBinding: ActivityTemplateBinding
    private lateinit var binding: ContentSettingsBinding
    private var isUpdatingUi = false
    private val viewModel: SettingsViewModel by viewModel()
    private var initialFocusRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        templateBinding = TemplatePage.create(this, R.string.menu_settings, null)
        binding = ContentSettingsBinding.inflate(layoutInflater, templateBinding.contentContainer, true)
        setupCollapsibles()
        setupRadioGroups()
        setupCustomInputWatcher()
        setupCacheInputWatcher()
        setupStatusTimerWatcher()
        observeViewModel()
    }

    private fun setupCollapsibles() {
        setupCollapsibleSection(
            header = binding.languageHeader,
            content = binding.languageRadioGroup,
            chevron = binding.languageChevron,
            collapsedNextId = binding.themeHeader.id,
            expandedFirstId = binding.languageSystem.id
        )
        setupCollapsibleSection(
            header = binding.themeHeader,
            content = binding.themeRadioGroup,
            chevron = binding.themeChevron,
            collapsedNextId = binding.serverHeader.id,
            expandedFirstId = binding.themeSystem.id
        )
        setupCollapsibleSection(
            header = binding.serverHeader,
            content = binding.serverContent,
            chevron = binding.serverChevron,
            collapsedNextId = binding.autoSwitchHeader.id,
            expandedFirstId = binding.serverDefault.id
        )
        setupCollapsibleSection(
            header = binding.autoSwitchHeader,
            content = binding.autoSwitchContent,
            chevron = binding.autoSwitchChevron,
            collapsedNextId = binding.statusTimerHeader.id,
            expandedFirstId = binding.autoSwitchOn.id
        )
        setupCollapsibleSection(
            header = binding.statusTimerHeader,
            content = binding.statusTimerInputLayout,
            chevron = binding.statusTimerChevron,
            collapsedNextId = binding.cacheHeader.id,
            expandedFirstId = binding.statusTimerInput.id
        )
        setupCollapsibleSection(
            header = binding.cacheHeader,
            content = binding.cacheInputLayout,
            chevron = binding.cacheChevron,
            collapsedNextId = View.NO_ID,
            expandedFirstId = binding.cacheInput.id
        )
        setupContentFocus()
    }

    private fun setupRadioGroups() {
        binding.languageRadioGroup.setOnCheckedChangeListener { _, _ ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            val selected = when (binding.languageRadioGroup.checkedRadioButtonId) {
                binding.languageSystem.id -> LanguageOption.SYSTEM
                binding.languageEn.id -> LanguageOption.ENGLISH
                binding.languageRu.id -> LanguageOption.RUSSIAN
                binding.languagePl.id -> LanguageOption.POLISH
                else -> LanguageOption.SYSTEM
            }
            viewModel.onAction(SettingsAction.SelectLanguage(selected))
        }
        binding.themeRadioGroup.setOnCheckedChangeListener { _, _ ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            val selected = when (binding.themeRadioGroup.checkedRadioButtonId) {
                binding.themeSystem.id -> ThemeOption.SYSTEM
                binding.themeLight.id -> ThemeOption.LIGHT
                binding.themeDark.id -> ThemeOption.DARK
                else -> ThemeOption.SYSTEM
            }
            viewModel.onAction(SettingsAction.SelectTheme(selected))
        }
        binding.serverRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            val source = when (checkedId) {
                binding.serverDefault.id -> ServerSource.DEFAULT
                binding.serverVpngate.id -> ServerSource.VPNGATE
                binding.serverCustom.id -> ServerSource.CUSTOM
                else -> ServerSource.DEFAULT
            }
            viewModel.onAction(SettingsAction.SelectServerSource(source))
        }
        binding.autoSwitchRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            val enabled = checkedId == binding.autoSwitchOn.id
            viewModel.onAction(SettingsAction.SetAutoSwitchWithinCountry(enabled))
        }
    }

    private fun setupCustomInputWatcher() {
        binding.customServerInput.addTextChangedListener {
            if (isUpdatingUi) return@addTextChangedListener
            if (binding.serverCustom.isChecked) {
                val value = it?.toString() ?: ""
                viewModel.onAction(SettingsAction.SetCustomServerUrl(value))
            }
        }
    }

    private fun setupCacheInputWatcher() {
        binding.cacheInput.addTextChangedListener { text ->
            if (isUpdatingUi) return@addTextChangedListener
            viewModel.onAction(SettingsAction.SetCacheTtlInput(text?.toString().orEmpty()))
        }
    }

    private fun setupStatusTimerWatcher() {
        binding.statusTimerInput.addTextChangedListener { text ->
            if (isUpdatingUi) return@addTextChangedListener
            viewModel.onAction(SettingsAction.SetStatusStallTimeoutInput(text?.toString().orEmpty()))
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(it) } }
                launch { viewModel.effects.collect { handleEffect(it) } }
            }
        }
    }

    private fun setupCollapsibleSection(header: View, content: View, chevron: ImageView) {
        setExpanded(content, chevron, false)
        header.setOnClickListener {
            val expanded = !content.isVisible
            setExpanded(content, chevron, expanded)
        }
    }

    private fun setupCollapsibleSection(
        header: View,
        content: View,
        chevron: ImageView,
        collapsedNextId: Int,
        expandedFirstId: Int
    ) {
        setExpanded(content, chevron, false)
        updateHeaderNextFocus(header, expanded = false, collapsedNextId, expandedFirstId)
        header.setOnClickListener {
            val expanded = !content.isVisible
            setExpanded(content, chevron, expanded)
            updateHeaderNextFocus(header, expanded, collapsedNextId, expandedFirstId)
        }
    }

    private fun updateHeaderNextFocus(
        header: View,
        expanded: Boolean,
        collapsedNextId: Int,
        expandedFirstId: Int
    ) {
        header.nextFocusDownId = if (expanded) expandedFirstId else collapsedNextId
    }

    private fun setExpanded(content: View, chevron: ImageView, expanded: Boolean) {
        content.isVisible = expanded
        chevron.animate().rotation(if (expanded) 90f else 0f).setDuration(150).start()
    }

    private fun setupContentFocus() {
        binding.languageSystem.nextFocusUpId = binding.languageHeader.id
        binding.languagePl.nextFocusDownId = binding.themeHeader.id
        binding.themeSystem.nextFocusUpId = binding.themeHeader.id
        binding.themeDark.nextFocusDownId = binding.serverHeader.id
        binding.serverDefault.nextFocusUpId = binding.serverHeader.id
        binding.autoSwitchOn.nextFocusUpId = binding.autoSwitchHeader.id
        binding.autoSwitchOff.nextFocusDownId = binding.statusTimerHeader.id
        binding.statusTimerInput.nextFocusUpId = binding.statusTimerHeader.id
        binding.statusTimerInput.nextFocusDownId = binding.cacheHeader.id
        binding.cacheInput.nextFocusUpId = binding.cacheHeader.id
    }

    private fun updateServerContentFocus(isCustom: Boolean) {
        val nextHeaderId = binding.autoSwitchHeader.id
        if (isCustom) {
            binding.serverCustom.nextFocusDownId = binding.customServerInput.id
            binding.customServerInput.nextFocusDownId = nextHeaderId
        } else {
            binding.serverCustom.nextFocusDownId = nextHeaderId
        }
    }

    private fun render(state: SettingsUiState) {
        isUpdatingUi = true
        when (state.language) {
            LanguageOption.SYSTEM -> binding.languageRadioGroup.check(binding.languageSystem.id)
            LanguageOption.ENGLISH -> binding.languageRadioGroup.check(binding.languageEn.id)
            LanguageOption.RUSSIAN -> binding.languageRadioGroup.check(binding.languageRu.id)
            LanguageOption.POLISH -> binding.languageRadioGroup.check(binding.languagePl.id)
        }

        when (state.theme) {
            ThemeOption.SYSTEM -> binding.themeRadioGroup.check(binding.themeSystem.id)
            ThemeOption.LIGHT -> binding.themeRadioGroup.check(binding.themeLight.id)
            ThemeOption.DARK -> binding.themeRadioGroup.check(binding.themeDark.id)
        }

        when (state.serverSource) {
            ServerSource.DEFAULT -> binding.serverRadioGroup.check(binding.serverDefault.id)
            ServerSource.VPNGATE -> binding.serverRadioGroup.check(binding.serverVpngate.id)
            ServerSource.CUSTOM -> binding.serverRadioGroup.check(binding.serverCustom.id)
        }

        if (binding.customServerInput.text?.toString() != state.customServerUrl) {
            binding.customServerInput.setText(state.customServerUrl)
        }
        binding.customServerInputLayout.visibility =
            if (state.serverSource == ServerSource.CUSTOM) View.VISIBLE else View.GONE

        if (binding.cacheInput.text?.toString() != state.cacheTtlInput) {
            binding.cacheInput.setText(state.cacheTtlInput)
        }

        if (binding.statusTimerInput.text?.toString() != state.statusStallTimeoutInput) {
            binding.statusTimerInput.setText(state.statusStallTimeoutInput)
        }

        binding.autoSwitchRadioGroup.check(
            if (state.autoSwitchWithinCountry) binding.autoSwitchOn.id else binding.autoSwitchOff.id
        )

        updateServerContentFocus(state.serverSource == ServerSource.CUSTOM)
        updateSummaries(state)
        isUpdatingUi = false

        if (!initialFocusRequested) {
            initialFocusRequested = true
            binding.languageHeader.requestFocus()
        }
    }

    private fun handleEffect(effect: SettingsEffect) {
        when (effect) {
            SettingsEffect.ApplyThemeAndLocale -> UserSettingsStore.applyThemeAndLocale(this)
            SettingsEffect.RefreshNotification -> VpnManager.refreshNotification(this)
        }
    }

    private fun updateSummaries(state: SettingsUiState) {
        binding.languageSummary.text = languageLabel(state.language)
        binding.themeSummary.text = themeLabel(state.theme)
        binding.serverSummary.text = if (state.serverSource == ServerSource.CUSTOM) {
            val url = state.customServerUrl.trim().takeIf { it.isNotBlank() }
            url ?: serverLabel(state.serverSource)
        } else {
            serverLabel(state.serverSource)
        }
        binding.autoSwitchSummary.text =
            if (state.autoSwitchWithinCountry) binding.autoSwitchOn.text else binding.autoSwitchOff.text
        binding.statusTimerSummary.text = getString(
            R.string.settings_status_timer_summary_format,
            state.statusStallTimeoutSeconds
        )
        binding.cacheSummary.text = formatMinutesSummary(state.cacheTtlMs)
    }

    private fun languageLabel(option: LanguageOption): String = when (option) {
        LanguageOption.SYSTEM -> binding.languageSystem.text.toString()
        LanguageOption.ENGLISH -> binding.languageEn.text.toString()
        LanguageOption.RUSSIAN -> binding.languageRu.text.toString()
        LanguageOption.POLISH -> binding.languagePl.text.toString()
    }

    private fun themeLabel(option: ThemeOption): String = when (option) {
        ThemeOption.SYSTEM -> binding.themeSystem.text.toString()
        ThemeOption.LIGHT -> binding.themeLight.text.toString()
        ThemeOption.DARK -> binding.themeDark.text.toString()
    }

    private fun serverLabel(source: ServerSource): String = when (source) {
        ServerSource.DEFAULT -> binding.serverDefault.text.toString()
        ServerSource.VPNGATE -> binding.serverVpngate.text.toString()
        ServerSource.CUSTOM -> binding.serverCustom.text.toString()
    }

    private fun formatMinutesSummary(ttlMs: Long): String {
        val minutes = (ttlMs / 60000).coerceAtLeast(1)
        return getString(R.string.settings_cache_summary_format, minutes)
    }
}


