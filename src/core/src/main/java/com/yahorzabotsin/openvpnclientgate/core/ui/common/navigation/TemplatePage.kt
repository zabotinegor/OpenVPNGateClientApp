package com.yahorzabotsin.openvpnclientgate.core.ui.common.navigation

import android.content.Intent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.yahorzabotsin.openvpnclientgate.core.databinding.ActivityTemplateBinding

object TemplatePage {
    fun create(
        activity: AppCompatActivity,
        titleResId: Int,
        backDestination: Intent? = null
    ): ActivityTemplateBinding {
        val binding = ActivityTemplateBinding.inflate(activity.layoutInflater)
        activity.setContentView(binding.root)
        setupHeader(activity, binding, titleResId, backDestination)
        activity.lifecycle.addObserver(ScreenFlowLogger())
        return binding
    }

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

    private class ScreenFlowLogger : DefaultLifecycleObserver {
        private val screenLogTag =
            com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "ScreenFlow"

        override fun onStart(owner: LifecycleOwner) {
            Log.i(screenLogTag, "enter ${owner.javaClass.simpleName}")
        }

        override fun onStop(owner: LifecycleOwner) {
            Log.i(screenLogTag, "exit ${owner.javaClass.simpleName}")
        }
    }
}


