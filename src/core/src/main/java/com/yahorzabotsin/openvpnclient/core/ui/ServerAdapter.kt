package com.yahorzabotsin.openvpnclient.core.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.core.servers.Server
import com.yahorzabotsin.openvpnclient.core.servers.SignalStrength

class ServerAdapter(
    private var servers: MutableList<Server> = mutableListOf()
) : RecyclerView.Adapter<ServerAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_server, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(servers[position])
    }

    override fun getItemCount() = servers.size

    fun updateServers(newServers: List<Server>) {
        servers.clear()
        servers.addAll(newServers)
        notifyDataSetChanged() // This is simple, but not the most efficient way.
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val serverName: TextView = itemView.findViewById(R.id.server_name)
        private val serverCity: TextView = itemView.findViewById(R.id.server_city)
        private val pingIndicator: ImageView = itemView.findViewById(R.id.ping_indicator)
        private val pingValue: TextView = itemView.findViewById(R.id.ping_value)

        fun bind(server: Server) {
            serverName.text = server.name
            serverCity.text = server.city
            pingValue.text = server.ping.toString()

            val pingIndicatorRes = when (server.signalStrength) {
                SignalStrength.STRONG -> R.drawable.ic_ping_strong
                SignalStrength.MEDIUM -> R.drawable.ic_ping_medium
                SignalStrength.WEAK -> R.drawable.ic_ping_weak
            }
            pingIndicator.setImageResource(pingIndicatorRes)

            itemView.setAsStub()
        }
    }
}