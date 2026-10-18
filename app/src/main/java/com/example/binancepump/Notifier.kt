package com.example.binancepump

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.math.BigDecimal

object Notifier {
    private const val CHANNEL_SERVICE = "monitor_service"
    private const val CHANNEL_ALERTS = "pump_alerts"
    const val FOREGROUND_ID = 1

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Monitoring status", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Pump alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts when a futures coin pumps"
                enableVibration(true)
            }
        )
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Persistent notification required for the foreground service. */
    fun buildServiceNotification(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Binance Pump Alerts")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context))
            .build()

    @SuppressLint("MissingPermission")
    fun updateServiceNotification(context: Context, text: String) {
        if (!canNotify(context)) return
        NotificationManagerCompat.from(context)
            .notify(FOREGROUND_ID, buildServiceNotification(context, text))
    }

    /** The actual pump alert shown to the user, with the coin ticker in the title. */
    @SuppressLint("MissingPermission")
    fun sendPumpAlert(
        context: Context,
        symbol: String,
        changePercent: Double,
        price: Double,
        windowMinutes: Int
    ) {
        if (!canNotify(context)) return

        val priceText = BigDecimal.valueOf(price).stripTrailingZeros().toPlainString()
        val title = "$symbol  +${"%.2f".format(changePercent)}%"
        val text = "Price rose ${"%.2f".format(changePercent)}% in $windowMinutes min. Current price: $priceText"

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()

        // One notification per symbol: a newer alert for the same coin replaces the old one.
        NotificationManagerCompat.from(context).notify(symbol.hashCode(), notification)
    }
}
