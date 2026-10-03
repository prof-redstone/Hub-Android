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
 * Lecture du temps d'écran via [UsageStatsManager].
 *
 * Nécessite la permission spéciale `PACKAGE_USAGE_STATS`, que l'utilisateur doit
 * activer manuellement dans les réglages du système.
 */
object UsageStats {

    /**
     * La permission a-t-elle été accordée ?
     *
     * `PACKAGE_USAGE_STATS` n'est pas une permission runtime : elle est gérée par
     * AppOps, d'où le test direct plutôt qu'un `checkSelfPermission`.
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

    /** Envoie l'utilisateur dans les réglages pour activer la permission. */
    fun requestPermission(context: Context) {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    /**
     * Millisecondes passées au premier plan sur [packageName] depuis minuit.
     *
     * ⚠️ L'algorithme ne fait PAS la somme de tous les gaps entre ACTIVITY_RESUMED.
     * Android n'émet pas toujours ACTIVITY_PAUSED, et émet parfois plusieurs
     * ACTIVITY_RESUMED d'affilée lors des transitions entre activities internes d'une
     * même app. Une somme naïve compte donc des trous deux fois, ou perd du temps.
     *
     * On tient donc un intervalle ouvert ([resumedAt]) :
     * - RESUMED alors qu'aucun intervalle n'est ouvert  → on ouvre
     * - RESUMED alors qu'un intervalle est déjà ouvert  → on ignore
     * - PAUSED ou STOPPED                            → on ferme et on cumule
     * Un intervalle resté ouvert en fin de flux est cloturé sur `now` (app au premier
     * plan, ou PAUSED jamais émis).
     */
    fun todayForegroundMs(context: Context, packageName: String): Long {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val begin = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val events = usm.queryEvents(begin, System.currentTimeMillis())

        var totalMs = 0L
        var resumedAt: Long? = null

        // hasNextEvent() / getNextEvent(event) : l'API est stateful, on avance
        // dans le flux en réemplissant le même objet Event à chaque itération.
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

/** "1h 12" ou "47min", pour l'affichage au-dessus des boutons. */
fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60

    return if (hours > 0) {
        if (minutes > 0) "${hours}h ${minutes.toString().padStart(2, '0')}"
        else "${hours}h"
    } else {
        "${minutes}min"
    }
}