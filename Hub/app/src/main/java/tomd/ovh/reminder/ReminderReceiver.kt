package tomd.ovh.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import tomd.ovh.data.AppConfig
import tomd.ovh.data.ReminderState
import tomd.ovh.data.UsageStats
import tomd.ovh.data.WatchedApps
import tomd.ovh.data.startOfTodayMs

/**
 * Woken by [ReminderScheduler] once per interval, does its round, reschedules itself.
 *
 * The cycle is deliberately simple, with no service and no in-memory state:
 *
 *  1. reschedule **before** doing any work, so the process can die without
 *     breaking the chain
 *  2. if reminders are disabled, stop
 *  3. if we may not post, or lack the read permission, stop
 *  4. for every visible app, compare its daily total with the last reminder
 *  5. switch to midnight: move the references onto the current total, without notifying
 *
 * A receiver rather than a service: Android restarts the process for us, and we do
 * not have to handle any lifecycle.
 *
 * ⚠️ Every early exit logs *why* it exited. A silent return is indistinguishable
 * from "no reminder needed", and the two look identical from the outside. Filter
 * logcat on `HubReminder` to see what actually happened.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val config = AppConfig.from(context)

        // Alarms are lost on reboot: the phone forgets them.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (config.isReminderEnabled()) {
                ReminderScheduler.scheduleNext(context, config.reminderIntervalMs())
                Log.d(TAG, "boot: reminders re-armed")
            } else {
                Log.d(TAG, "boot: reminders disabled, nothing re-armed")
            }
            return
        }

        Log.d(
            TAG,
            "alarm fired — enabled=${config.isReminderEnabled()} " +
                "interval=${config.reminderIntervalMs() / 60_000L}min"
        )

        if (!config.isReminderEnabled()) {
            Log.w(TAG, "STOP: reminders disabled")
            return
        }

        val intervalMs = config.reminderIntervalMs()

        // Before any work: if the process is killed for lack of memory during a
        // disk read, the next wakeup already exists.
        ReminderScheduler.scheduleNext(context, intervalMs)

        if (!Reminder.canNotify(context)) {
            Log.w(TAG, "STOP: POST_NOTIFICATIONS not granted — notify() would be a no-op")
            return
        }
        if (!UsageStats.hasPermission(context)) {
            Log.w(TAG, "STOP: PACKAGE_USAGE_STATS not granted")
            return
        }

        val state = ReminderState.from(context)
        val today = startOfTodayMs()

        // ⚠️ What distinguishes this logic from a plain threshold comparison:
        // the first pass of the day triggers NO notification. Otherwise, installing
        // the app at 11am would show "3h42 today" for the very first time. The
        // reference starts from the *current* total.
        //
        // ⚠️ Side effect: a reinstall wipes hub_reminder, so lastResetDay() falls back
        // to NO_BASELINE and isNewDay stays true for the whole rest of the day.
        // Nothing is ever notified in that day. This is the "I reinstalled and it went
        // quiet" case.
        val isNewDay = state.lastResetDay() != today
        Log.d(TAG, "isNewDay=$isNewDay (lastResetDay=${state.lastResetDay()}, today=$today)")

        for (app in WatchedApps.all) {
            if (!config.isVisible(app.packageName)) {
                Log.d(TAG, "${app.label}: hidden in settings, skipped")
                continue
            }

            val totalMs = UsageStats.todayForegroundMs(context, app.packageName)
            val baseline = state.baseline(app.packageName)
            val delta = if (baseline == ReminderState.NO_BASELINE) -1L else totalMs - baseline

            val shouldNotify = !isNewDay &&
                baseline != ReminderState.NO_BASELINE &&
                delta >= intervalMs

            Log.d(
                TAG,
                "${app.label}: total=${totalMs / 60_000L}min " +
                    "baseline=${if (baseline == ReminderState.NO_BASELINE) "none" else "${baseline / 60_000L}min"} " +
                    "delta=${delta / 60_000L}min threshold=${intervalMs / 60_000L}min " +
                    "-> ${if (shouldNotify) "NOTIFY" else "wait"}"
            )

            if (shouldNotify) {
                Reminder.notify(context, app, totalMs)
            }

            // ⚠️ The baseline only moves on notify, on a new day, or when it was never
            // set. It deliberately does NOT move on a "wait": that is what lets the
            // delta accumulate across several intervals, since the alarm period and
            // the threshold are the same value and one window can therefore never
            // exceed it on its own.
            if (shouldNotify || isNewDay || baseline == ReminderState.NO_BASELINE) {
                state.setBaseline(app.packageName, totalMs)
            }
        }

        if (isNewDay) state.markResetFor(today)
    }

    private companion object {
        const val TAG = "HubReminder"
    }
}