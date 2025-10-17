package com.yahorzabotsin.openvpnclient.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.activity.BaseCountryListActivity
import com.yahorzabotsin.openvpnclient.core.ui.BaseServerListActivity

class ServerListActivityTV : BaseServerListActivity() {

    override fun setupToolbarAndBackButton() {
        findViewById<View>(R.id.back_button).setOnClickListener {
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    override fun createCountryListIntent(countries: ArrayList<String>, currentCountry: String): Intent {
        return CountryListActivityTV.newIntent(this, countries, currentCountry)
    }

    override fun getCountryListActivityExtraName(): String {
        return BaseCountryListActivity.EXTRA_SELECTED_COUNTRY
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
