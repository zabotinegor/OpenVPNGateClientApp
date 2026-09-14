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
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.databinding.ActivityTemplateBinding
import com.yahorzabotsin.openvpnclientgate.core.databinding.ContentCountryServersBinding
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerSelectionResult
import com.yahorzabotsin.openvpnclientgate.core.ui.common.decor.FavoritesSectionCardDecoration
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
    private var activeTvFavoriteDialog: androidx.appcompat.app.AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        templateBinding = TemplatePage.create(this, R.string.menu_server, null)
        contentBinding = ContentCountryServersBinding.inflate(layoutInflater, templateBinding.contentContainer, true)

        contentBinding.serversRecyclerView.layoutManager = LinearLayoutManager(this)
        contentBinding.serversRecyclerView.addItemDecoration(
            MarginItemDecoration(resources.getDimensionPixelSize(R.dimen.server_item_margin))
        )
        // Filled card drawn purely from adapter.pinnedSectionItemCount(); returns 0
        // (no drawing) whenever the pinned Favorites section is hidden.
        contentBinding.serversRecyclerView.addItemDecoration(
            FavoritesSectionCardDecoration(this) { adapter?.pinnedSectionItemCount() ?: 0 }
        )
        // Fires for both touch fling and D-pad-driven scroll (RecyclerView's
        // scroll callback is input-method agnostic — a D-pad focus move that scrolls the list
        // to bring the next row into view goes through the same onScrolled path).
        contentBinding.serversRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                maybeLoadNextPage(recyclerView)
            }
        })

        observeViewModel()

        viewModel.onAction(
            CountryServersAction.Initialize(
                countryName = intent.getStringExtra(EXTRA_COUNTRY_NAME),
                countryCode = intent.getStringExtra(EXTRA_COUNTRY_CODE),
                pageSize = computeServerPageSize()
            )
        )
    }

    /** Page size derived from the device's real screen dimensions and the server
     * row's measured/laid-out height — no hardcoded item-count constant. Safe to call before
     * the RecyclerView has been laid out (`onCreate`): falls back to the display width when
     * the view's own width isn't known yet. See [ServerListPageSizeCalculator]. */
    private fun computeServerPageSize(): Int = ServerListPageSizeCalculator.compute(
        parent = contentBinding.serversRecyclerView,
        rowLayoutResId = R.layout.item_server_row,
        screenHeightPx = resources.displayMetrics.heightPixels,
        screenWidthPx = resources.displayMetrics.widthPixels
    )

    /** Triggers the next page fetch once the user has scrolled within
     * [LOAD_MORE_TRIGGER_THRESHOLD] rows of the currently loaded end. This is a scroll-trigger
     * distance, not a page-size constant — it only decides *when* to ask for more, never
     * *how many* servers a page contains. The ViewModel itself is idempotent against repeated
     * triggers (no-ops while already loading or once the list is complete), so no local
     * debouncing state is needed here. */
    private fun maybeLoadNextPage(recyclerView: RecyclerView) {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val itemCount = recyclerView.adapter?.itemCount ?: 0
        if (itemCount == 0) return
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        if (lastVisible == RecyclerView.NO_POSITION) return
        if (lastVisible >= itemCount - LOAD_MORE_TRIGGER_THRESHOLD) {
            viewModel.onAction(CountryServersAction.LoadNextPage)
        }
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
                },
                onRetryLoadMore = {
                    viewModel.onAction(CountryServersAction.RetryLoadNextPage)
                }
            )
            contentBinding.serversRecyclerView.adapter = adapter
        } else if (state.items != lastRenderedItems) {
            lastRenderedItems = state.items
            adapter?.updateItems(state.items)
        }
    }

    private fun showFavoriteMenu(anchor: android.view.View, server: Server, isFavorite: Boolean) {
        when (FavoriteActionDialog.resolvePresentation(
            isTvDevice = TvUtils.isTvDevice(this),
            // Servers with id == 0 (legacy/un-synced) cannot be favorited — known limitation
            // carried forward from legacy limitations.
            canFavorite = server.id > 0
        )) {
            FavoriteActionDialog.Presentation.NONE -> return
            FavoriteActionDialog.Presentation.TV_DIALOG -> {
                showTvFavoriteDialog(server, isFavorite)
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
            viewModel.onAction(CountryServersAction.ToggleFavorite(server))
            true
        }
        popup.show()
    }

    /**
     * TV (D-pad) presentation of the favorites toggle: a self-contained, remote-navigable
     * AlertDialog opened by holding OK/center on a focused row. Short-press
     * select/connect behavior is untouched — this only runs on long-press.
     */
    private fun showTvFavoriteDialog(server: Server, isFavorite: Boolean) {
        // Dismiss any previously showing dialog to prevent window leaks
        activeTvFavoriteDialog?.dismiss()
        val dialog = FavoriteActionDialog.show(
            activity = this,
            itemTitle = tvFavoriteDialogTitle(server.city, server.ip),
            isFavorite = isFavorite,
            onToggle = { viewModel.onAction(CountryServersAction.ToggleFavorite(server)) }
        )
        // show() returns null when the Activity is finishing/destroyed (BadTokenException guard)
        activeTvFavoriteDialog = dialog
        dialog?.setOnDismissListener {
            if (activeTvFavoriteDialog == dialog) {
                activeTvFavoriteDialog = null
            }
        }
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
        // TV-gated scroll+focus; skipped on touch devices
        // (DEF-sub03-header-misscroll-on-open). See TvUtils.applyFocusFirstItem.
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

    private fun finishWithCancel() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    companion object {
        /**
         * TV favorite-dialog title for a server row: the city when present, otherwise the
         * IP — mirrors the row-title fallback used by [ServerPickerAdapter]. Extracted as a
         * testable seam (same rationale as [TvUtils.applyFocusFirstItem]).
         */
        internal fun tvFavoriteDialogTitle(city: String, ip: String): String =
            city.trim().ifEmpty { ip }

        const val EXTRA_SELECTED_SERVER_COUNTRY = "EXTRA_SELECTED_SERVER_COUNTRY"
        const val EXTRA_SELECTED_SERVER_COUNTRY_CODE = "EXTRA_SELECTED_SERVER_COUNTRY_CODE"
        const val EXTRA_SELECTED_SERVER_CITY = "EXTRA_SELECTED_SERVER_CITY"
        const val EXTRA_SELECTED_SERVER_CONFIG = "EXTRA_SELECTED_SERVER_CONFIG"
        const val EXTRA_SELECTED_SERVER_IP = "EXTRA_SELECTED_SERVER_IP"
        const val EXTRA_COUNTRY_NAME = "EXTRA_COUNTRY_NAME"
        const val EXTRA_COUNTRY_CODE = "EXTRA_COUNTRY_CODE"

        /** Row-count distance from the loaded end that triggers the next page fetch —
         * a scroll-trigger threshold, not the page size itself (see [maybeLoadNextPage] doc). */
        private const val LOAD_MORE_TRIGGER_THRESHOLD = 5
    }
}
