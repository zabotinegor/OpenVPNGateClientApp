package com.yahorzabotsin.openvpnclient.mobile

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.yahorzabotsin.openvpnclient.core.R
import com.yahorzabotsin.openvpnclient.vpn.ConnectionState
import com.yahorzabotsin.openvpnclient.vpn.DefaultNotificationProvider
import com.yahorzabotsin.openvpnclient.vpn.DisconnectReceiver
import com.yahorzabotsin.openvpnclient.vpn.NotificationProvider

class MobileNotificationProvider : NotificationProvider {
    override fun ensureChannel(context: Context) {
        // Reuse default implementation (channel name/desc live in core strings)
        DefaultNotificationProvider.ensureChannel(context)
    }

    override fun buildNotification(context: Context, state: ConnectionState): Notification {
        // Start with default notification content
        val base = DefaultNotificationProvider.buildNotification(context, state)

        // Enrich with a Disconnect action (future control buttons can be added similarly)
        val stopIntent = Intent(context, DisconnectReceiver::class.java)
        val stopPending = PendingIntent.getBroadcast(
            context,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(context, DefaultNotificationProvider.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_public_24)
            .setContentTitle(base.extras?.getCharSequence(Notification.EXTRA_TITLE) ?: base.extras?.getCharSequence(Notification.EXTRA_TITLE_BIG))
            .setContentText(base.extras?.getCharSequence(Notification.EXTRA_TEXT))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .addAction(0, context.getString(R.string.stop_connection), stopPending)
            .setContentIntent(base.contentIntent)
            .build()
    }
}

