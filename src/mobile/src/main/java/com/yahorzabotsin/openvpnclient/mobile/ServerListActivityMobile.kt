package com.yahorzabotsin.openvpnclient.mobile

import android.content.Intent
import android.view.View
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.activity.BaseCountryListActivity
import com.yahorzabotsin.openvpnclient.core.ui.BaseServerListActivity

class ServerListActivityMobile : BaseServerListActivity() {

    override fun setupToolbarAndBackButton() {
        findViewById<View>(R.id.back_button).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun createCountryListIntent(countries: ArrayList<String>, currentCountry: String): Intent {
        return CountryListActivityMobile.newIntent(this, countries, currentCountry)
    }

    override fun getCountryListActivityExtraName(): String {
        return BaseCountryListActivity.EXTRA_SELECTED_COUNTRY
    }
}