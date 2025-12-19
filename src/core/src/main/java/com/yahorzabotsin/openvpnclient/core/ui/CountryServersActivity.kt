package com.yahorzabotsin.openvpnclient.core.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.databinding.ActivityTemplateBinding
import com.yahorzabotsin.openvpnclient.core.databinding.ContentCountryServersBinding
import com.yahorzabotsin.openvpnclient.core.servers.Country
import com.yahorzabotsin.openvpnclient.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclient.core.servers.Server
import com.yahorzabotsin.openvpnclient.core.servers.ServerRepository
import kotlinx.coroutines.launch

class CountryServersActivity : AppCompatActivity() {

    private lateinit var templateBinding: ActivityTemplateBinding
    private lateinit var contentBinding: ContentCountryServersBinding
    private val serverRepository = ServerRepository()
    private var countryName: String? = null
    private var countryCode: String? = null
    private var servers: List<Server> = emptyList()
    private val TAG = CountryServersActivity::class.simpleName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        templateBinding = ActivityTemplateBinding.inflate(layoutInflater)
        setContentView(templateBinding.root)

        contentBinding = ContentCountryServersBinding.inflate(layoutInflater, templateBinding.contentContainer, true)

        countryName = intent.getStringExtra(EXTRA_COUNTRY_NAME)
        countryCode = intent.getStringExtra(EXTRA_COUNTRY_CODE)

        TemplatePage.setupHeader(
            activity = this,
            binding = templateBinding,
            titleResId = R.string.menu_server,
            backDestination = null
        )
        countryName?.let { templateBinding.toolbarTitle.text = it }

        contentBinding.serversRecyclerView.layoutManager = LinearLayoutManager(this)
        contentBinding.serversRecyclerView.addItemDecoration(
            MarginItemDecoration(resources.getDimensionPixelSize(R.dimen.server_item_margin))
        )

        loadServers()
    }

    private fun setLoadingState(isLoading: Boolean) {
        contentBinding.progressBar.isVisible = isLoading
        contentBinding.serversRecyclerView.isVisible = !isLoading
    }

    private fun loadServers() {
        val name = countryName
        if (name.isNullOrBlank()) {
            finishWithCancel()
            return
        }
        lifecycleScope.launch {
            setLoadingState(true)
            try {
                val allServers = serverRepository.getServers(this@CountryServersActivity, forceRefresh = false)
                servers = allServers.filter { it.country.name == name }
                if (servers.isEmpty()) {
                    Toast.makeText(this@CountryServersActivity, R.string.no_servers_for_country, Toast.LENGTH_SHORT).show()
                    finishWithCancel()
                    return@launch
                }
                val adapter = ServerPickerAdapter(servers) { selected ->
                    selectServer(selected)
                }
                contentBinding.serversRecyclerView.adapter = adapter
                contentBinding.serversRecyclerView.post {
                    contentBinding.serversRecyclerView.findViewHolderForAdapterPosition(0)
                        ?.itemView
                        ?.requestFocus()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading servers for $name", e)
                Snackbar.make(templateBinding.root, R.string.error_getting_servers, Snackbar.LENGTH_LONG).show()
                finishWithCancel()
            } finally {
                setLoadingState(false)
            }
        }
    }

    private fun selectServer(selected: Server) {
        lifecycleScope.launch {
            setLoadingState(true)
            try {
                val configs = serverRepository.loadConfigs(this@CountryServersActivity, servers)
                val resolvedServers = servers.map { srv ->
                    srv.copy(configData = configs[srv.lineIndex].orEmpty())
                }
                SelectedCountryStore.saveSelection(this@CountryServersActivity, selected.country.name, resolvedServers)
                val chosenResolved = resolvedServers.firstOrNull { it.lineIndex == selected.lineIndex }
                    ?: resolvedServers.first()
                try {
                    SelectedCountryStore.ensureIndexForConfig(this@CountryServersActivity, chosenResolved.configData)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to align index with chosen server", e)
                }
                val resultIntent = Intent().apply {
                    putExtra(EXTRA_SELECTED_SERVER_COUNTRY, selected.country.name)
                    putExtra(EXTRA_SELECTED_SERVER_COUNTRY_CODE, countryCode)
                    putExtra(EXTRA_SELECTED_SERVER_CITY, chosenResolved.city)
                    putExtra(EXTRA_SELECTED_SERVER_CONFIG, chosenResolved.configData)
                    putExtra(EXTRA_SELECTED_SERVER_IP, chosenResolved.ip)
                }
                setResult(Activity.RESULT_OK, resultIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error selecting server ${selected.ip}", e)
                Snackbar.make(templateBinding.root, R.string.error_getting_servers, Snackbar.LENGTH_LONG).show()
                setResult(Activity.RESULT_CANCELED)
            } finally {
                setLoadingState(false)
                finish()
            }
        }
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
