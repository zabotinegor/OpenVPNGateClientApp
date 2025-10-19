package com.yahorzabotsin.openvpnclient.core.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.databinding.ActivityServerListBinding
import com.yahorzabotsin.openvpnclient.core.servers.Server
import com.yahorzabotsin.openvpnclient.core.servers.ServerRepository
import kotlinx.coroutines.launch

abstract class BaseServerListActivity : AppCompatActivity() {

    private val serverRepository = ServerRepository()
    private lateinit var servers: List<Server>
    private lateinit var binding: ActivityServerListBinding
    private val TAG = BaseServerListActivity::class.simpleName

    private val serverAdapter by lazy {
        ServerAdapter { server ->
            Log.d(TAG, "Server selected: ${server.city}, ${server.country.name}")
            val resultIntent = Intent().apply {
                putExtra(EXTRA_SELECTED_SERVER_COUNTRY, server.country.name)
                putExtra(EXTRA_SELECTED_SERVER_CITY, server.city)
                putExtra(EXTRA_SELECTED_SERVER_CONFIG, server.configData)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    private val countrySelectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val selectedCountry = result.data?.getStringExtra(getCountryListActivityExtraName()) ?: getString(R.string.all_countries)
            Log.d(TAG, "Country selected: $selectedCountry")
            binding.selectedCountryText.text = selectedCountry
            filterServers(selectedCountry)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")
        binding = ActivityServerListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbarAndBackButton()
        setupRecyclerView()

        binding.countryFilterButton.setOnClickListener {
            Log.d(TAG, "Country filter button clicked")
            if (::servers.isInitialized) {
                val intent = createCountryListIntent(
                    ArrayList(servers.map { it.country.name }.distinct()),
                    binding.selectedCountryText.text.toString()
                )
                countrySelectionLauncher.launch(intent)
            }
        }

        lifecycleScope.launch {
            setLoadingState(true)
            try {
                servers = serverRepository.getServers()
                Log.i(TAG, "Successfully loaded ${servers.size} servers.")
                filterServers(getString(R.string.all_countries))
                setLoadingState(false)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting servers", e)
                setLoadingState(false)
                Snackbar.make(binding.root, R.string.error_getting_servers, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun setLoadingState(isLoading: Boolean) {
        binding.progressBar.isVisible = isLoading
        binding.serversRecyclerView.isVisible = !isLoading
        binding.countryFilterButton.isVisible = !isLoading
    }

    private fun setupRecyclerView() {
        binding.serversRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.serversRecyclerView.adapter = serverAdapter
        binding.serversRecyclerView.addItemDecoration(MarginItemDecoration(resources.getDimensionPixelSize(R.dimen.server_item_margin)))
    }

    private fun filterServers(country: String) {
        Log.d(TAG, "Filtering servers for country: '$country'")
        val filteredServers = if (country == getString(R.string.all_countries)) {
            servers
        } else {
            servers.filter { it.country.name == country }
        }
        serverAdapter.updateServers(filteredServers)
    }

    abstract fun setupToolbarAndBackButton()
    abstract fun createCountryListIntent(countries: ArrayList<String>, currentCountry: String): Intent
    abstract fun getCountryListActivityExtraName(): String

    companion object {
        const val EXTRA_SELECTED_SERVER_COUNTRY = "EXTRA_SELECTED_SERVER_COUNTRY"
        const val EXTRA_SELECTED_SERVER_CITY = "EXTRA_SELECTED_SERVER_CITY"
        const val EXTRA_SELECTED_SERVER_CONFIG = "EXTRA_SELECTED_SERVER_CONFIG"
    }
}