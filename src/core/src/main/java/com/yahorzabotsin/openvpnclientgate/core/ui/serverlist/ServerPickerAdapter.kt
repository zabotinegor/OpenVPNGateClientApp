package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.countryFlagEmoji
import com.yahorzabotsin.openvpnclientgate.core.servers.SignalStrength
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.ServerDisplayFormatter

class ServerPickerAdapter(
    private val servers: List<Server>,
    private val isDefaultV2Source: Boolean,
    private val onClick: (Server) -> Unit
) : RecyclerView.Adapter<ServerPickerAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_server_row, parent, false)
        return ViewHolder(v, isDefaultV2Source)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val server = servers[position]
        holder.bind(server)
        holder.itemView.setOnClickListener { onClick(server) }
    }

    override fun getItemCount(): Int = servers.size

    class ViewHolder(
        itemView: View,
        private val isDefaultV2Source: Boolean
    ) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.server_title)
        private val subtitle: TextView = itemView.findViewById(R.id.server_subtitle)
        private val chevron: ImageView = itemView.findViewById(R.id.chevron_icon)
        private val flag: TextView = itemView.findViewById(R.id.server_flag)
        private val pingView: TextView = itemView.findViewById(R.id.server_ping)
        private val signalView: ImageView = itemView.findViewById(R.id.server_signal)
        fun bind(server: Server) {
            if (isDefaultV2Source) {
                val city = server.city.trim()
                val utc = ServerDisplayFormatter.formatUtc(server.utc)
                when {
                    city.isNotEmpty() && utc != null -> {
                        title.text = city
                        subtitle.text = utc
                        subtitle.visibility = View.VISIBLE
                    }

                    city.isNotEmpty() -> {
                        title.text = city
                        subtitle.text = ""
                        subtitle.visibility = View.GONE
                    }

                    else -> {
                        title.text = server.ip
                        subtitle.text = ""
                        subtitle.visibility = View.GONE
                    }
                }
            } else {
                title.text = server.city.takeIf { it.isNotBlank() } ?: server.name
                subtitle.text = server.ip
                subtitle.visibility = View.VISIBLE
            }

            chevron.visibility = View.VISIBLE
            val flagEmoji = countryFlagEmoji(server.country.code)
            flag.text = flagEmoji ?: ""
            flag.visibility = if (flagEmoji.isNullOrEmpty()) View.GONE else View.VISIBLE
            pingView.text = itemView.context.getString(R.string.ping_ms_format, server.ping)
            signalView.setImageResource(
                when (server.signalStrength) {
                    SignalStrength.STRONG -> R.drawable.signal_strong
                    SignalStrength.MEDIUM -> R.drawable.signal_medium
                    SignalStrength.WEAK -> R.drawable.signal_weak
                }
            )
        }
    }
}

