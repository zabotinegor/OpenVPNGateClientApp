package com.yahorzabotsin.openvpnclient.core.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.activity.BaseCountryListActivity
import com.yahorzabotsin.openvpnclient.core.servers.Server
import com.yahorzabotsin.openvpnclient.core.servers.ServerRepository
import kotlinx.coroutines.launch

abstract class BaseServerListActivity : AppCompatActivity() {

    private val serverRepository = ServerRepository()
    private lateinit var servers: List<Server>
    private lateinit var selectedCountryText: TextView
    private lateinit var recyclerView: RecyclerView
    private val serverAdapter by lazy {
        ServerAdapter {
            val resultIntent = Intent().apply {
                putExtra(EXTRA_SELECTED_SERVER_COUNTRY, it.country.name)
                putExtra(EXTRA_SELECTED_SERVER_CITY, it.city)
                putExtra(EXTRA_SELECTED_SERVER_CONFIG, it.configData)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    private val countrySelectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val selectedCountry = result.data?.getStringExtra(getCountryListActivityExtraName()) ?: getString(R.string.all_countries)
            selectedCountryText.text = selectedCountry
            filterServers(selectedCountry)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_list)

        setupToolbarAndBackButton()

        recyclerView = findViewById(R.id.servers_recycler_view)
        val progressBar = findViewById<ProgressBar>(R.id.progress_bar)
        val countryFilterButton = findViewById<FrameLayout>(R.id.country_filter_button)
        selectedCountryText = findViewById(R.id.selected_country_text)

        setupRecyclerView()

        countryFilterButton.setOnClickListener {
            if (::servers.isInitialized) {
                val intent = createCountryListIntent(
                    ArrayList(servers.map { it.country.name }.distinct()),
                    selectedCountryText.text.toString()
                )
                countrySelectionLauncher.launch(intent)
            }
        }

        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            countryFilterButton.visibility = View.GONE
            try {
                servers = serverRepository.getServers()
                filterServers(getString(R.string.all_countries))
                progressBar.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                countryFilterButton.visibility = View.VISIBLE
            } catch (e: Exception) {
                Log.e("BaseServerListActivity", "Error getting servers", e)
                progressBar.visibility = View.GONE
                Snackbar.make(findViewById<View>(android.R.id.content), R.string.error_getting_servers, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = serverAdapter
        recyclerView.addItemDecoration(MarginItemDecoration(resources.getDimensionPixelSize(R.dimen.server_item_margin)))
    }

    private fun filterServers(country: String) {
        if (country == getString(R.string.all_countries)) {
            serverAdapter.updateServers(servers)
        } else {
            val filteredServers = servers.filter { it.country.name == country }
            serverAdapter.updateServers(filteredServers)
        }
    }

    abstract fun setupToolbarAndBackButton()
    abstract fun createCountryListIntent(countries: ArrayList<String>, currentCountry: String): Intent
    abstract fun getCountryListActivityExtraName(): String

    override fun onBackPressed() {
        super.onBackPressed()
    }

    companion object {
        const val EXTRA_SELECTED_SERVER_COUNTRY = "EXTRA_SELECTED_SERVER_COUNTRY"
        const val EXTRA_SELECTED_SERVER_CITY = "EXTRA_SELECTED_SERVER_CITY"
        const val EXTRA_SELECTED_SERVER_CONFIG = "EXTRA_SELECTED_SERVER_CONFIG"
    }
}