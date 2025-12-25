package com.yahorzabotsin.openvpnclientgate.core.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.countryFlagEmoji

data class CountryWithServers(
    val country: Country,
    val serverCount: Int
)

class CountryListAdapter(
    private val countries: List<CountryWithServers>,
    private val onClick: (Country) -> Unit
) : RecyclerView.Adapter<CountryListAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_country_row, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val country = countries[position]
        holder.bind(country)
        holder.itemView.setOnClickListener { onClick(country.country) }
    }

    override fun getItemCount(): Int = countries.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.country_name)
        private val flagView: TextView = itemView.findViewById(R.id.country_flag)
        private val serverCountView: TextView = itemView.findViewById(R.id.server_count)
        private val chevronIcon: ImageView = itemView.findViewById(R.id.chevron_icon)
        fun bind(country: CountryWithServers) {
            name.text = country.country.name
            val flag = countryFlagEmoji(country.country.code)
            if (!flag.isNullOrEmpty()) {
                flagView.text = flag
                flagView.visibility = View.VISIBLE
            } else {
                flagView.text = ""
                flagView.visibility = View.GONE
            }
            serverCountView.text = itemView.context.resources.getQuantityString(
                R.plurals.server_count,
                country.serverCount,
                country.serverCount
            )
            chevronIcon.visibility = View.VISIBLE
        }
    }
}

