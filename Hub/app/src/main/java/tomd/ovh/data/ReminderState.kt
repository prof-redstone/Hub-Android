package tomd.ovh.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Reminder bookkeeping: when we last interrupted the user on each app.
 *
 * Deliberately separate from [AppConfig]. `AppConfig` holds **preferences**, chosen
 * by the user and read by the UI. `ReminderState` holds **runtime state**, written by
 * [tomd.ovh.reminder.ReminderReceiver] and never read by the UI.
 *
 * ⚠️ This used to hold three concepts — a day marker plus a per-app baseline — to
 * implement "10 min *since the last notification, accumulated over the day*". The
 * threshold now sits on the **current session** instead, and that made all three
 * redundant:
 *
 *  - the day marker existed to avoid announcing "3h42 today" as the very first
 *    reminder of the day. With a per-session threshold that cannot happen: a fresh
 *    session always starts at zero, so the first reminder of a day needs 10 min of
 *    actual use like any other.
 *  - the baseline only ever needed to move on notify, which is exactly what
 *    [lastNotifiedAt] records.
 *  - the reset-on-leave needed no storage at all. The session start comes from the
 *    OS event stream, so leaving the app and coming back produces a *new* start
 *    timestamp and the counter restarts by itself.
 *
 * One Long per app is the whole remaining state.
 */
class ReminderState private constructor(private val prefs: SharedPreferences) {

    companion object {
        private const val FILE = "hub_reminder"
        private const val KEY_LAST_NOTIFIED = "last_notified_"

        /** The "never notified" marker for this app. */
        const val NEVER_NOTIFIED = -1L

        fun from(context: Context): ReminderState = ReminderState(
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        )
    }

    /**
     * Wall-clock instant of the last reminder posted for [packageName].
     *
     * Used only to space reminders out *within* one long session, so that a user
     * who keeps scrolling gets nagged every threshold rather than once.
     *
     * ⚠️ Wall clock, not elapsedRealtime: this has to survive a reboot. The cost is
     * that a manual clock change can stretch or squash the spacing. Acceptable for a
     * nag timer; would not be for a billing one.
     */
    fun lastNotifiedAt(packageName: String): Long =
        prefs.getLong(KEY_LAST_NOTIFIED + packageName, NEVER_NOTIFIED)

    fun markNotifiedAt(packageName: String, atMs: Long) {
        prefs.edit().putLong(KEY_LAST_NOTIFIED + packageName, atMs).apply()
    }
}