package com.yahorzabotsin.openvpnclientgate.core.ui.splash

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.annotation.ColorRes
import androidx.annotation.RawRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.servers.refresh.ServerRefreshFeatureFlags
import com.yahorzabotsin.openvpnclientgate.vpn.VpnConnectionStateProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.android.ext.android.inject

abstract class SplashActivityCore : AppCompatActivity() {
    private val tag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "SplashActivityCore"

    private val preloadInteractor: SplashServerPreloadInteractor by inject()
    private val connectionStateProvider: VpnConnectionStateProvider by inject()

    private var imageView: ImageView? = null
    private var spinner: ProgressBar? = null
    private var hasNavigated = false
    private var isGifCompleted = false
    private var isServerPreloadCompleted = false
    private var isReadyToNavigate = false
    private var serverPreloadJob: Job? = null

    private companion object {
        private const val SERVER_PRELOAD_TIMEOUT_MS = 12_000L
    }

    @get:RawRes
    protected abstract val splashGifRawRes: Int

    @get:ColorRes
    protected abstract val splashGifTintColorRes: Int

    protected abstract fun createMainIntent(): Intent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash_core)

        imageView = findViewById(R.id.splashGifView)
        spinner = findViewById(R.id.splashLoadingSpinner)
        val isDarkTheme =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        if (!isDarkTheme) {
            imageView?.setColorFilter(ContextCompat.getColor(this, splashGifTintColorRes))
        }

        startServerPreload()

        imageView?.let { view ->
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
                        resource.registerAnimationCallback(object : Animatable2Compat.AnimationCallback() {
                            override fun onAnimationEnd(drawable: Drawable?) {
                                onGifCompleted()
                            }
                        })
                        return false
                    }
                })
                .into(view)
        }
    }

    override fun onStart() {
        super.onStart()
        if (isReadyToNavigate) {
            navigateToMain()
        }
    }

    override fun onDestroy() {
        serverPreloadJob?.cancel()
        imageView = null
        spinner = null
        super.onDestroy()
    }

    private fun startServerPreload() {
        serverPreloadJob?.cancel()
        serverPreloadJob = lifecycleScope.launch {
            try {
                val isConnected = connectionStateProvider.isConnected()
                val cacheOnly = ServerRefreshFeatureFlags.shouldUseCacheOnlyWhenVpnConnected(isConnected)
                AppLog.i(tag, "Starting server preload. vpn_connected=$isConnected, cache_only=$cacheOnly")
                withTimeout(SERVER_PRELOAD_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        preloadInteractor.preloadServers(cacheOnly = cacheOnly)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                AppLog.w(
                    tag,
                    "Server preload timed out after ${SERVER_PRELOAD_TIMEOUT_MS} ms; continuing startup"
                )
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

    private fun navigateToMain() {
        if (hasNavigated) {
            return
        }
        hasNavigated = true
        val mainIntent = createMainIntent().apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(mainIntent)
        finish()
    }
}