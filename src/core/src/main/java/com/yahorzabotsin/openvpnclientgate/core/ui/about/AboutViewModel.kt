package com.yahorzabotsin.openvpnclientgate.core.ui.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.about.AboutInfoProvider
import com.yahorzabotsin.openvpnclientgate.core.about.AboutLinksProvider
import com.yahorzabotsin.openvpnclientgate.core.about.ElapsedRealtimeProvider
import com.yahorzabotsin.openvpnclientgate.core.about.LogExportInteractor
import com.yahorzabotsin.openvpnclientgate.core.about.LogExportResult
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

    private val _commands = MutableSharedFlow<AboutCommand>()
    val commands = _commands.asSharedFlow()

    private var lastActionAt: Long = 0
    private val clickDebounceMs = 500L

    init {
        loadContent()
    }

    fun onWebsiteClick() = handleUrlClick(state.value.links.website)
    fun onEmailClick() = handleEmailClick(state.value.links.email)
    fun onTelegramClick() = handleUrlClick(state.value.links.telegram)
    fun onGithubClick() = handleUrlClick(state.value.links.github)
    fun onGithubEngineClick() = handleUrlClick(state.value.links.githubEngine)
    fun onPlayClick() = handlePlayClick(state.value.links.googlePlay)
    fun onPrivacyClick() = handleUrlClick(state.value.links.privacyPolicy)
    fun onTermsClick() = handleUrlClick(state.value.links.termsOfUse)
    fun onLicenseClick() = handleUrlClick(state.value.links.gplv2)
    fun onIcsGithubClick() = handleUrlClick(state.value.links.icsGithub)
    fun onLogsClick() = exportLogs()

    fun onWebsiteLongClick() = handleCopyClick(state.value.links.website, R.string.copy_label_link)
    fun onEmailLongClick() = handleCopyClick(state.value.links.email, R.string.copy_label_email)
    fun onTelegramLongClick() = handleCopyClick(state.value.links.telegram, R.string.copy_label_link)
    fun onGithubLongClick() = handleCopyClick(state.value.links.github, R.string.copy_label_link)
    fun onGithubEngineLongClick() = handleCopyClick(state.value.links.githubEngine, R.string.copy_label_link)
    fun onPlayLongClick() = handleCopyClick(state.value.links.googlePlay, R.string.copy_label_link)
    fun onPrivacyLongClick() = handleCopyClick(state.value.links.privacyPolicy, R.string.copy_label_link)
    fun onTermsLongClick() = handleCopyClick(state.value.links.termsOfUse, R.string.copy_label_link)
    fun onLicenseLongClick() = handleCopyClick(state.value.links.gplv2, R.string.copy_label_link)
    fun onIcsGithubLongClick() = handleCopyClick(state.value.links.icsGithub, R.string.copy_label_link)

    private fun loadContent() {
        val info = infoProvider.load()
        val links = linksProvider.get()
        _state.value = _state.value.copy(info = info, links = links)
    }

    private fun handleUrlClick(url: String) {
        if (!canProceed()) return
        openUrl(url)
    }

    private fun handleEmailClick(email: String) {
        if (!canProceed()) return
        openEmail(email)
    }

    private fun handlePlayClick(webUrl: String) {
        if (!canProceed()) return
        openPlay(webUrl)
    }

    private fun handleCopyClick(value: String, labelResId: Int) {
        if (!canProceed()) return
        if (value.isBlank()) return
        viewModelScope.launch {
            _commands.emit(AboutCommand.CopyToClipboard(labelResId, value))
            _commands.emit(AboutCommand.ShowToast(UiText.Res(R.string.copied_to_clipboard), ToastDuration.SHORT))
        }
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            _commands.emit(AboutCommand.OpenUrl(url))
        }
    }

    private fun openEmail(email: String) {
        if (email.isBlank()) return
        viewModelScope.launch {
            _commands.emit(AboutCommand.OpenEmail(email))
        }
    }

    private fun openPlay(webUrl: String) {
        if (webUrl.isBlank()) return
        viewModelScope.launch {
            _commands.emit(AboutCommand.OpenPlay(webUrl))
        }
    }

    private fun exportLogs() {
        if (_state.value.isExportingLogs) return
        _state.value = _state.value.copy(isExportingLogs = true)
        viewModelScope.launch {
            _commands.emit(AboutCommand.ShowToast(UiText.Res(R.string.about_logs_export_started), ToastDuration.SHORT))
            when (val result = logExportUseCase.export()) {
                is LogExportResult.Success -> {
                    _commands.emit(AboutCommand.ShareLogArchive(result.path))
                    _commands.emit(
                        AboutCommand.ShowToast(
                            UiText.Res(R.string.about_logs_export_done_format, listOf(result.path)),
                            ToastDuration.LONG
                        )
                    )
                }
                is LogExportResult.Failure -> {
                    _commands.emit(
                        AboutCommand.ShowToast(
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
        if (now - lastActionAt < clickDebounceMs) return false
        lastActionAt = now
        return true
    }
}
