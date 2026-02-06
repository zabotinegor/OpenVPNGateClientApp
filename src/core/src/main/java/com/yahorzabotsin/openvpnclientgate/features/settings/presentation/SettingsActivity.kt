package com.yahorzabotsin.openvpnclientgate.features.settings.presentation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.yahorzabotsin.openvpnclientgate.core.CoreApp
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.databinding.ContentSettingsBinding
import com.yahorzabotsin.openvpnclientgate.core.ui.BaseTemplateActivity
import com.yahorzabotsin.openvpnclientgate.features.settings.presentation.utils.toId
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.features.settings.presentation.utils.toLanguageOption
import com.yahorzabotsin.openvpnclientgate.features.settings.presentation.utils.toServerSource
import com.yahorzabotsin.openvpnclientgate.features.settings.presentation.utils.formatFromMinutes
import com.yahorzabotsin.openvpnclientgate.features.settings.presentation.utils.toThemeOption
import kotlinx.coroutines.launch

class SettingsActivity : BaseTemplateActivity(R.string.menu_settings) {
    private lateinit var binding: ContentSettingsBinding
    private var isUiLoading = false

    private val viewModel: SettingsViewModel by viewModels {
        SettingsViewModel.provideFactory((application as CoreApp).settingsRepository)
    }

    override fun inflateContent(inflater: LayoutInflater, container: ViewGroup) {
        binding = ContentSettingsBinding.inflate(inflater, container, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupCollapsibles()
        setupContentFocus()
        setupListeners()
        observeState()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    if (state is SettingsState.Success)
                        loadSettingsIntoUi(state)
                }
            }
        }
    }

    private fun syncRadioGroup(group: RadioGroup, id: Int) {
        if (group.checkedRadioButtonId != id) {
            group.check(id)
        }
    }

    private fun syncText(view: EditText, text: String) {
        if (!view.hasFocus()) return
        if (view.text.toString() != text) {
            view.setText(text)
            view.setSelection(view.text.length)
        }
    }

    private fun loadSettingsIntoUi(state: SettingsState.Success) = with(binding) {

        isUiLoading = true
        syncRadioGroup(languageRadioGroup, id = state.language.toId())

        syncRadioGroup(themeRadioGroup, id = state.theme.toId())

        syncRadioGroup(serverRadioGroup, id = state.serverSource.toId())

        syncRadioGroup(
            autoSwitchRadioGroup,
            id = if (state.isAutoSwitchEnabled) autoSwitchOn.id else autoSwitchOff.id
        )

        syncText(customServerInput, state.customServerUrl)
        syncText(cacheInput, formatMinutesSummary(state.cacheTtlMs))
        syncText(statusTimerInput, state.statusStallTimeoutSeconds.toString())

        customServerInputLayout.isVisible = state.serverSource == ServerSource.CUSTOM

        updateSummaries(state)
        updateServerContentFocus()
        isUiLoading = false
    }


    private fun setupListeners() = with(binding) {
        languageRadioGroup.setOnCheckedChangeListener { _, id ->
            if (!isUiLoading)
                viewModel.handleIntent(SettingsIntent.OnChangeLanguage(id.toLanguageOption()))
        }
        themeRadioGroup.setOnCheckedChangeListener { _, id ->
            if (!isUiLoading)
                viewModel.handleIntent(SettingsIntent.OnChangeTheme(id.toThemeOption()))
        }
        serverRadioGroup.setOnCheckedChangeListener { _, id ->
            if (!isUiLoading)
                viewModel.handleIntent(SettingsIntent.OnChangeServerSource(id.toServerSource()))
        }
        autoSwitchRadioGroup.setOnCheckedChangeListener { _, id ->
            if (!isUiLoading)
                viewModel.handleIntent(SettingsIntent.OnToggleAutoSwitch(id == binding.autoSwitchOn.id))
        }
        customServerInput.addTextChangedListener {
            if (!isUiLoading)
                viewModel.handleIntent(
                    SettingsIntent.OnUpdateCustomServerUrl(
                        it?.toString()?.trim() ?: ""
                    )
                )
        }
        cacheInput.addTextChangedListener {
            if (!isUiLoading)
                it?.toString()?.toLongOrNull()?.let { mins ->
                    viewModel.handleIntent(
                        SettingsIntent.OnUpdateCachedServersTimer(formatFromMinutes(mins))
                    )
                }
        }
        statusTimerInput.addTextChangedListener {
            if (!isUiLoading)
                it?.toString()?.toIntOrNull()
                    ?.let { secs -> viewModel.handleIntent(SettingsIntent.OnUpdateStatusTimer(secs)) }
        }
    }

    private fun setupCollapsibles() = with(binding) {
        setupCollapsibleSection(
            header = languageHeader,
            content = languageRadioGroup,
            chevron = languageChevron,
            collapsedNextId = themeHeader.id,
            expandedFirstId = languageSystem.id
        )
        setupCollapsibleSection(
            header = themeHeader,
            content = themeRadioGroup,
            chevron = themeChevron,
            collapsedNextId = serverHeader.id,
            expandedFirstId = themeSystem.id
        )
        setupCollapsibleSection(
            header = serverHeader,
            content = serverContent,
            chevron = serverChevron,
            collapsedNextId = autoSwitchHeader.id,
            expandedFirstId = serverDefault.id
        )
        setupCollapsibleSection(
            header = autoSwitchHeader,
            content = autoSwitchContent,
            chevron = autoSwitchChevron,
            collapsedNextId = statusTimerHeader.id,
            expandedFirstId = autoSwitchOn.id
        )
        setupCollapsibleSection(
            header = statusTimerHeader,
            content = statusTimerInputLayout,
            chevron = statusTimerChevron,
            collapsedNextId = cacheHeader.id,
            expandedFirstId = statusTimerInput.id
        )
        setupCollapsibleSection(
            header = cacheHeader,
            content = cacheInputLayout,
            chevron = cacheChevron,
            collapsedNextId = View.NO_ID,
            expandedFirstId = cacheInput.id
        )
        setupContentFocus()
    }

    private fun updateSummaries(state: SettingsState.Success) = with(binding) {
        languageSummary.text = selectedRadioText(languageRadioGroup)
        themeSummary.text = selectedRadioText(themeRadioGroup)
        cacheSummary.text = formatMinutesSummary(state.cacheTtlMs)
        autoSwitchSummary.text = selectedRadioText(autoSwitchRadioGroup)
        statusTimerSummary.text = getString(
            R.string.settings_status_timer_summary_format,
            state.statusStallTimeoutSeconds
        )
        serverSummary.text = if (serverCustom.isChecked) {
            val url = customServerInput.text?.toString()?.takeIf { it.isNotBlank() }
            url ?: selectedRadioText(serverRadioGroup)
        } else {
            selectedRadioText(serverRadioGroup)
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

    private fun setExpanded(content: View, chevron: ImageView, expanded: Boolean) {
        content.isVisible = expanded
        chevron.animate().rotation(if (expanded) 90f else 0f).setDuration(150).start()
    }

    private fun updateHeaderNextFocus(
        header: View,
        expanded: Boolean,
        collapsedNextId: Int,
        expandedFirstId: Int
    ) {
        header.nextFocusDownId = if (expanded) expandedFirstId else collapsedNextId
    }

    private fun setupContentFocus() = with(binding) {
        languageSystem.nextFocusUpId = languageHeader.id
        languagePl.nextFocusDownId = themeHeader.id
        themeSystem.nextFocusUpId = themeHeader.id
        themeDark.nextFocusDownId = serverHeader.id
        serverDefault.nextFocusUpId = serverHeader.id
        autoSwitchOn.nextFocusUpId = autoSwitchHeader.id
        autoSwitchOff.nextFocusDownId = statusTimerHeader.id
        statusTimerInput.nextFocusUpId = statusTimerHeader.id
        statusTimerInput.nextFocusDownId = cacheHeader.id
        cacheInput.nextFocusUpId = cacheHeader.id
    }

    private fun updateServerContentFocus() = with(binding) {
        val nextHeaderId = autoSwitchHeader.id
        if (serverCustom.isChecked) {
            serverCustom.nextFocusDownId = customServerInput.id
            customServerInput.nextFocusDownId = nextHeaderId
        } else {
            serverCustom.nextFocusDownId = nextHeaderId
        }
    }

    private fun selectedRadioText(group: RadioGroup): String {
        val selectedId = group.checkedRadioButtonId
        val radio = group.findViewById<RadioButton>(selectedId)
        return radio?.text?.toString().orEmpty()
    }

    private fun formatMinutesSummary(ttlMs: Long): String {
        val minutes = (ttlMs / 60000).coerceAtLeast(1)
        return getString(R.string.settings_cache_summary_format, minutes)
    }
}
