package com.yahorzabotsin.openvpnclient.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.ui.BaseServerListActivity

class ServerListActivityTV : BaseServerListActivity() {

    override fun setupToolbarAndBackButton() {
        findViewById<View>(R.id.back_button).setOnClickListener {
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    override fun onBackPressed() {
        setResult(Activity.RESULT_OK)
        super.onBackPressed()
    }

    companion object {
        fun newIntent(context: Context): Intent {
            return Intent(context, ServerListActivityTV::class.java)
        }
    }
}
