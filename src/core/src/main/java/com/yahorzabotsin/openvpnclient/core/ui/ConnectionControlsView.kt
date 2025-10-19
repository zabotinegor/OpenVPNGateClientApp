package com.yahorzabotsin.openvpnclient.core.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.yahorzabotsin.openvpnclient.core.R

class ConnectionControlsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private val countryTextView: TextView
    private val cityTextView: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_connection_controls, this, true)
        countryTextView = findViewById(R.id.current_country)
        cityTextView = findViewById(R.id.current_city)
    }

    fun setServer(country: String, city: String) {
        countryTextView.text = country
        cityTextView.text = city
    }
}