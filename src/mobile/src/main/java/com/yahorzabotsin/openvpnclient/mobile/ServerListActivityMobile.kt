package com.yahorzabotsin.openvpnclient.mobile

import android.view.View
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.ui.BaseServerListActivity

class ServerListActivityMobile : BaseServerListActivity() {

    override fun setupToolbarAndBackButton() {
        findViewById<View>(R.id.back_button).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }
}
