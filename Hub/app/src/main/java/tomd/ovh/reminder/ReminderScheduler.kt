package tomd.ovh.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Programmation du prochain réveil.
 *
 * On utilise `AlarmManager` et non une boucle : une boucle exigerait un service
 * premier plan, donc une notification permanente — ce que l'utilisateur ne veut pas.
 * Ici le process est tué quand Android le décide, et relancé à l'heure exacte.
 */
object ReminderScheduler {

    /**
     * Un seul code de requête pour toutes les programmations.
     *
     * L'identité d'un `PendingIntent` est le triplet (action, data, requestCode).
     * Deux alarmes doivent donc partager ces trois valeurs, sinon elles se
     * cumulent au lieu de se remplacer : la requête précédente continuerait de
     * déclencher le receiver en doublon.
     */
    private const val REQUEST_CODE = 4201

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ReminderReceiver::class.java),
            // UPDATE_CURRENT : réécrit l'alarme existante avec les extras mis à jour.
            // IMMUTABLE : obligatoire depuis l'API 31, et sans risque ici — le receiver
            // ne lit aucun paramètre de l'intent.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * Programme le prochain passage dans [intervalMs].
     *
     * `setExactAndAllowWhileIdle` : déclenche même en veille (Doze), ce qui distingue
     * cette.alarme des alarmes normales. En Doze, Android espace les réveils exacts
     * à environ un toutes les 9 minutes — un intervalle plus court ne servirait à rien,
     * l'alarme serait simplement repoussée.
     *
     * `ELAPSED_REALTIME_WAKEUP` : décompte depuis le démarrage, donc insensible à un
     * changement d'heure système ou de fuseau. `WAKUP` : réveille le CPU.
     */
    fun scheduleNext(context: Context, intervalMs: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return

        manager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + intervalMs,
            pendingIntent(context)
        )
    }

    /** Annule le réveil programmé, s'il y en a un. */
    fun cancel(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        manager.cancel(pendingIntent(context))
    }
}