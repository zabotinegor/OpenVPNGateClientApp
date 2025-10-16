package com.yahorzabotsin.openvpnclient.mobile

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.yahorzabotsin.openvpnclient.core.R as coreR
import com.yahorzabotsin.openvpnclient.core.servers.Server
import com.yahorzabotsin.openvpnclient.core.servers.ServerRepository
import com.yahorzabotsin.openvpnclient.core.ui.MarginItemDecoration
import com.yahorzabotsin.openvpnclient.core.ui.ServerAdapter
import kotlinx.coroutines.launch

class ServerActivity : AppCompatActivity() {
    private val serverRepository = ServerRepository()
    private lateinit var servers: List<Server>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server)

        findViewById<ImageButton>(R.id.back_button).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        val recyclerView = findViewById<RecyclerView>(R.id.servers_recycler_view)
        val progressBar = findViewById<ProgressBar>(R.id.progress_bar)
        val countrySpinner = findViewById<Spinner>(R.id.country_spinner)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.addItemDecoration(MarginItemDecoration(resources.getDimensionPixelSize(coreR.dimen.server_item_margin)))

        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            countrySpinner.visibility = View.GONE
            try {
                servers = serverRepository.getServers()
                val countries = mutableListOf("All")
                countries.addAll(servers.map { it.country.name }.distinct())
                val adapter = ArrayAdapter(this@ServerActivity, android.R.layout.simple_spinner_item, countries)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                countrySpinner.adapter = adapter

                countrySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                        val selectedCountry = parent.getItemAtPosition(position).toString()
                        if (selectedCountry == "All") {
                            recyclerView.adapter = ServerAdapter(servers)
                        } else {
                            val filteredServers = servers.filter { it.country.name == selectedCountry }
                            recyclerView.adapter = ServerAdapter(filteredServers)
                        }
                    }

                    override fun onNothingSelected(parent: AdapterView<*>) {
                        // Do nothing
                    }
                }

                recyclerView.adapter = ServerAdapter(servers)
                progressBar.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                countrySpinner.visibility = View.VISIBLE
            } catch (e: Exception) {
                Log.e("ServerActivity", "Error getting servers", e)
                progressBar.visibility = View.GONE
                Snackbar.make(findViewById<View>(android.R.id.content), coreR.string.error_getting_servers, Snackbar.LENGTH_LONG).show()
            }
        }
    }
}