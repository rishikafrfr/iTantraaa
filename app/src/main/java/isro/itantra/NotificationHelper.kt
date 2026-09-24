package isro.itantra

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object NotificationHelper {

    private const val NORMAL_CHANNEL_ID = "itantra_normal_messages"
    private const val ALERT_CHANNEL_ID = "itantra_alert_messages"

    private const val NORMAL_NOTIFICATION_ID = 1001
    private const val ALERT_NOTIFICATION_ID = 1002

    fun createChannels(context: Context) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val normalSound =
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val alertSound =
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

        val normalChannel = NotificationChannel(
            NORMAL_CHANNEL_ID,
            "Normal Messages",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications for normal iTantra messages"
            enableVibration(true)
            setSound(
                normalSound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
            )
        }

        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Alert Messages",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "High priority emergency alerts"
            enableVibration(false)
            setSound(
                alertSound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
            )
        }

        manager.createNotificationChannel(normalChannel)
        manager.createNotificationChannel(alertChannel)
    }

    fun showMessage(
        context: Context,
        message: String,
        isAlert: Boolean
    ) {
        if (
            android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        createChannels(context)

        val channelId =
            if (isAlert) ALERT_CHANNEL_ID else NORMAL_CHANNEL_ID

        val title =
            if (isAlert) "ALERT" else "New Message"

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(message)
            )
            .setPriority(
                if (isAlert)
                    NotificationCompat.PRIORITY_HIGH
                else
                    NotificationCompat.PRIORITY_DEFAULT
            )
            .setAutoCancel(!isAlert)
            .setTimeoutAfter(if (isAlert) 60_000L else 0L)
            .build()

        NotificationManagerCompat
            .from(context)
            .notify(
                if (isAlert)
                    ALERT_NOTIFICATION_ID
                else
                    NORMAL_NOTIFICATION_ID,
                notification
            )
    }
}