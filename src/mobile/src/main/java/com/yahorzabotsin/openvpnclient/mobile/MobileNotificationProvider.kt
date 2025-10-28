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

    override fun buildNotification(context: Context, state: ConnectionState, content: String?): Notification {
        val title = when (state) {
            ConnectionState.CONNECTING -> context.getString(R.string.vpn_notification_title_connecting)
            ConnectionState.CONNECTED -> context.getString(R.string.vpn_notification_title_connected)
            ConnectionState.DISCONNECTING -> context.getString(R.string.vpn_notification_title_disconnecting)
            ConnectionState.DISCONNECTED -> context.getString(R.string.vpn_notification_title_disconnected)
        }
        val defaultText = when (state) {
            ConnectionState.CONNECTING -> context.getString(R.string.vpn_notification_text_connecting)
            ConnectionState.CONNECTED -> context.getString(R.string.vpn_notification_text_connected)
            ConnectionState.DISCONNECTING -> context.getString(R.string.vpn_notification_text_disconnecting)
            ConnectionState.DISCONNECTED -> context.getString(R.string.vpn_notification_text_disconnected)
        }
        val text = content ?: defaultText

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                0,
                it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or (
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
                )
            )
        }

        val stopIntent = Intent(context, DisconnectReceiver::class.java)
        val stopPending = PendingIntent.getBroadcast(
            context,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )
        )

        val builder = NotificationCompat.Builder(context, DefaultNotificationProvider.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_public_24)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setContentIntent(contentIntent)

        if (content != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }

        if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING) {
            builder.addAction(0, context.getString(R.string.stop_connection), stopPending)
        }

        return builder.build()
    }
}
