package com.yahorzabotsin.openvpnclient.tv

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import com.yahorzabotsin.openvpnclient.core.servers.ServerRepository
import com.yahorzabotsin.openvpnclient.core.ui.BaseServerListActivity
import com.yahorzabotsin.openvpnclient.tv.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import com.yahorzabotsin.openvpnclient.core.R as coreR

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val serverRepository = ServerRepository()
    private val TAG = MainActivity::class.simpleName
    private var selectedMenuItemId: Int = coreR.id.nav_server

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.i(TAG, "VPN permission granted.")
            binding.connectionControls.performConnectionClick()
        } else {
            Log.w(TAG, "VPN permission was not granted by the user.")
            Toast.makeText(this, "VPN permission was not granted", Toast.LENGTH_SHORT).show()
        }
    }

    private val serverListActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val country = result.data?.getStringExtra(BaseServerListActivity.EXTRA_SELECTED_SERVER_COUNTRY)
            val city = result.data?.getStringExtra(BaseServerListActivity.EXTRA_SELECTED_SERVER_CITY)
            val config = result.data?.getStringExtra(BaseServerListActivity.EXTRA_SELECTED_SERVER_CONFIG)

            if (country != null && city != null && config != null) {
                Log.i(TAG, "Server selected: $country, $city")
                binding.connectionControls.setServer(country, city)
                binding.connectionControls.setVpnConfig(config)
            } else {
                Log.w(TAG, "Server selection returned with incomplete data.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called.")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupConnectionControls()
        setupDrawer()
        setupListeners()

        binding.navView.setCheckedItem(selectedMenuItemId)
        fetchDefaultServer()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.setDisplayShowTitleEnabled(false)
    }

    private fun setupConnectionControls() {
        binding.connectionControls.setLifecycleOwner(this)
        binding.connectionControls.setVpnPermissionRequestHandler {
            Log.d(TAG, "Requesting VPN permission.")
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnPermissionLauncher.launch(intent)
            } else {
                Log.d(TAG, "VPN permission already granted, triggering connection directly.")
                binding.connectionControls.performConnectionClick()
            }
        }
    }

    private fun setupDrawer() {
        val toggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            coreR.string.navigation_drawer_open,
            coreR.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
    }

    private fun setupListeners() {
        binding.menuButton.setOnClickListener {
            Log.d(TAG, "Menu button clicked, opening drawer.")
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        binding.drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}

            override fun onDrawerOpened(drawerView: View) {
                Log.d(TAG, "Drawer opened, focusing on selected item.")
                binding.navView.setCheckedItem(selectedMenuItemId)
                binding.navView.post {
                    val viewToFocus = binding.navView.findViewById<View>(selectedMenuItemId)
                    if (viewToFocus != null) {
                        viewToFocus.requestFocus()
                    } else {
                        Log.w(TAG, "Could not find view with ID $selectedMenuItemId to focus.")
                    }
                }
            }

            override fun onDrawerClosed(drawerView: View) {
                Log.d(TAG, "Drawer closed, focusing on connection controls.")
                binding.connectionControls.requestFocus()
            }

            override fun onDrawerStateChanged(newState: Int) {}
        })

        binding.navView.setNavigationItemSelectedListener {
            selectedMenuItemId = it.itemId
            Log.d(TAG, "Navigation item selected: ${it.title}")
            when (it.itemId) {
                coreR.id.nav_server -> {
                    serverListActivityLauncher.launch(ServerListActivityTV.newIntent(this))
                }
                else -> {
                    Toast.makeText(this, coreR.string.feature_in_development, Toast.LENGTH_SHORT).show()
                }
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun fetchDefaultServer() {
        lifecycleScope.launch {
            Log.d(TAG, "Fetching default server...")
            try {
                val servers = serverRepository.getServers()
                servers.firstOrNull()?.let { defaultServer ->
                    Log.i(TAG, "Default server loaded: ${defaultServer.country.name}, ${defaultServer.city}")
                    binding.connectionControls.setServer(defaultServer.country.name, defaultServer.city)
                    binding.connectionControls.setVpnConfig(defaultServer.configData)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch default server", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called, requesting focus for connection controls.")
        binding.connectionControls.requestFocus()
    }

    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
}
