package app.keyshelf

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object SyncAlerts {
    private const val id = 7301
    private const val channel = "sync_review"
    fun show(context: Context, modifiedAt: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channel, "Подтверждение синхронизации", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java)
            .putExtra("sync_review", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_sync)
            .setContentTitle("KeyShelf: изменения с компьютера")
            .setContentText("Изменена " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(modifiedAt)) + ". Принять изменения?")
            .setContentIntent(open).setOnlyAlertOnce(true).setAutoCancel(true).build()
        // The in-app review remains available if system notifications are disabled.
        runCatching { manager.notify(id, notification) }
    }
    fun clear(context: Context) { context.getSystemService(NotificationManager::class.java).cancel(id) }
}
