package com.yahorzabotsin.openvpnclient.core.activity

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView

abstract class BaseCountryListActivity : AppCompatActivity() {

    protected abstract val recyclerView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    protected fun onCountrySelected(country: String) {
        val resultIntent = Intent().apply {
            putExtra(EXTRA_SELECTED_COUNTRY, country)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    companion object {
        const val EXTRA_COUNTRIES = "com.yahorzabotsin.openvpnclient.EXTRA_COUNTRIES"
        const val EXTRA_SELECTED_COUNTRY = "com.yahorzabotsin.openvpnclient.EXTRA_SELECTED_COUNTRY"
        const val EXTRA_CURRENT_COUNTRY = "com.yahorzabotsin.openvpnclient.EXTRA_CURRENT_COUNTRY"
    }
}