package seeyuer.yingli.player.app.processing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Build
import seeyuer.yingli.player.R
import seeyuer.yingli.player.app.MainActivity

class YingLiProcessingService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID,
            getString(R.string.processing_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= 35) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.processing_notification_title))
            .setContentText(getString(R.string.processing_notification_message))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "processing"
        private const val NOTIFICATION_ID = 2002
        private const val ACTION_START = "seeyuer.yingli.player.processing.START"
        private const val ACTION_STOP = "seeyuer.yingli.player.processing.STOP"

        fun setActive(context: Context, active: Boolean) {
            val intent = Intent(context, YingLiProcessingService::class.java).setAction(
                if (active) ACTION_START else ACTION_STOP,
            )
            if (active) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
