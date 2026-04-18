package com.yahorzabotsin.openvpnclientgate.mobile

import android.content.Intent
import com.yahorzabotsin.openvpnclientgate.core.ui.splash.SplashActivityCore

class SplashActivity : SplashActivityCore() {

    override val splashGifRawRes: Int = R.raw.splash_intro
    override val splashGifTintColorRes: Int = R.color.splash_gif_tint_color

    override fun createMainIntent(): Intent {
        return Intent(this, MainActivity::class.java)
    }
}
