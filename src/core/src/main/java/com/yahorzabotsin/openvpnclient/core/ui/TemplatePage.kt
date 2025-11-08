package com.yahorzabotsin.openvpnclient.core.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.yahorzabotsin.openvpnclient.core.databinding.ActivityTemplateBinding

object TemplatePage {
    fun setupHeader(
        activity: ComponentActivity,
        binding: ActivityTemplateBinding,
        titleResId: Int,
        backDestination: Intent? = null
    ) {
        binding.toolbarTitle.setText(titleResId)

        // Apply status bar insets so the toolbar content does not overlap
        // with the system status icons on edge-to-edge devices.
        val originalTop = binding.toolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { v, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(v.paddingLeft, originalTop + status.top, v.paddingRight, v.paddingBottom)
            insets
        }

        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                backDestination?.let {
                    val destinationIntent = Intent(it)
                    destinationIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    activity.startActivity(destinationIntent)
                }
                activity.finish()
            }
        }

        binding.backButton.setOnClickListener { backCallback.handleOnBackPressed() }

        activity.onBackPressedDispatcher.addCallback(activity, backCallback)
    }
}
