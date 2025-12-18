package com.yahorzabotsin.openvpnclient.core.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.databinding.ActivityTemplateBinding
import com.yahorzabotsin.openvpnclient.core.databinding.ContentServerListBinding
import com.yahorzabotsin.openvpnclient.core.servers.Server
import com.yahorzabotsin.openvpnclient.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclient.core.servers.ServerRepository
import kotlinx.coroutines.launch
import android.widget.Toast

open class ServerListActivity : AppCompatActivity() {

    private val serverRepository = ServerRepository()
    private lateinit var servers: List<Server>
    private lateinit var templateBinding: ActivityTemplateBinding
    private lateinit var contentBinding: ContentServerListBinding
    private val TAG = ServerListActivity::class.simpleName

    private var countries: List<CountryWithServers> = emptyList()
    private val REQUEST_PICK_SERVER = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")
        templateBinding = ActivityTemplateBinding.inflate(layoutInflater)
        setContentView(templateBinding.root)

        // Inflate page content into the template container
        contentBinding = ContentServerListBinding.inflate(layoutInflater, templateBinding.contentContainer, true)

        setupToolbarAndBackButton()
        setupRecyclerView()
        contentBinding.refreshFab.setOnClickListener { loadServers(forceRefresh = true) }

        loadServers(forceRefresh = false)
    }

    private fun setLoadingState(isLoading: Boolean) {
        contentBinding.progressBar.isVisible = isLoading
        contentBinding.serversRecyclerView.isVisible = !isLoading
        contentBinding.refreshFab.isEnabled = !isLoading
    }

    private fun setupRecyclerView() {
        contentBinding.serversRecyclerView.layoutManager = LinearLayoutManager(this)
        contentBinding.serversRecyclerView.addItemDecoration(MarginItemDecoration(resources.getDimensionPixelSize(R.dimen.server_item_margin)))
    }

    open fun setupToolbarAndBackButton() {
        // Set title and optional back-destination for the template header
        TemplatePage.setupHeader(
            activity = this,
            binding = templateBinding,
            titleResId = R.string.menu_server,
            backDestination = null
        )
    }

    private fun loadServers(forceRefresh: Boolean) {
        lifecycleScope.launch {
            setLoadingState(true)
            try {
                servers = serverRepository.getServers(this@ServerListActivity, forceRefresh)
                Log.i(TAG, "Successfully loaded ${servers.size} servers.")
                countries = servers
                    .groupBy { it.country }
                    .map { (country, serversByCountry) ->
                        CountryWithServers(country, serversByCountry.size)
                    }
                    .sortedBy { it.country.name }
                contentBinding.serversRecyclerView.adapter = CountryListAdapter(countries) { selected ->
                    handleCountrySelection(selected)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting servers", e)
                Snackbar.make(templateBinding.root, R.string.error_getting_servers, Snackbar.LENGTH_LONG).show()
            } finally {
                setLoadingState(false)
            }
        }
    }

    private fun handleCountrySelection(selected: com.yahorzabotsin.openvpnclient.core.servers.Country) {
        val countryName = selected.name
        val countryCode = selected.code
        val countryServers = servers.filter { it.country.name == countryName }
        if (countryServers.isEmpty()) {
            Log.w(TAG, "No servers found for selected country: $countryName")
            Toast.makeText(this@ServerListActivity, R.string.no_servers_for_country, Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        if (countryServers.size == 1) {
            lifecycleScope.launch {
                selectSingleServer(countryName, countryCode, countryServers.first(), countryServers)
            }
        } else {
            val intent = Intent(this, CountryServersActivity::class.java).apply {
                putExtra(CountryServersActivity.EXTRA_COUNTRY_NAME, countryName)
                putExtra(CountryServersActivity.EXTRA_COUNTRY_CODE, countryCode)
            }
            startActivityForResult(intent, REQUEST_PICK_SERVER)
        }
    }

    private suspend fun selectSingleServer(
        countryName: String,
        countryCode: String?,
        server: Server,
        countryServers: List<Server>
    ) {
        setLoadingState(true)
        try {
            val configs = serverRepository.loadConfigs(this@ServerListActivity, countryServers)
            val resolvedServers = countryServers.map { srv ->
                srv.copy(configData = configs[srv.lineIndex].orEmpty())
            }
            SelectedCountryStore.saveSelection(this@ServerListActivity, countryName, resolvedServers)
            val resolved = resolvedServers.firstOrNull { it.lineIndex == server.lineIndex } ?: resolvedServers.first()
            val resultIntent = Intent().apply {
                putExtra(EXTRA_SELECTED_SERVER_COUNTRY, countryName)
                putExtra(EXTRA_SELECTED_SERVER_COUNTRY_CODE, countryCode)
                putExtra(EXTRA_SELECTED_SERVER_CITY, resolved.city)
                putExtra(EXTRA_SELECTED_SERVER_CONFIG, resolved.configData)
                putExtra(EXTRA_SELECTED_SERVER_IP, resolved.ip)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading configs for $countryName", e)
            Snackbar.make(templateBinding.root, R.string.error_getting_servers, Snackbar.LENGTH_LONG).show()
            setResult(Activity.RESULT_CANCELED)
            setLoadingState(false)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_SERVER && resultCode == Activity.RESULT_OK) {
            setResult(Activity.RESULT_OK, data)
            finish()
        }
    }

    companion object {
        const val EXTRA_SELECTED_SERVER_COUNTRY = "EXTRA_SELECTED_SERVER_COUNTRY"
        const val EXTRA_SELECTED_SERVER_COUNTRY_CODE = "EXTRA_SELECTED_SERVER_COUNTRY_CODE"
        const val EXTRA_SELECTED_SERVER_CITY = "EXTRA_SELECTED_SERVER_CITY"
        const val EXTRA_SELECTED_SERVER_CONFIG = "EXTRA_SELECTED_SERVER_CONFIG"
        const val EXTRA_SELECTED_SERVER_IP = "EXTRA_SELECTED_SERVER_IP"
    }
}
