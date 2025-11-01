package com.yahorzabotsin.openvpnclient.tv

import android.util.Log
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView

import com.yahorzabotsin.openvpnclient.core.R as coreR
import com.yahorzabotsin.openvpnclient.tv.R as tvR

class MainActivity : com.yahorzabotsin.openvpnclient.core.ui.MainActivityCore() {
    private companion object { const val TAG = "MainActivityTV" }
    private var selectedMenuItemId: Int = coreR.id.nav_server

    override fun styleNavigationView(nv: NavigationView) {
        nv.itemBackground = AppCompatResources.getDrawable(
            this,
            tvR.drawable.nav_item_background
        )
    }

    override fun addDrawerExtras(drawerLayout: DrawerLayout) {
        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}
            override fun onDrawerOpened(drawerView: View) {
                Log.d(TAG, "Drawer opened, focusing on selected item.")
                binding.navView.setCheckedItem(selectedMenuItemId)
                binding.navView.post {
                    val viewToFocus = binding.navView.findViewById<View>(selectedMenuItemId)
                    viewToFocus?.requestFocus()
                }
            }
            override fun onDrawerClosed(drawerView: View) {
                Log.d(TAG, "Drawer closed, focusing on connection button.")
                connectionControlsView.requestPrimaryFocus()
            }
            override fun onDrawerStateChanged(newState: Int) {}
        })
    }

    override fun afterViewsReady() {
        binding.navView.setCheckedItem(selectedMenuItemId)
        connectionControlsView.post { connectionControlsView.requestPrimaryFocus() }
    }
}


