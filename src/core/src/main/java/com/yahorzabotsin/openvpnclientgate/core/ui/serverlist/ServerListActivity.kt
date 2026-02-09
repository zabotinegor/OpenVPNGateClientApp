package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import android.app.Activity
import android.content.Intent
import android.os.Bundle
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
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerSelectionResult
import com.yahorzabotsin.openvpnclientgate.core.ui.common.decor.MarginItemDecoration
import com.yahorzabotsin.openvpnclientgate.core.ui.common.navigation.TemplatePage
import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.UiText
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

open class ServerListActivity : AppCompatActivity() {

    protected lateinit var templateBinding: ActivityTemplateBinding
    private val viewModel: ServerListViewModel by viewModel()
    private lateinit var contentBinding: ContentServerListBinding
    private var adapter: CountryListAdapter? = null
    private var lastRenderedCountries: List<CountryWithServers> = emptyList()
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

        if (adapter == null || state.countries != lastRenderedCountries) {
            lastRenderedCountries = state.countries
            adapter = CountryListAdapter(state.countries) { selected ->
                viewModel.onAction(ServerListAction.CountrySelected(selected))
            }
            contentBinding.serversRecyclerView.adapter = adapter
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
            ServerListEffect.FocusFirstItem -> focusFirstItem()
        }
    }

    private fun focusFirstItem() {
        focusAdapterPositionWhenReady(position = 0, attemptsLeft = 10)
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

    private fun resolve(text: UiText): String = when (text) {
        is UiText.Plain -> text.value
        is UiText.Res -> getString(text.resId, *text.args.toTypedArray())
    }

    companion object {
        const val EXTRA_SELECTED_SERVER_COUNTRY = "EXTRA_SELECTED_SERVER_COUNTRY"
        const val EXTRA_SELECTED_SERVER_COUNTRY_CODE = "EXTRA_SELECTED_SERVER_COUNTRY_CODE"
        const val EXTRA_SELECTED_SERVER_CITY = "EXTRA_SELECTED_SERVER_CITY"
        const val EXTRA_SELECTED_SERVER_CONFIG = "EXTRA_SELECTED_SERVER_CONFIG"
        const val EXTRA_SELECTED_SERVER_IP = "EXTRA_SELECTED_SERVER_IP"
    }
}



