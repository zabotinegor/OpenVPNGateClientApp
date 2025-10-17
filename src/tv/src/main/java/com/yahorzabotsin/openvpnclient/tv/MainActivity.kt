package com.yahorzabotsin.openvpnclient.tv

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import com.yahorzabotsin.openvpnclient.core.R as coreR
import com.yahorzabotsin.openvpnclient.core.ui.setAsStub
import com.yahorzabotsin.openvpnclient.tv.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedMenuItemId: Int = coreR.id.nav_server

    private val serverListActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        setupDrawer()
        setupListeners()

        binding.startConnectionButton.setAsStub()
        binding.navView.setCheckedItem(selectedMenuItemId)
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
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        binding.drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}

            override fun onDrawerOpened(drawerView: View) {
                binding.navView.setCheckedItem(selectedMenuItemId)
                binding.navView.post { // Use post to make sure the view is rendered before requesting focus
                    binding.navView.findViewById<View>(selectedMenuItemId)?.requestFocus()
                }
            }

            override fun onDrawerClosed(drawerView: View) {
                binding.startConnectionButton.requestFocus()
            }

            override fun onDrawerStateChanged(newState: Int) {}
        })

        binding.navView.setNavigationItemSelectedListener {
            selectedMenuItemId = it.itemId
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

    override fun onResume() {
        super.onResume()
        binding.startConnectionButton.requestFocus()
    }

    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
}
