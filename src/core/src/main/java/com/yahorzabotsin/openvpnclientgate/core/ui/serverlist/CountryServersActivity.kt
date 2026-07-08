package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.databinding.ActivityTemplateBinding
import com.yahorzabotsin.openvpnclientgate.core.databinding.ContentCountryServersBinding
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerSelectionResult
import com.yahorzabotsin.openvpnclientgate.core.ui.common.decor.MarginItemDecoration
import com.yahorzabotsin.openvpnclientgate.core.ui.common.navigation.TemplatePage
import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.resolve
import com.yahorzabotsin.openvpnclientgate.core.ui.common.utils.TvUtils
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class CountryServersActivity : AppCompatActivity() {

    private lateinit var templateBinding: ActivityTemplateBinding
    private lateinit var contentBinding: ContentCountryServersBinding
    private val viewModel: CountryServersViewModel by viewModel()
    private var adapter: ServerPickerAdapter? = null
    private var lastRenderedItems: List<ServerListItem> = emptyList()
    private var lastRenderedDefaultV2Source: Boolean? = null
    private var activePopupMenu: PopupMenu? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        templateBinding = TemplatePage.create(this, R.string.menu_server, null)
        contentBinding = ContentCountryServersBinding.inflate(layoutInflater, templateBinding.contentContainer, true)

        contentBinding.serversRecyclerView.layoutManager = LinearLayoutManager(this)
        contentBinding.serversRecyclerView.addItemDecoration(
            MarginItemDecoration(resources.getDimensionPixelSize(R.dimen.server_item_margin))
        )

        observeViewModel()

        viewModel.onAction(
            CountryServersAction.Initialize(
                countryName = intent.getStringExtra(EXTRA_COUNTRY_NAME),
                countryCode = intent.getStringExtra(EXTRA_COUNTRY_CODE)
            )
        )
    }

    override fun onDestroy() {
        activePopupMenu?.dismiss()
        activePopupMenu = null
        super.onDestroy()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(it) } }
                launch { viewModel.effects.collect { handleEffect(it) } }
            }
        }
    }

    private fun render(state: CountryServersUiState) {
        contentBinding.progressBar.isVisible = state.isLoading
        contentBinding.serversRecyclerView.isVisible = !state.isLoading
        state.countryName?.let { templateBinding.toolbarTitle.text = it }
        val isDefaultV2Source = UserSettingsStore.load(this).serverSource == ServerSource.DEFAULT_V2

        if (adapter == null || lastRenderedDefaultV2Source != isDefaultV2Source) {
            lastRenderedItems = state.items
            lastRenderedDefaultV2Source = isDefaultV2Source
            adapter = ServerPickerAdapter(
                items = state.items,
                isDefaultV2Source = isDefaultV2Source,
                onClick = { selected ->
                    viewModel.onAction(CountryServersAction.ServerSelected(selected))
                },
                onLongClick = { anchor, server, isFavorite ->
                    showFavoriteMenu(anchor, server, isFavorite)
                }
            )
            contentBinding.serversRecyclerView.adapter = adapter
        } else if (state.items != lastRenderedItems) {
            lastRenderedItems = state.items
            adapter?.updateItems(state.items)
        }
    }

    private fun showFavoriteMenu(anchor: android.view.View, server: Server, isFavorite: Boolean) {
        // Servers with id == 0 (legacy/un-synced) cannot be favorited — known limitation
        // carried forward from SUB-01/SUB-02.
        if (server.id <= 0) {
            return
        }
        // Gate PopupMenu to mobile only — TV story (SUB-04) will handle D-pad dialog UI
        if (TvUtils.isTvDevice(this)) {
            return
        }
        // Dismiss any previously showing popup to prevent window leaks
        activePopupMenu?.dismiss()

        val popup = PopupMenu(this, anchor)
        activePopupMenu = popup
        popup.setOnDismissListener {
            if (activePopupMenu == popup) {
                activePopupMenu = null
            }
        }
        val actionTitle = if (isFavorite) {
            getString(R.string.favorites_remove_action)
        } else {
            getString(R.string.favorites_add_action)
        }
        popup.menu.add(actionTitle)
        popup.setOnMenuItemClickListener {
            viewModel.onAction(CountryServersAction.ToggleFavorite(server))
            true
        }
        popup.show()
    }

    private fun handleEffect(effect: CountryServersEffect) {
        when (effect) {
            is CountryServersEffect.ShowToast -> {
                Toast.makeText(this, resolve(effect.text), Toast.LENGTH_SHORT).show()
            }
            is CountryServersEffect.ShowSnackbar -> {
                Snackbar.make(templateBinding.root, resolve(effect.text), Snackbar.LENGTH_LONG).show()
            }
            is CountryServersEffect.FinishWithSelection -> finishWithSelection(effect.result)
            CountryServersEffect.FinishCanceled -> finishWithCancel()
            is CountryServersEffect.FocusFirstItem -> focusAdapterPosition(effect.adapterPosition)
        }
    }

    private fun focusAdapterPosition(position: Int) {
        // Scroll first: findViewHolderForAdapterPosition returns null for a position
        // RecyclerView hasn't bound yet because it's off-screen.
        contentBinding.serversRecyclerView.scrollToPosition(position)
        focusAdapterPositionWhenReady(position, attemptsLeft = 10)
    }

    private fun focusAdapterPositionWhenReady(position: Int, attemptsLeft: Int) {
        contentBinding.serversRecyclerView.post {
            val holder = contentBinding.serversRecyclerView.findViewHolderForAdapterPosition(position)
            if (holder != null) {
                holder.itemView.requestFocus()
            } else if (attemptsLeft > 0) {
                focusAdapterPositionWhenReady(position, attemptsLeft - 1)
            }
        }
    }

    private fun finishWithSelection(result: ServerSelectionResult) {
        val resultIntent = Intent().apply {
            putExtra(EXTRA_SELECTED_SERVER_COUNTRY, result.countryName)
            putExtra(EXTRA_SELECTED_SERVER_COUNTRY_CODE, result.countryCode)
            putExtra(EXTRA_SELECTED_SERVER_CITY, result.city)
            putExtra(EXTRA_SELECTED_SERVER_CONFIG, result.config)
            putExtra(EXTRA_SELECTED_SERVER_IP, result.ip)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun finishWithCancel() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    companion object {
        const val EXTRA_SELECTED_SERVER_COUNTRY = "EXTRA_SELECTED_SERVER_COUNTRY"
        const val EXTRA_SELECTED_SERVER_COUNTRY_CODE = "EXTRA_SELECTED_SERVER_COUNTRY_CODE"
        const val EXTRA_SELECTED_SERVER_CITY = "EXTRA_SELECTED_SERVER_CITY"
        const val EXTRA_SELECTED_SERVER_CONFIG = "EXTRA_SELECTED_SERVER_CONFIG"
        const val EXTRA_SELECTED_SERVER_IP = "EXTRA_SELECTED_SERVER_IP"
        const val EXTRA_COUNTRY_NAME = "EXTRA_COUNTRY_NAME"
        const val EXTRA_COUNTRY_CODE = "EXTRA_COUNTRY_CODE"
    }
}
