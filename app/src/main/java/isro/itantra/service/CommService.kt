package isro.itantra.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import isro.itantra.MainActivity
import isro.itantra.comm.CommApp

/**
 * Foreground service that keeps the comm stack (link + TTS queue) alive so the
 * receiver keeps working with the screen off / app backgrounded. Playback and
 * alerts live here — hardware back/home cannot stop an alert mid-play.
 */
class CommService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // bind the comm singleton to the service lifetime — until now nothing but
        // the Activity held it, so "keeps working with the screen off" relied on
        // the process simply not being reclaimed
        CommApp.get(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val n = buildNotification()
                // microphone FGS type requires in-use mic eligibility (foreground UI
                // launch); binder/shell starts would crash — include mic type only
                // when this process actually holds RECORD_AUDIO
                // Android 14+ grants the microphone FGS type only while mic use is
                // "in use" (capture started from foreground UI) — otherwise the
                // startForeground call throws and crash-loops the service
                // specialUse covers the relay itself; add microphone only while
                // capture is in use (Android 14+ validates eligibility)
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                val micOk = micActive &&
                    checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                if (micOk) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                // the typed overload is API 29+; on 26–28 it does not exist and the
                // service crashed on start (minSdk is 26)
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIF_ID, n, types)
                } else {
                    startForeground(NOTIF_ID, n)
                }
            }
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("iTantra listening")
            .setContentText("Offline voice relay active — tap to open")
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "iTantra relay", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        /** set true by MainActivity while TalkSession capture is active */
        @Volatile var micActive: Boolean = false

        private const val CHANNEL_ID = "itantra_relay"
        private const val NOTIF_ID = 1
        private const val TAG = "ITANTRA"

        fun start(context: Context) {
            val i = Intent(context, CommService::class.java)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, CommService::class.java).setAction(ACTION_STOP)
            )
        }

        private const val ACTION_STOP = "isro.itantra.STOP_RELAY"
    }
}
