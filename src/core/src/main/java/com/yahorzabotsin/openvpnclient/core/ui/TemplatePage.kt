package com.yahorzabotsin.openvpnclient.core.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import com.yahorzabotsin.openvpnclient.core.databinding.ActivityTemplateBinding

object TemplatePage {
    fun setupHeader(
        activity: ComponentActivity,
        binding: ActivityTemplateBinding,
        titleResId: Int,
        backDestination: Intent? = null
    ) {
        binding.toolbarTitle.setText(titleResId)
        binding.backButton.setOnClickListener {
            if (backDestination != null) {
                activity.startActivity(backDestination)
                activity.finish()
            } else {
                activity.onBackPressedDispatcher.onBackPressed()
            }
        }
    }
}
