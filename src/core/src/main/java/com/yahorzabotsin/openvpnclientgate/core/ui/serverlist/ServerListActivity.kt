package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.databinding.ActivityTemplateBinding
import com.yahorzabotsin.openvpnclientgate.core.databinding.ContentServerListBinding
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerSelectionResult
import com.yahorzabotsin.openvpnclientgate.core.ui.common.decor.MarginItemDecoration
import com.yahorzabotsin.openvpnclientgate.core.ui.common.navigation.TemplatePage
import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.resolve
import com.yahorzabotsin.openvpnclientgate.core.ui.common.utils.TvUtils
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

open class ServerListActivity : AppCompatActivity() {

    protected lateinit var templateBinding: ActivityTemplateBinding
    private val viewModel: ServerListViewModel by viewModel()
    private lateinit var contentBinding: ContentServerListBinding
    private var adapter: CountryListAdapter? = null
    private var lastRenderedItems: List<CountryListItem> = emptyList()
    private var activePopupMenu: PopupMenu? = null
    private var activeTvFavoriteDialog: androidx.appcompat.app.AlertDialog? = null
    private val countryServersLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                setResult(Activity.RESULT_OK, result.data)
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        templateBinding = TemplatePage.create(this, R.string.menu_server, null)
        contentBinding = ContentServerListBinding.inflate(layoutInflater, templateBinding.contentContainer, true)
        setupRecyclerView()
        contentBinding.refreshFab.setOnClickListener {
            viewModel.onAction(ServerListAction.Load(forceRefresh = true))
        }
        observeViewModel()
    }

    override fun onDestroy() {
        activePopupMenu?.dismiss()
        activePopupMenu = null
        activeTvFavoriteDialog?.dismiss()
        activeTvFavoriteDialog = null
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

    private fun setupRecyclerView() {
        contentBinding.serversRecyclerView.layoutManager = LinearLayoutManager(this)
        contentBinding.serversRecyclerView.addItemDecoration(MarginItemDecoration(resources.getDimensionPixelSize(R.dimen.server_item_margin)))
    }

    private fun render(state: ServerListUiState) {
        contentBinding.progressBar.isVisible = state.isLoading
        contentBinding.serversRecyclerView.isVisible = !state.isLoading
        contentBinding.refreshFab.isEnabled = state.isRefreshEnabled
        contentBinding.refreshHint.isVisible = state.showRefreshHint

        if (adapter == null) {
            lastRenderedItems = state.items
            adapter = CountryListAdapter(
                items = state.items,
                onClick = { selected ->
                    viewModel.onAction(ServerListAction.CountrySelected(selected))
                },
                onLongClick = { anchor, country, isFavorite ->
                    showFavoriteMenu(anchor, country, isFavorite)
                }
            )
            contentBinding.serversRecyclerView.adapter = adapter
        } else if (state.items != lastRenderedItems) {
            lastRenderedItems = state.items
            adapter?.updateItems(state.items)
        }
    }

    private fun showFavoriteMenu(anchor: android.view.View, country: Country, isFavorite: Boolean) {
        when (FavoriteActionDialog.resolvePresentation(
            isTvDevice = TvUtils.isTvDevice(this),
            // Guard against blank country codes — favoriting silently does nothing, so hide the menu
            canFavorite = !country.code.isNullOrBlank()
        )) {
            FavoriteActionDialog.Presentation.NONE -> return
            FavoriteActionDialog.Presentation.TV_DIALOG -> {
                showTvFavoriteDialog(country, isFavorite)
                return
            }
            FavoriteActionDialog.Presentation.POPUP_MENU -> Unit // fall through to PopupMenu below
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
            viewModel.onAction(ServerListAction.ToggleFavorite(country))
            true
        }
        popup.show()
    }

    /**
     * TV (D-pad) presentation of the favorites toggle: a self-contained, remote-navigable
     * AlertDialog opened by holding OK/center on a focused row (SUB-04). Short-press
     * select/connect behavior is untouched — this only runs on long-press.
     */
    private fun showTvFavoriteDialog(country: Country, isFavorite: Boolean) {
        // Dismiss any previously showing dialog to prevent window leaks
        activeTvFavoriteDialog?.dismiss()
        val dialog = FavoriteActionDialog.show(
            activity = this,
            itemTitle = country.name,
            isFavorite = isFavorite,
            onToggle = { viewModel.onAction(ServerListAction.ToggleFavorite(country)) }
        )
        // show() returns null when the Activity is finishing/destroyed (BadTokenException guard)
        activeTvFavoriteDialog = dialog
        dialog?.setOnDismissListener {
            if (activeTvFavoriteDialog == dialog) {
                activeTvFavoriteDialog = null
            }
        }
    }

    private fun handleEffect(effect: ServerListEffect) {
        when (effect) {
            is ServerListEffect.ShowSnackbar -> {
                Snackbar.make(templateBinding.root, resolve(effect.text), Snackbar.LENGTH_LONG).show()
            }
            is ServerListEffect.ShowToast -> {
                Toast.makeText(this, resolve(effect.text), Toast.LENGTH_SHORT).show()
            }
            is ServerListEffect.OpenCountryServers -> {
                val intent = Intent(this, CountryServersActivity::class.java).apply {
                    putExtra(CountryServersActivity.EXTRA_COUNTRY_NAME, effect.countryName)
                    putExtra(CountryServersActivity.EXTRA_COUNTRY_CODE, effect.countryCode)
                }
                countryServersLauncher.launch(intent)
            }
            is ServerListEffect.FinishWithSelection -> finishWithSelection(effect.result)
            ServerListEffect.SetResultCanceled -> setResult(Activity.RESULT_CANCELED)
            ServerListEffect.FinishCanceled -> {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
            is ServerListEffect.FocusFirstItem -> focusAdapterPosition(effect.adapterPosition)
        }
    }

    private fun focusAdapterPosition(position: Int) {
        // TV-gated scroll+focus; skipped on touch devices
        // (DEF-sub05-serverlist-header-misscroll-on-open). See TvUtils.applyFocusFirstItem.
        TvUtils.applyFocusFirstItem(
            isTvDevice = TvUtils.isTvDevice(this),
            position = position,
            scrollToPosition = contentBinding.serversRecyclerView::scrollToPosition,
            focusWhenReady = { focusAdapterPositionWhenReady(it, attemptsLeft = 10) }
        )
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

    companion object {
        const val EXTRA_SELECTED_SERVER_COUNTRY = "EXTRA_SELECTED_SERVER_COUNTRY"
        const val EXTRA_SELECTED_SERVER_COUNTRY_CODE = "EXTRA_SELECTED_SERVER_COUNTRY_CODE"
        const val EXTRA_SELECTED_SERVER_CITY = "EXTRA_SELECTED_SERVER_CITY"
        const val EXTRA_SELECTED_SERVER_CONFIG = "EXTRA_SELECTED_SERVER_CONFIG"
        const val EXTRA_SELECTED_SERVER_IP = "EXTRA_SELECTED_SERVER_IP"
    }
}



