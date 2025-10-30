package com.yahorzabotsin.openvpnclient.mobile

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import com.yahorzabotsin.openvpnclient.core.ui.BaseServerListActivity
import com.yahorzabotsin.openvpnclient.mobile.databinding.ActivityMainBinding
import com.yahorzabotsin.openvpnclient.vpn.VpnManager
import kotlinx.coroutines.launch
import com.yahorzabotsin.openvpnclient.core.R as coreR
import com.yahorzabotsin.openvpnclient.core.servers.ServerRepository
import com.yahorzabotsin.openvpnclient.core.servers.SelectedCountryStore

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val serverRepository = ServerRepository()
    private val TAG = MainActivity::class.simpleName

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.i(TAG, "VPN permission granted.")
            // Permission granted, now we can start the VPN
            // The config should be already set in the ConnectionControlsView
            // We trigger the click again, this time permission is granted
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

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.i(TAG, "Notification permission granted.")
            // Re-trigger the connection flow now that permission is granted
            binding.connectionControls.performConnectionClick()
        } else {
            Log.w(TAG, "Notification permission not granted by user.")
            val shouldShow = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            } else true
            if (!shouldShow) {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = android.net.Uri.fromParts("package", packageName, null)
                startActivity(intent)
            } else {
                Toast.makeText(this, "Notification permission is required for VPN status", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called.")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Provide mobile-specific notification builder
        VpnManager.notificationProvider = MobileNotificationProvider()

        setupConnectionControls()
        setupToolbarAndDrawer()
        setupNavigationView()
        loadSelectedCountryOrDefault()
    }

    private fun setupConnectionControls() {
        binding.connectionControls.setLifecycleOwner(this)
        binding.connectionControls.setVpnPermissionRequestHandler {
            Log.d(TAG, "Requesting VPN permission.")
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnPermissionLauncher.launch(intent)
            } else {
                // If prepare() returns null, it means permission is already granted.
                // We can directly trigger the connection logic.
                Log.d(TAG, "VPN permission already granted, triggering connection directly.")
                binding.connectionControls.performConnectionClick()
            }
        }
        binding.connectionControls.setNotificationPermissionRequestHandler {
            Log.d(TAG, "Requesting POST_NOTIFICATIONS permission.")
            // Only request on Android 13+
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // Not needed, continue flow
                binding.connectionControls.performConnectionClick()
            }
        }
    }

    private fun setupToolbarAndDrawer() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        val toggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            coreR.string.navigation_drawer_open,
            coreR.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        binding.menuButton.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    private fun setupNavigationView() {
        binding.navView.setNavigationItemSelectedListener {
            when (it.itemId) {
                coreR.id.nav_server -> {
                    Log.d(TAG, "Server list navigation item clicked.")
                    serverListActivityLauncher.launch(Intent(this, ServerListActivityMobile::class.java))
                }
                else -> {
                    Toast.makeText(this, "Feature in Development", Toast.LENGTH_SHORT).show()
                }
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun loadSelectedCountryOrDefault() {
        lifecycleScope.launch {
            Log.d(TAG, "Loading selection or default server...")
            try {
                val stored = SelectedCountryStore.currentServer(this@MainActivity)
                if (stored != null) {
                    val country = SelectedCountryStore.getSelectedCountry(this@MainActivity) ?: ""
                    Log.i(TAG, "Loaded stored selection: $country, ${stored.city}")
                    binding.connectionControls.setServer(country, stored.city)
                    binding.connectionControls.setVpnConfig(stored.config)
                } else {
                    val servers = serverRepository.getServers()
                    servers.firstOrNull()?.let { defaultServer ->
                        Log.i(TAG, "Default server loaded: ${defaultServer.country.name}, ${defaultServer.city}")
                        binding.connectionControls.setServer(defaultServer.country.name, defaultServer.city)
                        binding.connectionControls.setVpnConfig(defaultServer.configData)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch default server", e)
            }
        }
    }

    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
}
