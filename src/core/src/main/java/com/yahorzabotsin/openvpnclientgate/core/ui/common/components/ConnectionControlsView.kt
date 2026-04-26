package com.yahorzabotsin.openvpnclientgate.core.ui.common.components

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import androidx.appcompat.content.res.AppCompatResources
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isVisible
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.logging.launchLogged
import com.yahorzabotsin.openvpnclientgate.core.databinding.ViewConnectionControlsBinding
import com.yahorzabotsin.openvpnclientgate.core.ui.common.utils.TvUtils
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryVersionSignal
import com.yahorzabotsin.openvpnclientgate.core.servers.countryFlagEmoji
import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter

class ConnectionControlsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    internal enum class FocusTarget {
        PAUSE,
        START,
        NONE
    }

    private val binding: ViewConnectionControlsBinding =
        ViewConnectionControlsBinding.inflate(LayoutInflater.from(context), this)

    private var vpnConfig: String? = null
    private var selectedCountry: String? = null
    private var selectedCountryCode: String? = null
    private var selectedServerIp: String? = null
    private var openServerList: (() -> Unit)? = null
    private var onConnectionButtonClick: (() -> Unit)? = null
    private var onPauseButtonClick: (() -> Unit)? = null
    private var pauseActionFocusPending: Boolean = false
    private var connectionDetailsListener: ConnectionDetailsListener? = null
    private var presenter: ConnectionControlsPresenter =
        ConnectionControlsPresenter(context, ConnectionControlsUseCase())
    private var runtime: ConnectionControlsRuntime = DefaultConnectionControlsRuntime()
    private var selectionStore: ConnectionControlsSelectionStore = DefaultConnectionControlsSelectionStore()

    companion object {
        private val TAG = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "ConnectionControlsView"

        internal fun resolveFocusTarget(isTvDevice: Boolean, pauseHadFocus: Boolean, pauseVisible: Boolean): FocusTarget {
            if (!isTvDevice || !pauseHadFocus) return FocusTarget.NONE
            return if (pauseVisible) FocusTarget.PAUSE else FocusTarget.START
        }
    }

    init {
        applyServerSelectionButtonAppearance()
        applyServerSelectionLabel(context.getString(R.string.current_country))
        setupClicks()
    }

    private fun applyServerSelectionButtonAppearance() {
        val button = binding.serverSelectionContainer
        button.backgroundTintList = AppCompatResources.getColorStateList(context, R.color.server_selection_background_tint)
        button.strokeColor = AppCompatResources.getColorStateList(context, R.color.server_selection_stroke_color)
        button.strokeWidth = resources.displayMetrics.density.toInt().coerceAtLeast(1)
    }

    private fun setupClicks() {
        binding.startConnectionButton.setOnClickListener {
            pauseActionFocusPending = false
            onConnectionButtonClick?.invoke()
        }

        binding.pauseConnectionButton.setOnClickListener {
            pauseActionFocusPending = true
            onPauseButtonClick?.invoke()
        }

        binding.serverSelectionContainer.setOnClickListener {
            pauseActionFocusPending = false
            AppLog.d(TAG, "Server selection container clicked")
            openServerList?.invoke()
        }
    }

    fun requestPrimaryFocus() {
        binding.startConnectionButton.isFocusable = true
        binding.startConnectionButton.isFocusableInTouchMode = true
        binding.startConnectionButton.requestFocus()
    }

    fun setConnectionDetailsListener(listener: ConnectionDetailsListener?) {
        connectionDetailsListener = listener
    }

    fun setConnectionButtonClickHandler(handler: () -> Unit) {
        onConnectionButtonClick = handler
    }

    fun setPauseButtonClickHandler(handler: () -> Unit) {
        onPauseButtonClick = handler
    }

    fun setOpenServerListHandler(handler: () -> Unit) {
        openServerList = handler
    }

    fun setServer(country: String, countryCode: String? = null, ip: String? = null) {
        AppLog.d(TAG, "Server set: $country, ip=$ip")
        selectedCountry = country
        selectedCountryCode = countryCode
        updateAddress(ip)
        applyServerSelectionLabel(country, ip)
        updateServerPosition()

        if (runtime.state.value == ConnectionState.DISCONNECTED) {
            updateLocationPlaceholders()
        }
    }

    fun setVpnConfig(config: String) {
        setVpnConfigInternal(config)
    }

    fun setVpnConfigFromUser(config: String) {
        setVpnConfigInternal(config)
    }

    private fun setVpnConfigInternal(config: String) {
        AppLog.d(TAG, "VPN config set")
        vpnConfig = config
        val resolvedIp = resolveIpForConfig(config)
        if (!resolvedIp.isNullOrBlank()) {
            updateAddress(resolvedIp)
            applyServerSelectionLabel(selectedCountry ?: context.getString(R.string.current_country), resolvedIp)
        }
        updateServerPosition()
    }

    fun setLifecycleOwner(lifecycleOwner: LifecycleOwner) {
        lifecycleOwner.launchLogged(TAG) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                runtime.state.collect { state ->
                    updateStatusLabel(state)
                    updateButtonState(state)
                    syncSelectedServerIpFromStore()
                }
            }
        }
        lifecycleOwner.launchLogged(TAG) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                SelectedCountryVersionSignal.version
                    .filter { it > 0L }
                    .collect {
                        updateServerPosition()
                        syncSelectedServerIpFromStore()
                    }
            }
        }
        lifecycleOwner.launchLogged(TAG) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    runtime.engineLevel,
                    runtime.engineDetail,
                    runtime.reconnectingHint,
                    runtime.remainingSeconds
                ) { _, _, _, _ -> }
                    .collect {
                        val current = runtime.state.value
                        updateStatusLabel(current)
                        updateButtonState(current)
                        syncSelectedServerIpFromStore()
                    }
            }
        }
        lifecycleOwner.launchLogged(TAG) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    updateDurationTimer()
                    delay(1000L)
                }
            }
        }
        lifecycleOwner.launchLogged(TAG) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    runtime.downloadedBytes,
                    runtime.uploadedBytes
                ) { downloaded, uploaded -> downloaded to uploaded }
                    .collect { (downloaded, uploaded) ->
                        updateTraffic(downloaded, uploaded)
                    }
            }
        }
    }

    fun setRuntime(
        runtime: ConnectionControlsRuntime,
        selectionStore: ConnectionControlsSelectionStore = DefaultConnectionControlsSelectionStore()
    ) {
        this.runtime = runtime
        this.selectionStore = selectionStore
    }

    fun setDependencies(
        presenter: ConnectionControlsPresenter,
        runtime: ConnectionControlsRuntime,
        selectionStore: ConnectionControlsSelectionStore
    ) {
        this.presenter = presenter
        this.runtime = runtime
        this.selectionStore = selectionStore
    }

    private fun applyServerSelectionLabel(country: String, ip: String? = selectedServerIp) {
        val primary = country.ifBlank { context.getString(R.string.current_country) }
        val flag = countryFlagEmoji(selectedCountryCode)
        val primaryWithFlag = if (!flag.isNullOrEmpty()) "$flag $primary" else primary
        binding.serverSelectionContainer.text = buildServerSelectionLabel(primaryWithFlag, ip)
        val description = listOf(primaryWithFlag, ip.orEmpty())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(separator = ", ")
        binding.serverSelectionContainer.contentDescription = description
        updateServerButtonIcons(showGlobe = flag.isNullOrEmpty())
    }

    private fun buildServerSelectionLabel(country: String, ip: String?): CharSequence =
        buildSpannedString {
            inSpans(android.text.style.TextAppearanceSpan(context, R.style.TextAppearance_OpenVPNClientGate_BodyAdditional)) {
                append(country.trim())
            }
        }

    private fun syncSelectedServerIpFromStore() {
        val sync = buildServerSync() ?: return
        selectedCountry = sync.country

        if (!sync.ip.isNullOrBlank() && sync.ip != selectedServerIp) {
            updateAddress(sync.ip)
            applyServerSelectionLabel(sync.country ?: context.getString(R.string.current_country), sync.ip)
        } else if (!selectedServerIp.isNullOrBlank()) {
            updateAddress(selectedServerIp)
        }
        connectionDetailsListener?.updateCity(sync.cityText)
    }

    private fun updateStatusLabel(state: ConnectionState) {
        val statusText = presenter.buildStatusText(
            state = state,
            engineLevel = runtime.engineLevel.value,
            remainingSeconds = runtime.remainingSeconds.value
        )
        connectionDetailsListener?.updateStatus(statusText)
    }

    private fun updateButtonState(state: ConnectionState) {
        val model = presenter.buildButtonModel(
            state = state,
            detail = runtime.engineDetail.value,
            level = runtime.engineLevel.value,
            reconnectingHint = runtime.reconnectingHint.value
        )
        val connectButton = binding.startConnectionButton
        val pauseButton = binding.pauseConnectionButton
        val pauseHadFocus = pauseButton.hasFocus()
        val focusPolicyRequested = pauseHadFocus || pauseActionFocusPending

        connectButton.text = model.text
        val color = when (model.style) {
            ConnectionButtonStyle.ACTIVE -> ContextCompat.getColor(context, R.color.connection_button_active)
            ConnectionButtonStyle.CONNECTING -> ContextCompat.getColor(context, R.color.connection_button_connecting)
            ConnectionButtonStyle.DISCONNECTED ->
                ContextCompat.getColor(context, R.color.connection_button_disconnected)
        }
        connectButton.backgroundTintList = ColorStateList.valueOf(color)

        val pauseModel = presenter.buildPauseButtonModel(state)
        pauseButton.isVisible = pauseModel.visible
        if (pauseModel.visible) pauseButton.text = pauseModel.text

        when (resolveFocusTarget(TvUtils.isTvDevice(context), focusPolicyRequested, pauseModel.visible)) {
            FocusTarget.PAUSE -> {
                pauseButton.requestFocus()
                pauseActionFocusPending = false
            }
            FocusTarget.START -> {
                connectButton.requestFocus()
            }
            FocusTarget.NONE -> Unit
        }
    }

    private fun updateDurationTimer() {
        val duration = presenter.formatDuration(
            state = runtime.state.value,
            connectionStartTimeMs = runtime.connectionStartTimeMs.value
        )
        connectionDetailsListener?.updateDuration(duration)
    }

    private fun updateTraffic(downloaded: Long, uploaded: Long) {
        val (down, up) = presenter.formatTraffic(downloaded, uploaded)
        connectionDetailsListener?.updateTraffic(down, up)
    }

    private fun updateLocationPlaceholders() {
        updateServerPosition()
        connectionDetailsListener?.updateAddress(selectedServerIp.orEmpty())
    }

    private fun updateAddress(ip: String?) {
        selectedServerIp = ip
        connectionDetailsListener?.updateAddress(ip.orEmpty())
    }

    private fun resolveIpForConfig(config: String?): String? {
        return presenter.resolveIpForConfig(
            selectionStore = selectionStore,
            config = config,
            selectedServerIp = selectedServerIp
        )
    }

    private fun updateServerButtonIcons(showGlobe: Boolean) {
        val tint = MaterialColors.getColor(
            binding.serverSelectionContainer,
            com.google.android.material.R.attr.colorOnSurface,
            ContextCompat.getColor(context, R.color.text_color_primary)
        )
        val globe = if (showGlobe) {
            ContextCompat.getDrawable(context, R.drawable.ic_baseline_public_24)
        } else {
            null
        }
        val globeWrapped = globe?.let { DrawableCompat.wrap(it) }
        if (globeWrapped != null) {
            DrawableCompat.setTint(globeWrapped, tint)
        }

        val chevron = ContextCompat.getDrawable(context, R.drawable.ic_baseline_chevron_right_24)
        val chevronWrapped = chevron?.let { DrawableCompat.wrap(it) }
        if (chevronWrapped != null) {
            DrawableCompat.setTint(chevronWrapped, tint)
        }

        binding.serverSelectionContainer.icon = null
        binding.serverSelectionContainer.setCompoundDrawablesRelativeWithIntrinsicBounds(
            globeWrapped,
            null,
            chevronWrapped,
            null
        )
        binding.serverSelectionContainer.compoundDrawablePadding =
            resources.getDimensionPixelSize(R.dimen.server_item_margin)
    }

    private fun updateServerPosition() {
        val sync = buildServerSync()
        connectionDetailsListener?.updateCity(sync?.cityText.orEmpty())
    }

    private fun buildServerSync(): ConnectionServerSync? {
        return presenter.syncServer(
            selectionStore = selectionStore,
            selectedCountry = selectedCountry,
            selectedServerIp = selectedServerIp,
            vpnConfig = vpnConfig,
            reconnectingHint = runtime.reconnectingHint.value
        )
    }

    interface ConnectionDetailsListener {
        fun updateDuration(text: String)
        fun updateTraffic(downloaded: String, uploaded: String)
        fun updateCity(city: String)
        fun updateAddress(address: String)
        fun updateStatus(text: String)
    }
}







