package com.example.mindwave.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.mindwave.MainActivity
import com.example.mindwave.R

/**
 * Utility to fire stress-threshold notifications.
 */
object StressNotificationHelper {

    private const val CHANNEL_ID = "mindwave_stress_alert"
    private const val NOTIFICATION_ID = 1001

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Stress Likelihood Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Fired when stress likelihood exceeds your configured threshold"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 200, 300)
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    fun fireAlert(context: Context, stressPercent: Int, topFactor: String) {
        // Deep-link directly to stress_detail/-1
        val deepLinkUri = android.net.Uri.parse("mindwave://stress_detail/-1")
        val intent = Intent(Intent.ACTION_VIEW, deepLinkUri, context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("High stress likelihood ($stressPercent%)")
            .setContentText("Main signal: $topFactor. Tap for details & breathing exercise")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(longArrayOf(0, 300, 200, 300))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }
}

