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

    private var countries: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")
        templateBinding = ActivityTemplateBinding.inflate(layoutInflater)
        setContentView(templateBinding.root)

        // Inflate page content into the template container
        contentBinding = ContentServerListBinding.inflate(layoutInflater, templateBinding.contentContainer, true)

        setupToolbarAndBackButton()
        setupRecyclerView()

        lifecycleScope.launch {
            setLoadingState(true)
            try {
                servers = serverRepository.getServers()
                Log.i(TAG, "Successfully loaded ${servers.size} servers.")
                countries = servers.map { it.country.name }.distinct().sorted()
                contentBinding.serversRecyclerView.adapter = CountryListAdapter(countries) { country ->
                    Log.d(TAG, "Country selected: $country")
                    val countryServers = servers.filter { it.country.name == country }
                    if (countryServers.isNotEmpty()) {
                        SelectedCountryStore.saveSelection(this@ServerListActivity, country, countryServers)
                        val first = countryServers.first()
                        val resultIntent = Intent().apply {
                            putExtra(EXTRA_SELECTED_SERVER_COUNTRY, country)
                            putExtra(EXTRA_SELECTED_SERVER_CITY, first.city)
                            putExtra(EXTRA_SELECTED_SERVER_CONFIG, first.configData)
                        }
                        setResult(Activity.RESULT_OK, resultIntent)
                    } else {
                        Log.w(TAG, "No servers found for selected country: $country")
                        Toast.makeText(this@ServerListActivity, R.string.no_servers_for_country, Toast.LENGTH_SHORT).show()
                        setResult(Activity.RESULT_CANCELED)
                    }
                    finish()
                }
                setLoadingState(false)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting servers", e)
                setLoadingState(false)
                Snackbar.make(templateBinding.root, R.string.error_getting_servers, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun setLoadingState(isLoading: Boolean) {
        contentBinding.progressBar.isVisible = isLoading
        contentBinding.serversRecyclerView.isVisible = !isLoading
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

    companion object {
        const val EXTRA_SELECTED_SERVER_COUNTRY = "EXTRA_SELECTED_SERVER_COUNTRY"
        const val EXTRA_SELECTED_SERVER_CITY = "EXTRA_SELECTED_SERVER_CITY"
        const val EXTRA_SELECTED_SERVER_CONFIG = "EXTRA_SELECTED_SERVER_CONFIG"
    }
}
