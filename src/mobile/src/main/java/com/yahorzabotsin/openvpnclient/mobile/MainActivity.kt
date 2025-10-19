package com.yahorzabotsin.openvpnclient.mobile

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.yahorzabotsin.openvpnclient.core.R as coreR
import com.yahorzabotsin.openvpnclient.core.servers.ServerRepository
import com.yahorzabotsin.openvpnclient.core.ui.BaseServerListActivity
import com.yahorzabotsin.openvpnclient.core.ui.ConnectionControlsView
import com.yahorzabotsin.openvpnclient.vpn.VpnManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var connectionControls: ConnectionControlsView
    private val serverRepository = ServerRepository()
    private val tag = "MainActivity"

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Permission granted, now we can start the VPN
            // The config should be already set in the ConnectionControlsView
            // We need to trigger the click again, but this time permission is granted
            connectionControls.findViewById<android.view.View>(coreR.id.start_connection_button).performClick()
        } else {
            Toast.makeText(this, "VPN permission was not granted", Toast.LENGTH_SHORT).show()
        }
    }

    private val serverListActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val country = result.data?.getStringExtra(BaseServerListActivity.EXTRA_SELECTED_SERVER_COUNTRY)
            val city = result.data?.getStringExtra(BaseServerListActivity.EXTRA_SELECTED_SERVER_CITY)
            val config = result.data?.getStringExtra(BaseServerListActivity.EXTRA_SELECTED_SERVER_CONFIG)
            if (country != null && city != null && config != null) {
                connectionControls.setServer(country, city)
                connectionControls.setVpnConfig(config)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawer_layout)
        connectionControls = findViewById(R.id.connection_controls)
        connectionControls.setLifecycleOwner(this)
        connectionControls.setVpnPermissionRequestHandler {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnPermissionLauncher.launch(intent)
            }
        }

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        val toggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            coreR.string.navigation_drawer_open,
            coreR.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        findViewById<android.view.View>(R.id.menu_button).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        val navigationView = findViewById<NavigationView>(R.id.nav_view)
        navigationView.setNavigationItemSelectedListener {
            when (it.itemId) {
                coreR.id.nav_server -> {
                    serverListActivityLauncher.launch(Intent(this, ServerListActivityMobile::class.java))
                }
                else -> {
                    Toast.makeText(this, "Feature in Development", Toast.LENGTH_SHORT).show()
                }
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        lifecycleScope.launch {
            try {
                val servers = serverRepository.getServers()
                servers.firstOrNull()?.let { defaultServer ->
                    connectionControls.setServer(defaultServer.country.name, defaultServer.city)
                    connectionControls.setVpnConfig(defaultServer.configData)
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to fetch default server", e)
            }
        }
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
}