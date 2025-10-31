package com.yahorzabotsin.openvpnclient.core.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.databinding.ActivityMainBinding
import com.yahorzabotsin.openvpnclient.core.servers.SelectionBootstrap
import com.yahorzabotsin.openvpnclient.core.servers.ServerRepository
import kotlinx.coroutines.launch

open class MainActivityCore : AppCompatActivity() {

    protected lateinit var binding: ActivityMainBinding
    protected lateinit var toolbarView: Toolbar
    protected lateinit var connectionControlsView: ConnectionControlsView
    private val serverRepository = ServerRepository()
    private val TAG = MainActivityCore::class.simpleName

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.i(TAG, "VPN permission granted.")
            connectionControlsView.performConnectionClick()
        } else {
            Log.w(TAG, "VPN permission was not granted by the user.")
            Toast.makeText(this, R.string.vpn_permission_not_granted, Toast.LENGTH_SHORT).show()
        }
    }

    private val serverListActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val country = result.data?.getStringExtra(ServerListActivity.EXTRA_SELECTED_SERVER_COUNTRY)
            val city = result.data?.getStringExtra(ServerListActivity.EXTRA_SELECTED_SERVER_CITY)
            val config = result.data?.getStringExtra(ServerListActivity.EXTRA_SELECTED_SERVER_CONFIG)

            if (country != null && city != null && config != null) {
                Log.i(TAG, "Server selected: $country, $city")
                connectionControlsView.setServer(country, city)
                connectionControlsView.setVpnConfig(config)
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
            connectionControlsView.performConnectionClick()
        } else {
            Log.w(TAG, "Notification permission not granted by user.")
            Toast.makeText(this, R.string.notification_permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called.")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Resolve views from core layout
        toolbarView = findViewById(R.id.toolbar)
        connectionControlsView = findViewById(R.id.connection_controls)

        // Center content: add SpeedometerView
        val center: FrameLayout = findViewById(R.id.main_center_container)
        if (center.childCount == 0) {
            val margin = (32 * resources.displayMetrics.density).toInt()
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { setMargins(margin, 0, margin, 0) }
            val speedometer = SpeedometerView(this, null)
            center.addView(speedometer, lp)
        }

        styleNavigationView(binding.navView)

        setupConnectionControls()
        setupToolbarAndDrawer(binding.drawerLayout)
        setupNavigationView()
        loadSelectedCountryOrDefault()

        afterViewsReady()
    }

    private fun setupConnectionControls() {
        connectionControlsView.setLifecycleOwner(this)
        connectionControlsView.setVpnPermissionRequestHandler {
            Log.d(TAG, "Requesting VPN permission.")
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnPermissionLauncher.launch(intent)
            } else {
                Log.d(TAG, "VPN permission already granted, triggering connection directly.")
                connectionControlsView.performConnectionClick()
            }
        }
        connectionControlsView.setNotificationPermissionRequestHandler {
            Log.d(TAG, "Requesting POST_NOTIFICATIONS permission.")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                connectionControlsView.performConnectionClick()
            }
        }
    }

    private fun setupToolbarAndDrawer(drawerLayout: DrawerLayout) {
        setSupportActionBar(toolbarView)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        val toggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            toolbarView,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        addDrawerExtras(drawerLayout)
    }

    private fun setupNavigationView() {
        binding.navView.setNavigationItemSelectedListener {
            when (it.itemId) {
                R.id.nav_server -> {
                    serverListActivityLauncher.launch(Intent(this, ServerListActivity::class.java))
                }
                else -> {
                    Toast.makeText(this, R.string.feature_in_development, Toast.LENGTH_SHORT).show()
                }
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun loadSelectedCountryOrDefault() {
        lifecycleScope.launch {
            try {
                SelectionBootstrap.ensureSelection(this@MainActivityCore, serverRepository::getServers) { country, city, config ->
                    connectionControlsView.setServer(country, city)
                    connectionControlsView.setVpnConfig(config)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize selection", e)
            }
        }
    }

    // Hooks for per-variant tweaks
    protected open fun styleNavigationView(nv: NavigationView) {}
    protected open fun addDrawerExtras(drawerLayout: DrawerLayout) {}
    protected open fun afterViewsReady() {}
}
