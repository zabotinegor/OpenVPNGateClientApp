package com.yahorzabotsin.openvpnclient.core.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yahorzabotsin.openvpnclient.core.R

class CountryAdapter(
    private val countries: List<String>,
    private val currentCountry: String?,
    private val onCountrySelected: (String) -> Unit
) : RecyclerView.Adapter<CountryAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_country, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val country = countries[position]
        holder.bind(country)
        holder.itemView.isSelected = country == currentCountry
        holder.itemView.setOnClickListener { onCountrySelected(country) }
    }

    override fun getItemCount() = countries.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val countryName: TextView = itemView.findViewById(R.id.country_name)

        fun bind(country: String) {
            countryName.text = country
        }
    }
}