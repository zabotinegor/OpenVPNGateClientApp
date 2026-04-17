package com.yahorzabotsin.openvpnclientgate.mobile

import android.content.Intent
import android.graphics.Movie
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

class SplashActivity : AppCompatActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var imageView: ImageView? = null
    private var hasNavigated = false
    private var navigateAtElapsedMs = 0L

    private val navigateRunnable = Runnable {
        if (!hasNavigated) {
            navigateToMain()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        imageView = findViewById(R.id.splashGifView)
        val gifDurationMs = resolveGifDurationMs()

        Glide.with(this)
            .asGif()
            .load(R.raw.splash_intro)
            .listener(object : RequestListener<GifDrawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<GifDrawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    scheduleNavigation(EXTRA_HOLD_MS)
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
                    scheduleNavigation(gifDurationMs + EXTRA_HOLD_MS)
                    return false
                }
            })
            .into(imageView!!)
    }

    override fun onStart() {
        super.onStart()
        if (!hasNavigated && navigateAtElapsedMs > 0L) {
            val remainingDelayMs = (navigateAtElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            mainHandler.removeCallbacks(navigateRunnable)
            mainHandler.postDelayed(navigateRunnable, remainingDelayMs)
        }
    }

    override fun onStop() {
        imageView?.let { Glide.with(it).clear(it) }
        mainHandler.removeCallbacks(navigateRunnable)
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(navigateRunnable)
        imageView = null
        super.onDestroy()
    }

    private fun scheduleNavigation(delayMs: Long) {
        navigateAtElapsedMs = SystemClock.elapsedRealtime() + delayMs
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            mainHandler.removeCallbacks(navigateRunnable)
            mainHandler.postDelayed(navigateRunnable, delayMs)
        }
    }

    private fun navigateToMain() {
        if (hasNavigated) {
            return
        }
        hasNavigated = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun resolveGifDurationMs(): Long {
        resources.openRawResource(R.raw.splash_intro).use { inputStream ->
            val durationMs = Movie.decodeStream(inputStream)?.duration() ?: 0
            return if (durationMs > 0) durationMs.toLong() else FALLBACK_GIF_DURATION_MS
        }
    }

    private companion object {
        const val EXTRA_HOLD_MS = 2_000L
        const val FALLBACK_GIF_DURATION_MS = 3_000L
    }
}
