package tomd.ovh.data

import android.content.Context
import android.content.SharedPreferences

/**
 * The reminder log book: what we have already told the user, and when.
 *
 * Deliberately kept separate from [AppConfig]. `AppConfig` holds **preferences**,
 * chosen by the user and read by the UI. `ReminderState` holds **runtime state**,
 * written by [tomd.ovh.reminder.ReminderReceiver] and never read by the UI.
 * Mixing the two would force the settings screen to write into the log book.
 */
class ReminderState private constructor(private val prefs: SharedPreferences) {

    companion object {
        private const val FILE = "hub_reminder"
        private const val KEY_DAY = "day"
        private const val KEY_BASELINE = "baseline_"

        /** The "never notified" marker for this app. */
        const val NO_BASELINE = -1L

        fun from(context: Context): ReminderState = ReminderState(
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        )
    }

    /** Timestamp of the last switch to midnight. See [startOfTodayMs]. */
    fun lastResetDay(): Long = prefs.getLong(KEY_DAY, NO_BASELINE)

    fun markResetFor(day: Long) {
        prefs.edit().putLong(KEY_DAY, day).apply()
    }

    /**
     * Today's accumulated time at the moment this app was last reminded about.
     *
     * We do not compare the accumulated time to an absolute threshold but to this
     * reference: that is what yields a regular interval even if you hide the app
     * for half a day.
     */
    fun baseline(packageName: String): Long =
        prefs.getLong(KEY_BASELINE + packageName, NO_BASELINE)

    fun setBaseline(packageName: String, totalMs: Long) {
        prefs.edit().putLong(KEY_BASELINE + packageName, totalMs).apply()
    }

    /**
     * Starts over from zero for every known app.
     *
     * ⚠️ `SharedPreferences` offers no way to enumerate its keys. We therefore walk
     * `WatchedApps.all` instead of using `prefs.all.keys`. Consequence: the
     * reference of an app removed from the list stays on disk. Harmless here — the
     * receiver never reads it again — but worth knowing if the app list ever
     * becomes dynamic.
     */
    fun clearBaselines() {
        val editor = prefs.edit()
        WatchedApps.all.forEach { editor.remove(KEY_BASELINE + it.packageName) }
        editor.apply()
    }
}