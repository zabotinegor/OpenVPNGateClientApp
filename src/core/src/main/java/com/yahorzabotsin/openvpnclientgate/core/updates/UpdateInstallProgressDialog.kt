package com.yahorzabotsin.openvpnclientgate.core.updates

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.setPadding
import androidx.lifecycle.LifecycleOwner
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.ui.common.utils.DialogUtils

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
                val paddingPx = (48 * activity.resources.displayMetrics.density).toInt()
                setPadding(paddingPx)
                addView(progressBar)
                addView(progressText)
            }
        )
        .setCancelable(false)
        .create()

    private val uiHandler = Handler(Looper.getMainLooper())
    private val progressDispatcher = LatestUpdateProgressDispatcher(
        post = { uiHandler.post(it) },
        remove = { uiHandler.removeCallbacks(it) },
        render = { progress ->
            if (!activity.isDestroyed && !activity.isFinishing) {
                renderProgress(progress)
            }
        }
    )

    private val destroyObserver = ReleaseOnDestroyObserver {
        progressDispatcher.close()
        if (dialog.isShowing) {
            dialog.dismiss()
        }
    }
    private val lifecycleOwner = activity as? LifecycleOwner

    fun show() {
        if (!activity.isFinishing && !activity.isDestroyed) {
            // Close the window during onDestroy: the install coroutine is cancelled by the same
            // destruction but resumes only after the window manager has reported a leaked window.
            lifecycleOwner?.lifecycle?.addObserver(destroyObserver)
            dialog.show()
            DialogUtils.applyThemedTitleColor(dialog)
        }
    }

    fun update(progress: AppUpdateInstallProgress) {
        progressDispatcher.offer(progress)
    }

    private fun renderProgress(progress: AppUpdateInstallProgress) {
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

    fun dismiss() {
        // Remove the pending callback before dismissing the Activity-backed window.
        // Subsequent stream callbacks cannot requeue UI work after the screen closes.
        progressDispatcher.close()
        activity.runOnUiThread {
            lifecycleOwner?.lifecycle?.removeObserver(destroyObserver)
            if (dialog.isShowing) {
                dialog.dismiss()
            }
        }
    }

    private fun formatBytes(bytes: Long): String = Formatter.formatFileSize(activity, bytes)
}

