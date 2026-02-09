package com.yahorzabotsin.openvpnclientgate.core.ui.main

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.navigation.NavigationView
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.databinding.ActivityMainBinding
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.ConnectionControlsView
import com.yahorzabotsin.openvpnclientgate.core.ui.about.AboutActivity
import com.yahorzabotsin.openvpnclientgate.core.ui.dns.DnsActivity
import com.yahorzabotsin.openvpnclientgate.core.ui.filter.FilterActivity
import com.yahorzabotsin.openvpnclientgate.core.ui.serverlist.ServerListActivity
import com.yahorzabotsin.openvpnclientgate.core.ui.settings.SettingsActivity
import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.UiText
import com.yahorzabotsin.openvpnclientgate.vpn.OpenVpnService
import com.yahorzabotsin.openvpnclientgate.vpn.VpnManager
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

open class MainActivityCore : AppCompatActivity(), ConnectionControlsView.ConnectionDetailsListener {

    protected lateinit var binding: ActivityMainBinding
    protected lateinit var toolbarView: Toolbar
    protected lateinit var connectionControlsView: ConnectionControlsView
    private val viewModel: MainViewModel by viewModel()
    private val tag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "MainActivityCore"
    private val screenLogTag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "ScreenFlow"
    private val focusRestoringDrawerListener = object : DrawerLayout.SimpleDrawerListener() {
        override fun onDrawerClosed(drawerView: View) {
            connectionControlsView.requestPrimaryFocus()
        }
    }
    private var lastAppliedSelectionVersion: Long? = null

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            dispatchConnectionButtonClick()
        } else {
            Toast.makeText(this, R.string.vpn_permission_not_granted, Toast.LENGTH_SHORT).show()
        }
    }

    private val serverListActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val selection = if (result.resultCode == Activity.RESULT_OK) {
            SelectedServerResult(
                country = result.data?.getStringExtra(ServerListActivity.EXTRA_SELECTED_SERVER_COUNTRY),
                countryCode = result.data?.getStringExtra(ServerListActivity.EXTRA_SELECTED_SERVER_COUNTRY_CODE),
                city = result.data?.getStringExtra(ServerListActivity.EXTRA_SELECTED_SERVER_CITY),
                config = result.data?.getStringExtra(ServerListActivity.EXTRA_SELECTED_SERVER_CONFIG),
                ip = result.data?.getStringExtra(ServerListActivity.EXTRA_SELECTED_SERVER_IP)
            )
        } else {
            null
        }
        viewModel.onAction(MainAction.OnServerSelectionResult(selection))
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
            dispatchConnectionButtonClick()
        } else {
            Toast.makeText(this, R.string.notification_permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(tag, "onCreate called.")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        toolbarView = binding.toolbar
        connectionControlsView = binding.connectionControls

        binding.connectionDetails.speedometer.bindTo(this)
        binding.drawerLayout.setStatusBarBackground(null)
        binding.drawerLayout.setStatusBarBackgroundColor(Color.TRANSPARENT)

        styleNavigationView(binding.navView)
        setupConnectionControls()
        setupToolbarAndDrawer(binding.drawerLayout)
        setupNavigationView()
        observeViewModel()
        viewModel.onAction(MainAction.LoadInitialSelection)

        afterViewsReady()

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

        connectionControlsView.setOpenServerListHandler {
            viewModel.onAction(MainAction.OpenServerListFromConnectionControls)
        }

        connectionControlsView.requestPrimaryFocus()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            viewModel.onAction(MainAction.OnMultiWindowModeChanged(isInMultiWindowMode))
        }
    }

    override fun onStart() {
        super.onStart()
        Log.i(screenLogTag, "enter ${javaClass.simpleName}")
        try {
            startService(Intent(this, OpenVpnService::class.java))
        } catch (e: Exception) {
            Log.w(tag, "Failed to start OpenVpnService from UI", e)
        }
    }

    override fun onStop() {
        Log.i(screenLogTag, "exit ${javaClass.simpleName}")
        super.onStop()
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.N)
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        super.onMultiWindowModeChanged(isInMultiWindowMode)
        Log.d(tag, "Multi-window mode changed: $isInMultiWindowMode")
        viewModel.onAction(MainAction.OnMultiWindowModeChanged(isInMultiWindowMode))
    }

    private fun setupConnectionControls() {
        connectionControlsView.setLifecycleOwner(this)
        connectionControlsView.setConnectionDetailsListener(this)
        connectionControlsView.setConnectionButtonClickHandler {
            dispatchConnectionButtonClick()
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
            viewModel.onAction(MainAction.NavigationItemSelected(it.itemId))
            true
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(it) } }
                launch { viewModel.effects.collect { handleEffect(it) } }
            }
        }
    }

    private fun render(state: MainUiState) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            binding.connectionDetails.detailsContainer?.visibility =
                if (state.isDetailsVisible) View.VISIBLE else View.GONE
        }
        applySelectedServerIfNeeded(state.selectedServer)
    }

    private fun handleEffect(effect: MainEffect) {
        when (effect) {
            is MainEffect.OpenDestination -> openDestination(effect.destination)
            MainEffect.CloseDrawer -> binding.drawerLayout.closeDrawer(GravityCompat.START)
            MainEffect.ReopenDrawer -> binding.drawerLayout.openDrawer(GravityCompat.START)
            MainEffect.RequestPrimaryFocus -> connectionControlsView.requestPrimaryFocus()
            MainEffect.RequestVpnPermission -> {
                val intent = VpnService.prepare(this)
                if (intent != null) {
                    vpnPermissionLauncher.launch(intent)
                } else {
                    dispatchConnectionButtonClick()
                }
            }
            MainEffect.RequestNotificationPermission -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    dispatchConnectionButtonClick()
                }
            }
            is MainEffect.StartVpn -> VpnManager.startVpn(this, effect.config, effect.country)
            MainEffect.StopVpn -> VpnManager.stopVpn(this)
            is MainEffect.ShowToast -> Toast.makeText(this, resolve(effect.text), Toast.LENGTH_SHORT).show()
        }
    }

    private fun applySelectedServerIfNeeded(selection: MainSelectedServer?) {
        if (selection == null) return
        if (lastAppliedSelectionVersion == selection.version) return
        lastAppliedSelectionVersion = selection.version
        connectionControlsView.setServer(selection.country, selection.countryCode, selection.ip)
        if (selection.fromUserSelection) {
            connectionControlsView.setVpnConfigFromUser(selection.config)
        } else {
            connectionControlsView.setVpnConfig(selection.config)
        }
    }

    private fun openDestination(destination: MainDestination) {
        when (destination) {
            MainDestination.ServerList -> serverListActivityLauncher.launch(Intent(this, ServerListActivity::class.java))
            MainDestination.Dns -> dnsActivityLauncher.launch(Intent(this, DnsActivity::class.java))
            MainDestination.Filter -> filterActivityLauncher.launch(Intent(this, FilterActivity::class.java))
            MainDestination.Settings -> settingsActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
            MainDestination.About -> aboutActivityLauncher.launch(Intent(this, AboutActivity::class.java))
        }
    }

    private fun dispatchConnectionButtonClick() {
        val hasNotificationPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val hasVpnPermission = VpnService.prepare(this) == null
        viewModel.onAction(
            MainAction.ConnectionButtonClicked(
                hasNotificationPermission = hasNotificationPermission,
                hasVpnPermission = hasVpnPermission
            )
        )
    }

    private fun resolve(text: UiText): String = when (text) {
        is UiText.Plain -> text.value
        is UiText.Res -> getString(text.resId, *text.args.toTypedArray())
    }

    override fun onDestroy() {
        if (::binding.isInitialized) {
            binding.drawerLayout.removeDrawerListener(focusRestoringDrawerListener)
        }
        super.onDestroy()
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

