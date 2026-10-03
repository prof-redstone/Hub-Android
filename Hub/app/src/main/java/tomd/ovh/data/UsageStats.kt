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
     * Milliseconds spent in the foreground on [packageName] since midnight.
     *
     * ⚠️ The algorithm does NOT sum every gap between ACTIVITY_RESUMED events.
     * Android does not always emit ACTIVITY_PAUSED, and sometimes emits several
     * ACTIVITY_RESUMED in a row when transitioning between activities inside the
     * same app. A naive sum therefore counts some gaps twice, or loses time.
     *
     * We instead keep an open interval ([resumedAt]):
     * - RESUMED while no interval is open  -> open one
     * - RESUMED while an interval is open -> ignore it
     * - PAUSED or STOPPED                -> close it and accumulate
     * An interval still open at the end of the stream is closed on `now` (app in the
     * foreground, or PAUSED never emitted).
     */
    fun todayForegroundMs(context: Context, packageName: String): Long {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val events = usm.queryEvents(startOfTodayMs(), System.currentTimeMillis())

        var totalMs = 0L
        var resumedAt: Long? = null

        // hasNextEvent() / getNextEvent(event): the API is stateful, we walk the
        // stream by refilling the same Event object on each iteration.
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)

            if (event.packageName != packageName) continue

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (resumedAt == null) resumedAt = event.timeStamp
                }

                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    resumedAt?.let { totalMs += event.timeStamp - it }
                    resumedAt = null
                }
            }
        }

        resumedAt?.let { totalMs += (System.currentTimeMillis() - it).coerceAtLeast(0L) }

        return totalMs
    }
}

/**
 * Timestamp of the start of the local day (midnight), in milliseconds.
 *
 * `Calendar` rather than `java.time.LocalDate`: `java.time` only exists from
 * API 26 and the project's `minSdk` is 24.
 *
 * The value serves as a day identifier: two calls within the same day return the
 * same timestamp. That is how we detect the switch to midnight, without having to
 * format a date or compare it as a string.
 */
fun startOfTodayMs(): Long =
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