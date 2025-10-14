package com.yahorzabotsin.openvpnclient.mobile

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import com.yahorzabotsin.openvpnclient.core.R as coreR
import com.yahorzabotsin.openvpnclient.core.ui.setAsStub

class MainActivity : AppCompatActivity() {
    private lateinit var drawerLayout: DrawerLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawer_layout)
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

        findViewById<android.view.View>(R.id.start_connection_button).setAsStub()

        val navigationView = findViewById<NavigationView>(R.id.nav_view)
        navigationView.setNavigationItemSelectedListener {
            Toast.makeText(this, "Feature in Development", Toast.LENGTH_SHORT).show()
            drawerLayout.closeDrawer(GravityCompat.START)
            true
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