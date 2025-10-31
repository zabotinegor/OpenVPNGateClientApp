package com.yahorzabotsin.openvpnclient.tv

import android.util.Log
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView

class MainActivity : com.yahorzabotsin.openvpnclient.core.ui.MainActivityCore() {

    private var selectedMenuItemId: Int = com.yahorzabotsin.openvpnclient.core.R.id.nav_server

    override fun styleNavigationView(nv: NavigationView) {
        nv.itemBackground = AppCompatResources.getDrawable(
            this,
            com.yahorzabotsin.openvpnclient.tv.R.drawable.nav_item_background
        )
    }

    override fun addDrawerExtras(drawerLayout: DrawerLayout) {
        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}
            override fun onDrawerOpened(drawerView: View) {
                Log.d("MainActivityTV", "Drawer opened, focusing on selected item.")
                binding.navView.setCheckedItem(selectedMenuItemId)
                binding.navView.post {
                    val viewToFocus = binding.navView.findViewById<View>(selectedMenuItemId)
                    viewToFocus?.requestFocus()
                }
            }
            override fun onDrawerClosed(drawerView: View) {
                Log.d("MainActivityTV", "Drawer closed, focusing on connection button.")
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
