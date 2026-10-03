package tomd.ovh.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import tomd.ovh.data.AppConfig
import tomd.ovh.data.REMINDER_POLL_INTERVAL_MS
import tomd.ovh.data.ReminderState
import tomd.ovh.data.UsageStats
import tomd.ovh.data.WatchedApps

/**
 * Woken by [ReminderScheduler] once a minute, samples usage, reschedules itself.
 *
 * The rule, per watched app:
 *
 *     notify  ⟺  the app is in the foreground
 *             AND the CURRENT SESSION is at least the threshold long
 *             AND at least the threshold has passed since the last reminder
 *
 * ⚠️ The threshold is on the **session**, not on the day. That is what stops a
 * reminder from landing minutes after the user walked away: leaving the app closes
 * the session, so the next poll sees a brand new session start and the count is
 * back to zero. Nothing has to be reset explicitly — the session start comes from
 * the OS event stream (see `UsageStats.UsageSnapshot.sessionStartMs`), so it cannot
 * drift and it survives missed polls.
 *
 * A receiver rather than a service: Android restarts the process for us, and we do
 * not have to handle any lifecycle.
 *
 * ⚠️ Every early exit and every per-app decision logs *why*. A silent return is
 * indistinguishable from "no reminder needed", and the two look identical from the
 * outside. Filter logcat on `HubReminder` to see what actually happened.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val config = AppConfig.from(context)

        // Alarms are lost on reboot: the phone forgets them.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (config.isReminderEnabled()) {
                ReminderScheduler.scheduleNext(context, REMINDER_POLL_INTERVAL_MS)
                Log.d(TAG, "boot: reminders re-armed")
            } else {
                Log.d(TAG, "boot: reminders disabled, nothing re-armed")
            }
            return
        }

        val thresholdMs = config.reminderThresholdMs()
        Log.d(
            TAG,
            "poll — enabled=${config.isReminderEnabled()} threshold=${thresholdMs / 60_000L}min"
        )

        if (!config.isReminderEnabled()) {
            Log.w(TAG, "STOP: reminders disabled")
            return
        }

        // Before any work: if the process is killed for lack of memory during a
        // disk read, the next wakeup already exists.
        ReminderScheduler.scheduleNext(context, REMINDER_POLL_INTERVAL_MS)

        if (!Reminder.canNotify(context)) {
            Log.w(TAG, "STOP: POST_NOTIFICATIONS not granted — notify() would be a no-op")
            return
        }
        if (!UsageStats.hasPermission(context)) {
            Log.w(TAG, "STOP: PACKAGE_USAGE_STATS not granted")
            return
        }

        val state = ReminderState.from(context)
        val now = System.currentTimeMillis()

        // ONE query for every app, plus who is on screen and since when. Before this,
        // the loop below issued one full-day queryEvents per app.
        val visible = WatchedApps.all.filter { config.isVisible(it.packageName) }
        val snapshot = UsageStats.todaySnapshot(context, visible.map { it.packageName }.toSet())

        for (app in visible) {
            val pkg = app.packageName
            val sessionStart = snapshot.sessionStartMs[pkg]

            // Not on screen: nothing to interrupt. The counter restarts on its own
            // because the next foreground poll yields a different sessionStart.
            if (sessionStart == null) {
                Log.d(TAG, "${app.label}: not in foreground, skipped")
                continue
            }

            val sessionMs = (now - sessionStart).coerceAtLeast(0L)
            val lastNotified = state.lastNotifiedAt(pkg)
            val sinceLastMs = if (lastNotified == ReminderState.NEVER_NOTIFIED) {
                Long.MAX_VALUE
            } else {
                now - lastNotified
            }

            val shouldNotify = sessionMs >= thresholdMs && sinceLastMs >= thresholdMs

            Log.d(
                TAG,
                "${app.label}: session=${sessionMs / 60_000L}min " +
                    "sinceLast=${if (lastNotified == ReminderState.NEVER_NOTIFIED) "never" else "${sinceLastMs / 60_000L}min"} " +
                    "threshold=${thresholdMs / 60_000L}min " +
                    "day=${(snapshot.todayMs[pkg] ?: 0L) / 60_000L}min " +
                    "-> ${if (shouldNotify) "NOTIFY" else "wait"}"
            )

            if (shouldNotify) {
                Reminder.notify(context, app, snapshot.todayMs[pkg] ?: 0L)
                state.markNotifiedAt(pkg, now)
            }
        }
    }

    private companion object {
        const val TAG = "HubReminder"
    }
}