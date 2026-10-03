package tomd.ovh.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import tomd.ovh.R
import tomd.ovh.data.WatchedApp
import tomd.ovh.data.formatDuration

/**
 * Builds and sends the reminder notification.
 *
 * It is **dismissable**: it is a regular notification, not a foreground service
 * one. Nothing obliges us to keep anything permanently on screen — that is the whole
 * point of having dropped the service.
 */
object Reminder {

    private const val CHANNEL_ID = "hub_reminders"

    /**
     * Creates the notification channel.
     *
     * ⚠️ `NotificationChannel` only exists from API 26. Below that, `IMPORTANCE` is
     * a property of the whole app and the channel is ignored.
     *
     * Creating an already existing channel does not reset it: if the user lowered
     * its importance in the system settings, a call here does not put it back up.
     * That is the intended behaviour.
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.reminder_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Are we allowed to post a notification?
     *
     * API 33+: `POST_NOTIFICATIONS` is a runtime permission, so it is absent by
     * default. Without it, `notify()` does not throw — **nothing happens at all**.
     * Hence the need to test before doing any work.
     */
    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true

        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Posts the reminder for [app].
     *
     * The notification id is derived from the package: two reminders for the same
     * app **replace** each other instead of stacking up. An app can therefore never
     * leave a stack of 6 notifications in the shade.
     */
    fun notify(context: Context, app: WatchedApp, todayMs: Long) {
        ensureChannel(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                context.getString(
                    R.string.reminder_title,
                    app.label,
                    formatDuration(todayMs)
                )
            )
            .setContentText(context.getString(R.string.reminder_text))
            // Swipeable, and disappears on tap.
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context)
            .notify(app.packageName.hashCode(), notification)
    }

    /** Clears the pending reminders of every known app. */
    fun clearAll(context: Context, apps: List<WatchedApp>) {
        val manager = NotificationManagerCompat.from(context)
        apps.forEach { manager.cancel(it.packageName.hashCode()) }
    }
}