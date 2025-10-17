package com.yahorzabotsin.openvpnclient.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yahorzabotsin.openvpnclient.core.activity.BaseCountryListActivity
import com.yahorzabotsin.openvpnclient.core.R as coreR
import com.yahorzabotsin.openvpnclient.core.ui.CountryAdapter

class CountryListActivityTV : BaseCountryListActivity() {

    override val recyclerView: RecyclerView by lazy { findViewById(coreR.id.countries_recycler_view) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(coreR.layout.activity_country_list)

        val countries = intent.getStringArrayListExtra(EXTRA_COUNTRIES) ?: arrayListOf()
        val currentCountry = intent.getStringExtra(EXTRA_CURRENT_COUNTRY)
        val displayList = mutableListOf(getString(coreR.string.all_countries))
        displayList.addAll(countries)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = CountryAdapter(displayList, currentCountry) { country ->
            onCountrySelected(country)
        }

        val selectedItemPosition = displayList.indexOf(currentCountry)
        if (selectedItemPosition != -1) {
            recyclerView.scrollToPosition(selectedItemPosition)
        }
    }

    companion object {
        fun newIntent(context: Context, countries: ArrayList<String>, currentCountry: String): Intent {
            return Intent(context, CountryListActivityTV::class.java).apply {
                putStringArrayListExtra(EXTRA_COUNTRIES, countries)
                putExtra(EXTRA_CURRENT_COUNTRY, currentCountry)
            }
        }
    }
}
