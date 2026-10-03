package tomd.ovh.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How much *continuous* screen time in one session before we interrupt the user.
 *
 * Measured against the current session, not against the day's total: quitting the
 * app closes the session, so the next time they open it the count starts from zero.
 * That is the whole point — a reminder that lands minutes after they walked away is
 * pure noise.
 *
 * ⚠️ Declared top-level rather than inside AppConfig's `companion object`: a
 * companion member has to be imported as `AppConfig.Companion.NAME`, which is
 * verbose. Top-level, `import tomd.ovh.data.REMINDER_THRESHOLD_CHOICES_MS` is
 * enough — same approach as `formatDuration` in UsageStats.kt.
 */
private const val DEFAULT_REMINDER_THRESHOLD_MS = 10L * 60_000L

/** The thresholds offered in the settings, in milliseconds. */
val REMINDER_THRESHOLD_CHOICES_MS = listOf(5L, 10L, 15L, 30L).map { it * 60_000L }

/**
 * How often usage is sampled. **Independent from the user's threshold.**
 *
 * ⚠️ Why these are two different values: the threshold answers "how much screen
 * time before we warn", the poll interval answers "how precisely can we notice".
 * Tying them together was a mistake — with both at 10 min, the alarm can only ever
 * observe usage *after* the threshold has already been crossed, so every reminder
 * arrived late by up to a full interval. That is exactly what made the notification
 * land minutes after the user had left the app.
 *
 * Polling faster than the threshold means the warning fires while the app is still
 * in the foreground, which is the only moment where interrupting the user is any
 * use.
 *
 * Battery: one exact alarm per minute. Doze caps exact alarms at roughly one per 9
 * minutes per app, so this cadence only actually materialises while the device is
 * awake and in use — which is exactly when the reminder is worth having. Each poll
 * is a single shared `queryEvents` over the day (see `UsageStats.todaySnapshot`).
 * Raise it if it shows up in the battery stats; the only thing lost is how late a
 * reminder can land.
 */
const val REMINDER_POLL_INTERVAL_MS = 1L * 60_000L

/**
 * User customisation: which apps to show, and under what label.
 *
 * Deliberately kept separate from [WatchedApp]. `WatchedApp` is the *static*
 * definition of an app (package, friction, colours), frozen in the code. `AppConfig`
 * is the *override* layer on top of it, written by the user and persisted on disk.
 *
 * C++ analogy: the compile-time table stays constant, the user config is an override
 * file loaded on top at startup and re-read as it changes.
 *
 * ⚠️ The getters (`isVisible`, `labelOf`) read a `mutableStateOf`, so they must be
 * called *during* a composition, never inside a `LaunchedEffect`: it is that read
 * which triggers recomposition when something is written.
 */
@Stable
class AppConfig private constructor(private val prefs: SharedPreferences) {

    companion object {
        private const val FILE = "hub_config"
        private const val KEY_VISIBLE = "visible_"
        private const val KEY_LABEL = "label_"
        private const val KEY_REMINDER_ENABLED = "reminder_enabled"

        // ⚠️ The *value* still says "interval" although the setting is now a
        // threshold. Renaming the persisted string would silently reset the user's
        // choice to the default on next launch. Data beats naming; the constant is
        // what matters for readability.
        private const val KEY_REMINDER_THRESHOLD = "reminder_interval_ms"

        /**
         * Builds an instance from a [Context].
         *
         * We take `applicationContext` and not the Activity: an Activity has a short
         * lifetime, and the config has to outlive it.
         */
        fun from(context: Context): AppConfig = AppConfig(
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        )
    }

    /**
     * The Compose state that drives the whole UI.
     *
     * An immutable `Map`, replaced on every write: read lambdas capture the old map,
     * so the snapshot is safe. It is never mutated in place.
     */
    private var entries by mutableStateOf(readAllFromPrefs())
        private set

    /**
     * The reminder preferences, held in Compose state — **for the same reason as
     * `entries`**.
     *
     * `SharedPreferences` is not a `MutableState`: writing to it triggers no
     * recomposition. Without this mirrored state, the switch and the chips would
     * keep showing the old value and you would have to leave the screen to see
     * the change take effect.
     *
     * Subscribing to `SharedPreferences` is not an option either: it exposes no
     * observation flow. Hence the mirrored copy inside the snapshot.
     */
    private var reminder by mutableStateOf(readReminderFromPrefs())
        private set

    /** Should this app's button appear on the Hub screen? Default: yes. */
    fun isVisible(packageName: String): Boolean =
        entries[packageName]?.visible ?: true

    /** The custom label if it is non-blank, otherwise the one from the definition. */
    fun labelOf(app: WatchedApp): String =
        entries[app.packageName]?.label?.takeIf { it.isNotBlank() } ?: app.label

    /** The label exactly as typed. Blank = no override. Used by the text field. */
    fun customLabelOf(app: WatchedApp): String =
        entries[app.packageName]?.label.orEmpty()

    fun setVisible(packageName: String, visible: Boolean) =
        update(packageName) { it.copy(visible = visible) }

    /**
     * ⚠️ The input is stored **verbatim**, without `trim()`.
     *
     * The text field is controlled: its value re-reads `customLabelOf`. If we
     * normalised here, typing a trailing space would delete the character under
     * the cursor on every keystroke.
     */
    fun setLabel(packageName: String, label: String) =
        update(packageName) { it.copy(label = label) }

    /**
     * Are screen time reminders enabled?
     *
     * The switch is driven from the settings screen, but this is also the gate for
     * [tomd.ovh.reminder.ReminderReceiver]: the receiver does nothing at all while
     * this is `false`.
     */
    fun isReminderEnabled(): Boolean = reminder.enabled

    fun setReminderEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REMINDER_ENABLED, enabled).apply()
        reminder = reminder.copy(enabled = enabled)
    }

    fun reminderThresholdMs(): Long = reminder.thresholdMs

    fun setReminderThresholdMs(thresholdMs: Long) {
        prefs.edit().putLong(KEY_REMINDER_THRESHOLD, thresholdMs).apply()
        reminder = reminder.copy(thresholdMs = thresholdMs)
    }

    private fun readReminderFromPrefs() = ReminderSettings(
        enabled = prefs.getBoolean(KEY_REMINDER_ENABLED, false),
        thresholdMs = prefs.getLong(KEY_REMINDER_THRESHOLD, DEFAULT_REMINDER_THRESHOLD_MS),
    )

    private fun update(packageName: String, block: (Entry) -> Entry) {
        val updated = block(entries[packageName] ?: Entry())
        prefs.edit()
            .putBoolean(KEY_VISIBLE + packageName, updated.visible)
            .putString(KEY_LABEL + packageName, updated.label)
            .apply()
        entries = entries + (packageName to updated)
    }

    /** Startup read: only the known apps are loaded. */
    private fun readAllFromPrefs(): Map<String, Entry> =
        WatchedApps.all.associate { app ->
            app.packageName to Entry(
                visible = prefs.getBoolean(KEY_VISIBLE + app.packageName, true),
                label = prefs.getString(KEY_LABEL + app.packageName, "").orEmpty(),
            )
        }

    private data class Entry(
        val visible: Boolean = true,
        val label: String = "",
    )

    /**
     * Reminder preferences.
     *
     * A `data class` so that `mutableStateOf` compares by structural equality:
     * writing an identical value then triggers no wasted recomposition.
     */
    private data class ReminderSettings(
        val enabled: Boolean = false,
        val thresholdMs: Long = DEFAULT_REMINDER_THRESHOLD_MS,
    )
}