package tomd.ovh.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import java.util.Calendar

/**
 * Reads screen time through [UsageStatsManager].
 *
 * Requires the special `PACKAGE_USAGE_STATS` permission, which the user has to
 * enable manually in the system settings.
 */
object UsageStats {

    /**
     * Has the permission been granted?
     *
     * `PACKAGE_USAGE_STATS` is not a runtime permission: it is managed through
     * AppOps, hence the direct test rather than a `checkSelfPermission`.
     */
    fun hasPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Sends the user to the settings to enable the permission. */
    fun requestPermission(context: Context) {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    /**
     * One query returning everything we need about the watched apps at once:
     * the day's accumulated foreground time, who is on screen right now, and when
     * the current session started.
     *
     * ⚠️ The algorithm does NOT sum every gap between ACTIVITY_RESUMED events.
     * Android does not always emit ACTIVITY_PAUSED, and sometimes emits several
     * ACTIVITY_RESUMED in a row when transitioning between activities inside the
     * same app. A naive sum therefore counts some gaps twice, or loses time.
     *
     * We instead keep an open interval ([openSince]):
     * - RESUMED while no interval is open  -> open one
     * - RESUMED while an interval is open -> ignore it
     * - PAUSED or STOPPED                -> close it and accumulate
     * An interval still open at the end of the stream is closed on `now`, and those
     * leftovers are precisely the apps the user is looking at right now.
     */
    fun todaySnapshot(context: Context, packages: Set<String>): UsageSnapshot {
        val wanted = packages.toSet()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val events = usm.queryEvents(startOfTodayMs(), System.currentTimeMillis())

        val totals = mutableMapOf<String, Long>()
        val openSince = mutableMapOf<String, Long>()

        // hasNextEvent() / getNextEvent(event): the API is stateful, we walk the
        // stream by refilling the same Event object on each iteration.
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)

            val pkg = event.packageName
            if (pkg !in wanted) continue

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    // Only the first RESUMED opens an interval. Android sometimes
                    // emits several in a row when transitioning between activities
                    // inside the same app; counting each of them would double count.
                    if (pkg !in openSince) openSince[pkg] = event.timeStamp
                }

                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    openSince.remove(pkg)?.let { started ->
                        totals[pkg] = (totals[pkg] ?: 0L) + (event.timeStamp - started)
                    }
                }
            }
        }

        // Whatever is still open after the last event: the app is on screen now.
        val inForeground = openSince.keys.toSet()
        val now = System.currentTimeMillis()
        openSince.forEach { (pkg, started) ->
            totals[pkg] = (totals[pkg] ?: 0L) + (now - started).coerceAtLeast(0L)
        }

        return UsageSnapshot(totals, inForeground, openSince.toMap())
    }
}

/**
 * One pass over the day's events, shared by every app we care about.
 *
 * ⚠️ Why one shared call: querying per app means one full-day `queryEvents` per app
 * — 4 identical scans of the same stream, all reading the same rows. This does it
 * once and distributes the result, which is what makes a per-minute poll affordable.
 *
 * @param todayMs    accumulated foreground time per package, since midnight.
 * @param inForeground the packages whose last event was RESUMED with no matching
 *        PAUSED/STOPPED after it, i.e. the ones the user is on *right now*.
 * @param sessionStartMs for each package in [inForeground], the timestamp of the
 *        RESUMED that opened the current session. This is the *authoritative*
 *        session length: it comes from the OS event stream, so it does not drift and
 *        it cannot be lost if one of our polls is missed.
 *
 *        ⚠️ Why the query covers the whole day and not just the last few minutes: a
 *        session open for 45 minutes emits no event in the last 10, so a bounded
 *        window would report "not in the foreground" for an app the user is looking
 *        at right now. Truncating the window corrupts the very signal we poll for.
 *
 * ⚠️ Caveat on [inForeground]: if Android never emits PAUSED/STOPPED for an app that
 * left the foreground (process killed while backgrounded), the package stays flagged
 * as foreground. It is a strong signal, not a proof.
 */
data class UsageSnapshot(
    val todayMs: Map<String, Long>,
    val inForeground: Set<String>,
    /** Start of the current session, for the packages in [inForeground] only. */
    val sessionStartMs: Map<String, Long>,
)

/**
 * Timestamp of the start of the local day (midnight), in milliseconds.
 *
 * `Calendar` rather than `java.time.LocalDate`: `java.time` only exists from
 * API 26 and the project's `minSdk` is 24.
 *
 * Private because it is only the lower bound of the day's event window — which is
 * all we ever want. Resuming from midnight to now would return "no usage at all"
 * for apps the user has been on all day.
 */
private fun startOfTodayMs(): Long =
    Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

/** "1h 12" or "47min", for the display above the buttons. */
fun formatDuration(ms: Long): String {
    // Rounded to the nearest, the way Android's own "Screen time" does it.
    //
    // Truncating would cause two concrete problems:
    //  - a counter stays on "0min" during the first minute of usage, which makes
    //    it look like the measurement is broken
    //  - when firing a reminder on a 10 min threshold, the display would be one
    //    notification behind the time actually spent
    val totalMinutes = (ms + 30_000L) / 60_000L
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60

    return if (hours > 0) {
        if (minutes > 0) "${hours}h ${minutes.toString().padStart(2, '0')}"
        else "${hours}h"
    } else {
        "${minutes}min"
    }
}