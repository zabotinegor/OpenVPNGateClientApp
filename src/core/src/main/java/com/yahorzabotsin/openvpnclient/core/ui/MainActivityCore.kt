package com.yahorzabotsin.openvpnclient.core.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
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
import com.yahorzabotsin.openvpnclient.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclient.core.servers.ServerRepository
import com.yahorzabotsin.openvpnclient.vpn.ConnectionState
import com.yahorzabotsin.openvpnclient.vpn.ConnectionStateManager
import kotlinx.coroutines.launch

open class MainActivityCore : AppCompatActivity(), ConnectionControlsView.ConnectionDetailsListener {

    protected lateinit var binding: ActivityMainBinding
    protected lateinit var toolbarView: Toolbar
    protected lateinit var connectionControlsView: ConnectionControlsView
    private val serverRepository = ServerRepository()
    private val TAG = MainActivityCore::class.simpleName
    private var reopenDrawerAfterReturn = false
    private val focusRestoringDrawerListener = object : DrawerLayout.SimpleDrawerListener() {
        override fun onDrawerClosed(drawerView: View) {
            connectionControlsView.requestPrimaryFocus()
        }
    }

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
            val countryCode = result.data?.getStringExtra(ServerListActivity.EXTRA_SELECTED_SERVER_COUNTRY_CODE)
            val city = result.data?.getStringExtra(ServerListActivity.EXTRA_SELECTED_SERVER_CITY)
            val config = result.data?.getStringExtra(ServerListActivity.EXTRA_SELECTED_SERVER_CONFIG)
            val ip = result.data?.getStringExtra(ServerListActivity.EXTRA_SELECTED_SERVER_IP)

            if (country != null && city != null && config != null) {
                val total = try { SelectedCountryStore.getServers(this).size } catch (e: Exception) { Log.w(TAG, "Failed to get server count", e); -1 }
                if (total >= 0) {
                    Log.i(TAG, "Server selected: $country, $city, servers - $total, ip=${ip ?: "<none>"}")
                } else {
                    Log.i(TAG, "Server selected: $country, $city, ip=${ip ?: "<none>"}")
                }
                connectionControlsView.setServer(country, countryCode, ip)
                connectionControlsView.setVpnConfig(config)
            } else {
                Log.w(TAG, "Server selection returned with incomplete data.")
            }
        }
        if (!reopenDrawerAfterReturn) {
            connectionControlsView.requestPrimaryFocus()
        }
        // After returning, reopen the navigation menu only if requested
        if (reopenDrawerAfterReturn) {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        reopenDrawerAfterReturn = false
    }

    private fun createDrawerReopeningLauncher() =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

    private val dnsActivityLauncher = createDrawerReopeningLauncher()

    private val filterActivityLauncher = createDrawerReopeningLauncher()

    private val settingsActivityLauncher = createDrawerReopeningLauncher()

    private val aboutActivityLauncher = createDrawerReopeningLauncher()

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

        toolbarView = binding.toolbar
        connectionControlsView = binding.connectionControls

        binding.connectionDetails.speedometer.bindTo(this)

        styleNavigationView(binding.navView)

        setupConnectionControls()
        setupToolbarAndDrawer(binding.drawerLayout)
        setupNavigationView()
        loadSelectedCountryOrDefault()

        afterViewsReady()

        // Close drawer on back instead of exiting app
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        // Make the country row clickable: open servers without reopening drawer on return
        connectionControlsView.setOpenServerListHandler {
            reopenDrawerAfterReturn = false
            serverListActivityLauncher.launch(Intent(this, ServerListActivity::class.java))
        }

        connectionControlsView.requestPrimaryFocus()
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            updateDetailsVisibility()
        }
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.N)
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        super.onMultiWindowModeChanged(isInMultiWindowMode)
        Log.d(TAG, "Multi-window mode changed: $isInMultiWindowMode")
        updateDetailsVisibility()
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.N)
    private fun updateDetailsVisibility() {
        binding.connectionDetails.detailsContainer?.visibility = if (isInMultiWindowMode) View.GONE else View.VISIBLE
    }

    private fun setupConnectionControls() {
        connectionControlsView.setLifecycleOwner(this)
        connectionControlsView.setConnectionDetailsListener(this)
        connectionControlsView.setVpnPermissionRequestHandler {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnPermissionLauncher.launch(intent)
            } else {
                connectionControlsView.performConnectionClick()
            }
        }
        connectionControlsView.setNotificationPermissionRequestHandler {
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
        drawerLayout.addDrawerListener(focusRestoringDrawerListener)
        toggle.syncState()

        addDrawerExtras(drawerLayout)
    }

    private fun setupNavigationView() {
        binding.navView.setNavigationItemSelectedListener {
            when (it.itemId) {
                R.id.nav_server -> {
                    reopenDrawerAfterReturn = true
                    serverListActivityLauncher.launch(Intent(this, ServerListActivity::class.java))
                }
                R.id.nav_dns -> dnsActivityLauncher.launch(Intent(this, DnsActivity::class.java))
                R.id.nav_filter -> filterActivityLauncher.launch(Intent(this, FilterActivity::class.java))
                R.id.nav_settings -> settingsActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
                R.id.nav_about -> aboutActivityLauncher.launch(Intent(this, AboutActivity::class.java))
                else -> {
                    Toast.makeText(this, R.string.feature_in_development, Toast.LENGTH_SHORT).show()
                }
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    override fun onDestroy() {
        if (::binding.isInitialized) {
            binding.drawerLayout.removeDrawerListener(focusRestoringDrawerListener)
        }
        super.onDestroy()
    }

    private fun loadSelectedCountryOrDefault() {
        lifecycleScope.launch {
            try {
                SelectionBootstrap.ensureSelection(
                    this@MainActivityCore,
                    {
                        val cacheOnly = ConnectionStateManager.state.value == ConnectionState.CONNECTED
                        serverRepository.getServers(this@MainActivityCore, cacheOnly = cacheOnly)
                    },
                    { srv -> serverRepository.loadConfigs(this@MainActivityCore, srv) }
                ) { country, city, config, countryCode, ip ->
                    connectionControlsView.setServer(country, countryCode, ip)
                    connectionControlsView.setVpnConfig(config)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize selection", e)
            }
        }
    }

    protected open fun styleNavigationView(nv: NavigationView) {}
    protected open fun addDrawerExtras(drawerLayout: DrawerLayout) {}
    protected open fun afterViewsReady() {}

    override fun updateDuration(text: String) {
        binding.connectionDetails.durationValue.text = text
    }

    override fun updateTraffic(downloaded: String, uploaded: String) {
        binding.connectionDetails.downloadedValue.text = downloaded
        binding.connectionDetails.uploadedValue.text = uploaded
    }

    override fun updateCity(city: String) {
        binding.connectionDetails.cityValue.text = city
    }

    override fun updateAddress(address: String) {
        binding.connectionDetails.addressValue.text = address
    }

    override fun updateStatus(text: String) {
        binding.connectionDetails.statusValue.text = text
    }
}
