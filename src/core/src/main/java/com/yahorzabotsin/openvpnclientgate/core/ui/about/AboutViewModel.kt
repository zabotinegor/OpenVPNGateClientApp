package com.yahorzabotsin.openvpnclientgate.core.ui.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.about.AboutInfoProvider
import com.yahorzabotsin.openvpnclientgate.core.about.AboutLinksProvider
import com.yahorzabotsin.openvpnclientgate.core.about.ElapsedRealtimeProvider
import com.yahorzabotsin.openvpnclientgate.core.about.LogExportInteractor
import com.yahorzabotsin.openvpnclientgate.core.about.LogExportResult
import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.UiText
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AboutViewModel(
    private val infoProvider: AboutInfoProvider,
    private val linksProvider: AboutLinksProvider,
    private val logExportUseCase: LogExportInteractor,
    private val elapsedRealtimeProvider: ElapsedRealtimeProvider
) : ViewModel() {

    private val _state = MutableStateFlow(AboutUiState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<AboutEffect>()
    val effects = _effects.asSharedFlow()

    private val clickDebounceMs = 500L

    init {
        loadContent()
    }

    fun onAction(action: AboutAction) {
        when (action) {
            is AboutAction.RowClick -> handleRowClick(action.id)
            is AboutAction.RowLongClick -> handleRowLongClick(action.id)
        }
    }

    private fun loadContent() {
        val info = infoProvider.load()
        val links = linksProvider.get()
        _state.value = _state.value.copy(info = info, links = links)
    }

    private fun handleRowClick(id: AboutRowId) {
        if (!canProceed()) return
        when (id) {
            AboutRowId.LOGS -> exportLogs()
            AboutRowId.WEBSITE -> openUrl(state.value.links.website)
            AboutRowId.EMAIL -> openEmail(state.value.links.email)
            AboutRowId.TELEGRAM -> openUrl(state.value.links.telegram)
            AboutRowId.GITHUB -> openUrl(state.value.links.github)
            AboutRowId.GITHUB_ENGINE -> openUrl(state.value.links.githubEngine)
            AboutRowId.PLAY -> openPlay(state.value.links.googlePlay)
            AboutRowId.PRIVACY -> openUrl(state.value.links.privacyPolicy)
            AboutRowId.TERMS -> openUrl(state.value.links.termsOfUse)
            AboutRowId.LICENSE -> openUrl(state.value.links.gplv2)
            AboutRowId.ICS_GITHUB -> openUrl(state.value.links.icsGithub)
        }
    }

    private fun handleRowLongClick(id: AboutRowId) {
        if (!canProceed()) return
        val links = state.value.links
        val (value, labelResId) = when (id) {
            AboutRowId.EMAIL -> links.email to R.string.copy_label_email
            AboutRowId.WEBSITE -> links.website to R.string.copy_label_link
            AboutRowId.TELEGRAM -> links.telegram to R.string.copy_label_link
            AboutRowId.GITHUB -> links.github to R.string.copy_label_link
            AboutRowId.GITHUB_ENGINE -> links.githubEngine to R.string.copy_label_link
            AboutRowId.PLAY -> links.googlePlay to R.string.copy_label_link
            AboutRowId.PRIVACY -> links.privacyPolicy to R.string.copy_label_link
            AboutRowId.TERMS -> links.termsOfUse to R.string.copy_label_link
            AboutRowId.LICENSE -> links.gplv2 to R.string.copy_label_link
            AboutRowId.ICS_GITHUB -> links.icsGithub to R.string.copy_label_link
            AboutRowId.LOGS -> "" to R.string.copy_label_link
        }
        if (value.isBlank()) return
        viewModelScope.launch {
            _effects.emit(AboutEffect.CopyToClipboard(labelResId, value))
            _effects.emit(AboutEffect.ShowToast(UiText.Res(R.string.copied_to_clipboard), ToastDuration.SHORT))
        }
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            _effects.emit(AboutEffect.OpenUrl(url))
        }
    }

    private fun openEmail(email: String) {
        if (email.isBlank()) return
        viewModelScope.launch {
            _effects.emit(AboutEffect.OpenEmail(email))
        }
    }

    private fun openPlay(webUrl: String) {
        if (webUrl.isBlank()) return
        viewModelScope.launch {
            _effects.emit(AboutEffect.OpenPlay(webUrl))
        }
    }

    private fun exportLogs() {
        if (_state.value.isExportingLogs) return
        _state.value = _state.value.copy(isExportingLogs = true)
        viewModelScope.launch {
            _effects.emit(AboutEffect.ShowToast(UiText.Res(R.string.about_logs_export_started), ToastDuration.SHORT))
            when (val result = logExportUseCase.export()) {
                is LogExportResult.Success -> {
                    _effects.emit(AboutEffect.ShareLogArchive(result.path))
                    _effects.emit(
                        AboutEffect.ShowToast(
                            UiText.Res(R.string.about_logs_export_done_format, listOf(result.path)),
                            ToastDuration.LONG
                        )
                    )
                }
                is LogExportResult.Failure -> {
                    _effects.emit(
                        AboutEffect.ShowToast(
                            UiText.Res(R.string.about_logs_export_failed_format, listOf(result.reason)),
                            ToastDuration.LONG
                        )
                    )
                }
            }
            _state.value = _state.value.copy(isExportingLogs = false)
        }
    }

    private fun canProceed(): Boolean {
        val now = elapsedRealtimeProvider.elapsedRealtimeMs()
        if (now - _state.value.lastActionAtMs < clickDebounceMs) return false
        _state.value = _state.value.copy(lastActionAtMs = now)
        return true
    }
}
