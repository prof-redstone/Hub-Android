package tomd.ovh.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Carnet de bord du rappel : ce qu'on a déjà signalé à l'utilisateur, et quand.
 *
 * Volontairement séparé de [AppConfig]. `AppConfig` contient des **préférences**,
 * choisies par l'utilisateur et lues par l'UI. `ReminderState` contient de l'**état
 * d'exécution**, écrit par le [tomd.ovh.reminder.ReminderReceiver] et jamais lu par
 * l'UI. Mélanger les deux obligerait l'écran de réglages à écrire dans le carnet.
 */
class ReminderState private constructor(private val prefs: SharedPreferences) {

    companion object {
        private const val FILE = "hub_reminder"
        private const val KEY_DAY = "day"
        private const val KEY_BASELINE = "baseline_"

        /** Marqueur « jamais signalé » pour cette app. */
        const val NO_BASELINE = -1L

        fun from(context: Context): ReminderState = ReminderState(
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        )
    }

    /** Timestamp du dernier passage à minuit. Voir [startOfTodayMs]. */
    fun lastResetDay(): Long = prefs.getLong(KEY_DAY, NO_BASELINE)

    fun markResetFor(day: Long) {
        prefs.edit().putLong(KEY_DAY, day).apply()
    }

    /**
     * Cumul du jour au moment du dernier rappel de cette app.
     *
     * On ne compare pas le cumul à un seuil absolu mais à cette référence : c'est ce
     * qui produit un intervalle régulier même si on masque l'app une demi-journée.
     */
    fun baseline(packageName: String): Long =
        prefs.getLong(KEY_BASELINE + packageName, NO_BASELINE)

    fun setBaseline(packageName: String, totalMs: Long) {
        prefs.edit().putLong(KEY_BASELINE + packageName, totalMs).apply()
    }

    /**
     * Repart de zéro pour toutes les apps connues.
     *
     * ⚠️ `SharedPreferences` n'offre pas d'énumérer ses clés. On parcourt donc
     * `WatchedApps.all` plutôt que de faire `prefs.all.keys`. Conséquence : le
     * repère d'une app retirée de la liste reste sur le disque. Sans conséquence
     * ici — le receiver ne le lit plus jamais — mais à savoir si on rend la liste
     * des apps dynamique.
     */
    fun clearBaselines() {
        val editor = prefs.edit()
        WatchedApps.all.forEach { editor.remove(KEY_BASELINE + it.packageName) }
        editor.apply()
    }
}