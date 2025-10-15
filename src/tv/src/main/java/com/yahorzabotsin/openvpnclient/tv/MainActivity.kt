package com.yahorzabotsin.openvpnclient.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import com.yahorzabotsin.openvpnclient.core.ui.setAsStub

class MainActivity : AppCompatActivity() {
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private var selectedMenuItemId: Int = com.yahorzabotsin.openvpnclient.core.R.id.nav_server

    private val serverActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawer_layout)
        navigationView = findViewById(R.id.nav_view)
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        val toggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            com.yahorzabotsin.openvpnclient.core.R.string.navigation_drawer_open,
            com.yahorzabotsin.openvpnclient.core.R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        findViewById<View>(R.id.menu_button).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        val startConnectionButton = findViewById<View>(R.id.start_connection_button)
        startConnectionButton.setAsStub()

        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}

            override fun onDrawerOpened(drawerView: View) {
                navigationView.setCheckedItem(selectedMenuItemId)
                // Use post to make sure the view is rendered before requesting focus
                navigationView.post {
                    navigationView.findViewById<View>(selectedMenuItemId)?.requestFocus()
                }
            }

            override fun onDrawerClosed(drawerView: View) {
                startConnectionButton.requestFocus()
            }

            override fun onDrawerStateChanged(newState: Int) {}
        })

        navigationView.setNavigationItemSelectedListener {
            selectedMenuItemId = it.itemId
            when (it.itemId) {
                com.yahorzabotsin.openvpnclient.core.R.id.nav_server -> {
                    serverActivityLauncher.launch(Intent(this, ServerActivity::class.java))
                }
                else -> {
                    Toast.makeText(this, "Feature in Development", Toast.LENGTH_SHORT).show()
                }
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        // Set initial checked item
        navigationView.setCheckedItem(selectedMenuItemId)
    }

    override fun onResume() {
        super.onResume()
        findViewById<View>(R.id.start_connection_button).requestFocus()
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
}