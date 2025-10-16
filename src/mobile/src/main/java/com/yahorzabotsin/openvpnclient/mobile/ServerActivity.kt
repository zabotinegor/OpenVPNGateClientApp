package com.yahorzabotsin.openvpnclient.mobile

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.yahorzabotsin.openvpnclient.core.R as coreR
import com.yahorzabotsin.openvpnclient.core.servers.ServerRepository
import com.yahorzabotsin.openvpnclient.core.ui.MarginItemDecoration
import com.yahorzabotsin.openvpnclient.core.ui.ServerAdapter
import kotlinx.coroutines.launch

class ServerActivity : AppCompatActivity() {
    private val serverRepository = ServerRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server)

        findViewById<ImageButton>(R.id.back_button).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        val recyclerView = findViewById<RecyclerView>(R.id.servers_recycler_view)
        val progressBar = findViewById<ProgressBar>(R.id.progress_bar)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.addItemDecoration(MarginItemDecoration(resources.getDimensionPixelSize(coreR.dimen.server_item_margin)))

        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            try {
                val servers = serverRepository.getServers()
                recyclerView.adapter = ServerAdapter(servers)
                progressBar.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
            } catch (e: Exception) {
                Log.e("ServerActivity", "Error getting servers", e)
                progressBar.visibility = View.GONE
                Snackbar.make(findViewById<View>(android.R.id.content), coreR.string.error_getting_servers, Snackbar.LENGTH_LONG).show()
            }
        }
    }
}