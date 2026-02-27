package com.yahorzabotsin.openvpnclientgate.core.updates

import android.app.Activity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.setPadding
import com.yahorzabotsin.openvpnclientgate.core.R

class UpdateInstallProgressDialog(
    private val activity: Activity
) {
    private val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true
        max = 100
    }
    private val progressText = TextView(activity).apply {
        text = activity.getString(R.string.update_downloading_unknown, formatBytes(0))
    }
    private val dialog: AlertDialog = AlertDialog.Builder(activity)
        .setTitle(R.string.action_update)
        .setView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48)
                addView(progressBar)
                addView(progressText)
            }
        )
        .setCancelable(false)
        .create()

    fun show() {
        if (!activity.isFinishing && !activity.isDestroyed) {
            dialog.show()
        }
    }

    fun update(progress: AppUpdateInstallProgress) {
        activity.runOnUiThread {
            val total = progress.totalBytes
            if (progress.percent != null && total != null && total > 0) {
                progressBar.isIndeterminate = false
                progressBar.progress = progress.percent
                progressText.text = activity.getString(
                    R.string.update_downloading_progress,
                    progress.percent,
                    formatBytes(progress.downloadedBytes),
                    formatBytes(total)
                )
            } else {
                progressBar.isIndeterminate = true
                progressText.text = activity.getString(
                    R.string.update_downloading_unknown,
                    formatBytes(progress.downloadedBytes)
                )
            }
        }
    }

    fun dismiss() {
        activity.runOnUiThread {
            if (dialog.isShowing) {
                dialog.dismiss()
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return if (index == 0) {
            "${value.toLong()} ${units[index]}"
        } else {
            String.format("%.1f %s", value, units[index])
        }
    }
}
