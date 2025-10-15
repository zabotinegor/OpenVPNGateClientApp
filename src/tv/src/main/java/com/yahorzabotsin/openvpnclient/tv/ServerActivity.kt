package com.yahorzabotsin.openvpnclient.tv

import android.app.Activity
import android.os.Bundle
import android.view.View
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

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        findViewById<View>(R.id.back_button).setOnClickListener {
            setResult(Activity.RESULT_OK)
            finish()
        }

        val servers = serverRepository.getServers()

        val recyclerView = findViewById<RecyclerView>(R.id.servers_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = ServerAdapter(servers)
        recyclerView.addItemDecoration(MarginItemDecoration(resources.getDimensionPixelSize(coreR.dimen.server_item_margin)))
    }

    override fun onBackPressed() {
        setResult(Activity.RESULT_OK)
        super.onBackPressed()
    }
}