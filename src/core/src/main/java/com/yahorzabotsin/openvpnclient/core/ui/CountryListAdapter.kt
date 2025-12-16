package com.yahorzabotsin.openvpnclient.core.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.servers.Country
import com.yahorzabotsin.openvpnclient.core.servers.countryFlagEmoji

class CountryListAdapter(
    private val countries: List<Country>,
    private val onClick: (Country) -> Unit
) : RecyclerView.Adapter<CountryListAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_country_row, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val country = countries[position]
        holder.bind(country)
        holder.itemView.setOnClickListener { onClick(country) }
    }

    override fun getItemCount(): Int = countries.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.country_name)
        private val flagView: TextView = itemView.findViewById(R.id.country_flag)
        fun bind(country: Country) {
            name.text = country.name
            val flag = countryFlagEmoji(country.code)
            if (!flag.isNullOrEmpty()) {
                flagView.text = flag
                flagView.visibility = View.VISIBLE
            } else {
                flagView.text = ""
                flagView.visibility = View.GONE
            }
        }
    }
}

