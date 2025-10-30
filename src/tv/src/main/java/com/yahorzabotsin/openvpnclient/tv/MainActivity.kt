package com.yahorzabotsin.openvpnclient.tv

import android.app.Activity
import android.Manifest
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
import com.yahorzabotsin.openvpnclient.core.servers.SelectionBootstrap
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

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.i(TAG, "Notification permission granted.")
            binding.connectionControls.performConnectionClick()
        } else {
            Log.w(TAG, "Notification permission not granted by user.")
            Toast.makeText(this, "Notification permission is required for VPN status", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called.")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupConnectionControls()
        setupToolbarAndDrawer()
        setupNavigationView()
        loadSelectedCountryOrDefault()

        binding.navView.setCheckedItem(selectedMenuItemId)
        binding.connectionControls.post { binding.connectionControls.requestPrimaryFocus() }
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
        binding.connectionControls.setNotificationPermissionRequestHandler {
            Log.d(TAG, "Requesting POST_NOTIFICATIONS permission (TV).")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
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
            Log.d(TAG, "Menu button clicked, opening drawer.")
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        // TV-specific focus management
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
                Log.d(TAG, "Drawer closed, focusing on connection button.")
                binding.connectionControls.requestPrimaryFocus()
            }

            override fun onDrawerStateChanged(newState: Int) {}
        })
    }

    private fun setupNavigationView() {
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

    private fun loadSelectedCountryOrDefault() {
        lifecycleScope.launch {
            try {
                SelectionBootstrap.ensureSelection(this@MainActivity, serverRepository) { country, city, config ->
                    binding.connectionControls.setServer(country, city)
                    binding.connectionControls.setVpnConfig(config)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize selection (TV)", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called, requesting focus for connection button.")
        binding.connectionControls.requestPrimaryFocus()
    }

    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
}
