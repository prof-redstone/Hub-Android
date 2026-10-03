package tomd.ovh.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The reminder interval does double duty: it is both the period between two checks
 * and the amount of screen time to cross before a reminder fires.
 *
 * A single setting, so there is no way to end up with "I check every 5 min" and
 * "I warn after 10 min" disagreeing. The accepted consequence: at 10 min, a
 * reminder actually lands every 10 to 20 min of accumulated usage.
 *
 * ⚠️ Declared top-level rather than inside AppConfig's `companion object`: a
 * companion member has to be imported as `AppConfig.Companion.NAME`, which is
 * verbose. Top-level, `import tomd.ovh.data.REMINDER_INTERVAL_CHOICES_MS` is
 * enough — same approach as `startOfTodayMs` and `formatDuration` in UsageStats.kt.
 */
const val DEFAULT_REMINDER_INTERVAL_MS = 10L * 60_000L

/** The intervals offered in the settings, in milliseconds. */
val REMINDER_INTERVAL_CHOICES_MS = listOf(5L, 10L, 15L, 30L).map { it * 60_000L }

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
        private const val KEY_REMINDER_INTERVAL = "reminder_interval_ms"

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

    fun reminderIntervalMs(): Long = reminder.intervalMs

    fun setReminderIntervalMs(intervalMs: Long) {
        prefs.edit().putLong(KEY_REMINDER_INTERVAL, intervalMs).apply()
        reminder = reminder.copy(intervalMs = intervalMs)
    }

    private fun readReminderFromPrefs() = ReminderSettings(
        enabled = prefs.getBoolean(KEY_REMINDER_ENABLED, false),
        intervalMs = prefs.getLong(KEY_REMINDER_INTERVAL, DEFAULT_REMINDER_INTERVAL_MS),
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
        val intervalMs: Long = DEFAULT_REMINDER_INTERVAL_MS,
    )
}