package com.yahorzabotsin.openvpnclientgate.tv

import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView

import com.yahorzabotsin.openvpnclientgate.core.R as coreR
import com.yahorzabotsin.openvpnclientgate.tv.R as tvR

class MainActivity : com.yahorzabotsin.openvpnclientgate.core.ui.main.MainActivityCore() {
    private companion object {
        const val TAG = "MainActivityTV"
        const val OK_KEY_POST_DRAWER_CLOSE_DEBOUNCE_MS = 500L
        const val OK_KEY_SPAM_BURST_GUARD_MS = 500L
    }
    private var selectedMenuItemId: Int = coreR.id.nav_server
    private var isMainContentBlocked: Boolean = false
    private var currentDrawerState: Int = DrawerLayout.STATE_IDLE
    private var isDrawerEngaged: Boolean = false
    private var consumeOkUntilUptimeMs: Long = 0L
    private var consumeOkBurstUntilUptimeMs: Long = 0L
    private var hasConsumedPostCloseOkUp: Boolean = false
    private var initialConnectionControlsDescendantFocusability: Int? = null

    override fun styleNavigationView(nv: NavigationView) {
        nv.itemBackground = AppCompatResources.getDrawable(
            this,
            tvR.drawable.nav_item_background
        )
    }

    override fun addDrawerExtras(drawerLayout: DrawerLayout) {
        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                if (slideOffset > 0f) {
                    isDrawerEngaged = true
                }
                if (TvDrawerInteractionGuard.shouldRequestDrawerFocus(slideOffset)) {
                    updateMainContentInteraction(blocked = true)
                }
            }

            override fun onDrawerOpened(drawerView: View) {
                AppLog.d(TAG, "Drawer opened, main content interaction blocked.")
                isDrawerEngaged = true
                updateMainContentInteraction(blocked = true)
            }

            override fun onDrawerClosed(drawerView: View) {
                AppLog.d(TAG, "Drawer closed, focusing on connection button.")
                isDrawerEngaged = false
                consumeOkUntilUptimeMs = SystemClock.uptimeMillis() + OK_KEY_POST_DRAWER_CLOSE_DEBOUNCE_MS
                consumeOkBurstUntilUptimeMs = 0L
                hasConsumedPostCloseOkUp = false
                updateMainContentInteraction(blocked = false)
                connectionControlsView.requestPrimaryFocus()
            }

            override fun onDrawerStateChanged(newState: Int) {
                currentDrawerState = newState
                val drawerIsOpen = drawerLayout.isDrawerOpen(GravityCompat.START)

                if (newState != DrawerLayout.STATE_IDLE) {
                    isDrawerEngaged = true
                } else if (isDrawerEngaged && !drawerIsOpen) {
                    isDrawerEngaged = false
                    // Arm debounce when drawer has just finished closing.
                    consumeOkUntilUptimeMs =
                        SystemClock.uptimeMillis() + OK_KEY_POST_DRAWER_CLOSE_DEBOUNCE_MS
                    consumeOkBurstUntilUptimeMs = 0L
                    hasConsumedPostCloseOkUp = false
                }

                val shouldBlock = TvDrawerInteractionGuard.shouldBlockMainContent(
                    drawerState = newState,
                    isDrawerOpen = drawerIsOpen
                )
                updateMainContentInteraction(shouldBlock)
            }
        })
    }

    override fun afterViewsReady() {
        val checkedItemId = binding.navView.checkedItem?.itemId
        if (checkedItemId == null) {
            binding.navView.setCheckedItem(selectedMenuItemId)
        } else {
            selectedMenuItemId = checkedItemId
        }
        val drawerIsOpen = binding.drawerLayout.isDrawerOpen(GravityCompat.START)
        updateMainContentInteraction(blocked = drawerIsOpen)
        connectionControlsView.post {
            if (!binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                connectionControlsView.requestPrimaryFocus()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateMainContentInteraction(
            blocked = binding.drawerLayout.isDrawerOpen(GravityCompat.START)
        )
        connectionControlsView.post {
            if (!binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                connectionControlsView.requestPrimaryFocus()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val drawerIsOpen = binding.drawerLayout.isDrawerOpen(GravityCompat.START)
        val drawerIsEngaged = drawerIsOpen || isDrawerEngaged
        val now = SystemClock.uptimeMillis()
        val isCloseDebounceActive = now < consumeOkUntilUptimeMs
        val isBurstGuardActive = now < consumeOkBurstUntilUptimeMs

        val isOkKey = TvDrawerInteractionGuard.isOkKey(event.keyCode)
        val focusInDrawer = isViewInside(binding.navView, currentFocus)
        val focusInMainControls = isViewInside(binding.connectionControls, currentFocus)

        val shouldConsumeDebounced = TvDrawerInteractionGuard.shouldConsumeDebouncedOkEvent(
            keyCode = event.keyCode,
            keyAction = event.action,
            isCloseDebounceActive = isCloseDebounceActive,
            isDrawerOpen = drawerIsOpen,
            isFocusInMainContent = focusInMainControls,
            hasConsumedPostCloseOkUp = hasConsumedPostCloseOkUp
        )

        if (shouldConsumeDebounced) {
            if (TvDrawerInteractionGuard.shouldArmBurstGuardAfterDebouncedConsume(
                    keyCode = event.keyCode,
                    keyAction = event.action
                )
            ) {
                consumeOkBurstUntilUptimeMs = now + OK_KEY_SPAM_BURST_GUARD_MS
            }
            if (event.action == KeyEvent.ACTION_UP) {
                hasConsumedPostCloseOkUp = true
            }
            return true
        }

        if (isBurstGuardActive && isOkKey && focusInMainControls) {
            return true
        }

        val shouldConsume = TvDrawerInteractionGuard.shouldConsumeOkEvent(
            keyCode = event.keyCode,
            keyAction = event.action,
            drawerState = currentDrawerState,
            isDrawerEngaged = drawerIsEngaged,
            isFocusInDrawer = focusInDrawer
        )

        if (shouldConsume) {
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    private fun requestSelectedDrawerItemFocus() {
        val selectedId = binding.navView.checkedItem?.itemId ?: selectedMenuItemId
        selectedMenuItemId = selectedId
        binding.navView.setCheckedItem(selectedId)
        binding.navView.post {
            val viewToFocus = binding.navView.findViewById<View>(selectedId)
            viewToFocus?.requestFocus()
        }
    }

    private fun updateMainContentInteraction(blocked: Boolean) {
        if (initialConnectionControlsDescendantFocusability == null) {
            initialConnectionControlsDescendantFocusability =
                binding.connectionControls.descendantFocusability
        }
        if (isMainContentBlocked == blocked) return
        isMainContentBlocked = blocked

        binding.connectionControls.descendantFocusability = if (blocked) {
            ViewGroup.FOCUS_BLOCK_DESCENDANTS
        } else {
            initialConnectionControlsDescendantFocusability ?: ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        if (blocked) {
            requestSelectedDrawerItemFocus()
        }
    }

    private fun isViewInside(container: View, candidate: View?): Boolean {
        var current = candidate
        while (current != null) {
            if (current === container) return true
            current = (current.parent as? View)
        }
        return false
    }
}
