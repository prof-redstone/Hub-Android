package tomd.ovh.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * Schedules the next wakeup.
 *
 * We use `AlarmManager` rather than a loop: a loop would require a foreground
 * service, hence a permanent notification — which the user does not want. Here the
 * process is killed when Android decides so, and restarted at the exact time.
 */
object ReminderScheduler {

    /**
     * A single request code for every scheduling.
     *
     * A `PendingIntent`'s identity is the triplet (action, data, requestCode).
     * Both alarms must therefore share these three values, otherwise they
     * accumulate instead of replacing each other: the previous request would keep
     * firing the receiver twice.
     */
    private const val REQUEST_CODE = 4201

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ReminderReceiver::class.java),
            // UPDATE_CURRENT: rewrites the existing alarm with the updated extras.
            // IMMUTABLE: mandatory since API 31, and carries no risk here — the
            // receiver reads no parameter from the intent.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * Schedules the next poll in [delayMs].
     *
     * ⚠️ This is the *poll* delay, never the user's threshold. See
     * `REMINDER_POLL_INTERVAL_MS` for why the two are separate.
     *
     * `setExactAndAllowWhileIdle`: fires even while the device is dozing, which is
     * what sets this alarm apart from ordinary ones. Under Doze, Android spaces out
     * exact wakeups to roughly one every 9 minutes — a shorter delay would be
     * pointless, the alarm would simply be pushed back.
     *
     * `ELAPSED_REALTIME_WAKEUP`: counts from boot, so it is immune to a change of
     * system time or time zone. `WAKEUP`: wakes the CPU.
     */
    fun scheduleNext(context: Context, delayMs: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return

        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        manager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAt,
            pendingIntent(context)
        )
        Log.d(TAG, "scheduleNext(): next poll in ${(triggerAt - SystemClock.elapsedRealtime()) / 1000}s")
    }

    /** Cancels the scheduled wakeup, if there is one. */
    fun cancel(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        manager.cancel(pendingIntent(context))
        Log.d(TAG, "cancel(): pending alarm cleared")
    }

    /**
     * Is a wakeup already pending?
     *
     * ⚠️ Built around `FLAG_NO_CREATE`: it returns `null` instead of creating the
     * PendingIntent. That is the only way to ask "does this alarm exist?" without
     * side effect — and PendingIntent offers no `exists()`.
     *
     * Used to turn `scheduleNext` from a blind reset into a repair: a blind reset
     * pushes the next check back by a full interval every single time the app is
     * opened, which silently delays reminders.
     */
    fun hasPending(context: Context): Boolean =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) != null

    private const val TAG = "HubReminder"
}