package com.yahorzabotsin.openvpnclientgate.core.ui.splash

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Movie
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.annotation.ColorRes
import androidx.annotation.RawRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.vpn.VpnConnectionStateProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

abstract class SplashActivityCore : AppCompatActivity() {
    private val tag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "SplashActivityCore"

    private val preloadInteractor: SplashServerPreloadInteractor by inject()
    private val connectionStateProvider: VpnConnectionStateProvider by inject()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var imageView: ImageView? = null
    private var spinner: ProgressBar? = null
    private var hasNavigated = false
    private var gifCompletedAtElapsedMs = 0L
    private var isGifCompleted = false
    private var isServerPreloadCompleted = false
    private var isReadyToNavigate = false
    private var serverPreloadJob: Job? = null

    @get:RawRes
    protected abstract val splashGifRawRes: Int

    @get:ColorRes
    protected abstract val splashGifTintColorRes: Int

    protected abstract fun createMainIntent(): Intent

    private val gifCompleteRunnable = Runnable {
        onGifCompleted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash_core)

        imageView = findViewById(R.id.splashGifView)
        spinner = findViewById(R.id.splashLoadingSpinner)
        val isDarkTheme =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        // For light theme, tint the GIF with the dark theme base color.
        if (!isDarkTheme) {
            imageView?.setColorFilter(getColor(splashGifTintColorRes))
        }

        startServerPreload()
        val gifDurationMs = resolveGifDurationMs(splashGifRawRes)

        Glide.with(this)
            .asGif()
            .load(splashGifRawRes)
            .listener(object : RequestListener<GifDrawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<GifDrawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    if (e != null) {
                        AppLog.w(tag, "Splash GIF load failed; continuing without GIF", e)
                    } else {
                        AppLog.w(tag, "Splash GIF load failed; continuing without GIF")
                    }
                    onGifCompleted()
                    return false
                }

                override fun onResourceReady(
                    resource: GifDrawable,
                    model: Any,
                    target: Target<GifDrawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    resource.setLoopCount(1)
                    scheduleGifCompletion(gifDurationMs)
                    return false
                }
            })
            .into(imageView!!)
    }

    override fun onStart() {
        super.onStart()
        if (isReadyToNavigate) {
            navigateToMain()
            return
        }
        if (!isGifCompleted && gifCompletedAtElapsedMs > 0L) {
            val remainingDelayMs = (gifCompletedAtElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            mainHandler.removeCallbacks(gifCompleteRunnable)
            mainHandler.postDelayed(gifCompleteRunnable, remainingDelayMs)
        }
    }

    override fun onStop() {
        imageView?.let { Glide.with(it).clear(it) }
        mainHandler.removeCallbacks(gifCompleteRunnable)
        super.onStop()
    }

    override fun onDestroy() {
        serverPreloadJob?.cancel()
        mainHandler.removeCallbacks(gifCompleteRunnable)
        imageView = null
        spinner = null
        super.onDestroy()
    }

    private fun startServerPreload() {
        serverPreloadJob?.cancel()
        serverPreloadJob = lifecycleScope.launch {
            try {
                val cacheOnly = connectionStateProvider.isConnected()
                withContext(Dispatchers.IO) {
                    preloadInteractor.preloadServers(cacheOnly = cacheOnly)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Ignore preload errors and continue to main flow.
                AppLog.w(tag, "Server preload failed; continuing startup", e)
            } finally {
                isServerPreloadCompleted = true
                onPreloadCompleted()
            }
        }
    }

    private fun onPreloadCompleted() {
        if (isGifCompleted) {
            markReadyToNavigate()
        }
    }

    private fun onGifCompleted() {
        if (isGifCompleted) return
        isGifCompleted = true
        gifCompletedAtElapsedMs = 0L
        if (isServerPreloadCompleted) {
            markReadyToNavigate()
        } else {
            spinner?.isVisible = true
        }
    }

    private fun markReadyToNavigate() {
        if (isReadyToNavigate) return
        isReadyToNavigate = true
        spinner?.isVisible = false
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            navigateToMain()
        }
    }

    private fun scheduleGifCompletion(delayMs: Long) {
        gifCompletedAtElapsedMs = SystemClock.elapsedRealtime() + delayMs
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            mainHandler.removeCallbacks(gifCompleteRunnable)
            mainHandler.postDelayed(gifCompleteRunnable, delayMs)
        }
    }

    private fun navigateToMain() {
        if (hasNavigated) {
            return
        }
        hasNavigated = true
        startActivity(createMainIntent())
        finish()
    }

    private fun resolveGifDurationMs(@RawRes gifRes: Int): Long {
        resources.openRawResource(gifRes).use { inputStream ->
            val durationMs = Movie.decodeStream(inputStream)?.duration() ?: 0
            return if (durationMs > 0) durationMs.toLong() else FALLBACK_GIF_DURATION_MS
        }
    }

    companion object {
        private const val FALLBACK_GIF_DURATION_MS = 3_000L
    }
}