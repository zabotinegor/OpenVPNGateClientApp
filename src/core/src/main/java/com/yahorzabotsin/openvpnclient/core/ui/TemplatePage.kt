package com.yahorzabotsin.openvpnclient.core.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.yahorzabotsin.openvpnclient.core.databinding.ActivityTemplateBinding

object TemplatePage {
    fun setupHeader(
        activity: ComponentActivity,
        binding: ActivityTemplateBinding,
        titleResId: Int,
        backDestination: Intent? = null
    ) {
        binding.toolbarTitle.setText(titleResId)

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
