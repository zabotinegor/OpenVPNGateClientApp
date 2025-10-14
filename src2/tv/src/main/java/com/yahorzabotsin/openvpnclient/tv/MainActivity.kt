package com.yahorzabotsin.openvpnclient.tv

import android.os.Bundle
import androidx.fragment.app.FragmentActivity

/**
 * Main activity for the TV app.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_content, ConnectionFragment())
                .commitNow()
        }
    }
}
