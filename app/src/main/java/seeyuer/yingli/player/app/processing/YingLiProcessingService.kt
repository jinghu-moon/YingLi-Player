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
import seeyuer.yingli.player.app.YingLiApplication
import seeyuer.yingli.player.core.common.AppLogEvent
import seeyuer.yingli.player.core.common.AppLogLevel
import seeyuer.yingli.player.core.common.LogValue

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

    /**
     * F24：Android 15 起前台服务类型 `mediaProcessing` 的每日运行时长用尽时，系统会调用这里，
     * 并要求服务在**几秒内**停止，否则 ANR。
     *
     * 收尾语义（三段，顺序不能换）：
     * 1. 先立起「本进程不能再提供处理用前台服务」的闸门——它必须早于第 2 步，否则第 2 步写出的
     *    失败事件会触发调度器重新取任务，而此刻已经无法再起前台服务。
     * 2. 把正在执行的任务标记为失败。状态是 `FAILED("FOREGROUND_SERVICE_TIMEOUT")` 而不是
     *    `CANCELED`：这不是用户的取消，用户必须在任务中心看到真实原因。
     * 3. 撤下前台通知并停止服务。
     *
     * 不做的事：不删用户数据、不删已经提交的输出、不自动重试。排队中的任务保持 `QUEUED`，
     * 闸门只在本次进程内有效，下次启动自然复位（配额是每 24 小时计算，不是每个进程）。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        foregroundTimeExhausted = true
        val container = (application as YingLiApplication).container
        val controller = (application as YingLiApplication).mediaContainer.processingController
        controller.failRunning(TIMEOUT_ERROR_CODE)
        container.logger.log(
            AppLogLevel.ERROR,
            AppLogEvent(
                "PROCESSING_FOREGROUND_TIMEOUT",
                "Foreground service time limit reached; running processing tasks were failed.",
                mapOf(
                    "startId" to LogValue.Public(startId.toString()),
                    "foregroundServiceType" to LogValue.Public(fgsType.toString()),
                ),
            ),
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

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

        /** F24 的超时错误码；调用方与状态机共用同一个字面量（`[A-Z][A-Z0-9_]+`）。 */
        const val TIMEOUT_ERROR_CODE = "FOREGROUND_SERVICE_TIMEOUT"

        /** 「本进程的前台服务配额已用尽」。只在内存中，进程重启即复位。 */
        @Volatile
        private var foregroundTimeExhausted = false

        /**
         * 供调度条件读取：为真时不得再启动新的处理任务。
         *
         * 闸门放在服务这一侧而不是调度器里，因为「配额用尽」这个事实只由 Android 通过
         * `onTimeout` 告诉服务；调度器只是它的一个读者。
         */
        fun isForegroundTimeExhausted(): Boolean = foregroundTimeExhausted

        fun setActive(context: Context, active: Boolean) {
            val intent = Intent(context, YingLiProcessingService::class.java).setAction(
                if (active) ACTION_START else ACTION_STOP,
            )
            // 起不来不是可以放过的异常，而是「现在没有服务」这个状态的另一种说法：
            // - 前台服务配额用尽后 startForegroundService 会抛
            //   ForegroundServiceStartNotAllowedException（IllegalStateException 的子类）；
            // - 服务已经因为超时停掉、进程退到后台后 startService 会抛 IllegalStateException。
            // 两者都发生在收尾路径上（超时后任务结束 → onActiveChanged(false)），
            // 若在此抛出会直接崩在调度器的回调里。因此这里只吞掉这两种状态型异常。
            runCatching {
                if (active) context.startForegroundService(intent) else context.startService(intent)
            }
        }
    }
}
