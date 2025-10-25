package com.yahorzabotsin.openvpnclient.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import com.yahorzabotsin.openvpnclient.core.R

interface NotificationProvider {
    fun ensureChannel(context: Context)
    fun buildNotification(context: Context, state: ConnectionState): Notification
}

object DefaultNotificationProvider : NotificationProvider {
    const val CHANNEL_ID = "vpn_status_channel"

    override fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.vpn_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.vpn_channel_description)
                    enableLights(false)
                    enableVibration(false)
                    setShowBadge(false)
                    lightColor = Color.BLUE
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    override fun buildNotification(context: Context, state: ConnectionState): Notification {
        val title = when (state) {
            ConnectionState.CONNECTING -> context.getString(R.string.vpn_notification_title_connecting)
            ConnectionState.CONNECTED -> context.getString(R.string.vpn_notification_title_connected)
            ConnectionState.DISCONNECTING -> context.getString(R.string.vpn_notification_title_disconnecting)
            ConnectionState.DISCONNECTED -> context.getString(R.string.vpn_notification_title_disconnected)
        }
        val text = when (state) {
            ConnectionState.CONNECTING -> context.getString(R.string.vpn_notification_text_connecting)
            ConnectionState.CONNECTED -> context.getString(R.string.vpn_notification_text_connected)
            ConnectionState.DISCONNECTING -> context.getString(R.string.vpn_notification_text_disconnecting)
            ConnectionState.DISCONNECTED -> context.getString(R.string.vpn_notification_text_disconnected)
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                context,
                0,
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or (
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
                )
            )
        } else null

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_public_24)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .apply { if (contentIntent != null) setContentIntent(contentIntent) }
            .build()
    }
}

