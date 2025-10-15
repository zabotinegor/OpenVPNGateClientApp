package com.yahorzabotsin.openvpnclient.mobile

import android.os.Bundle
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yahorzabotsin.openvpnclient.core.R as coreR
import com.yahorzabotsin.openvpnclient.core.servers.ServerRepository
import com.yahorzabotsin.openvpnclient.core.ui.MarginItemDecoration
import com.yahorzabotsin.openvpnclient.core.ui.ServerAdapter

class ServerActivity : AppCompatActivity() {
    private val serverRepository = ServerRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server)

        findViewById<ImageButton>(R.id.back_button).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        val servers = serverRepository.getServers()

        val recyclerView = findViewById<RecyclerView>(R.id.servers_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = ServerAdapter(servers)
        recyclerView.addItemDecoration(MarginItemDecoration(resources.getDimensionPixelSize(coreR.dimen.server_item_margin)))
    }
}