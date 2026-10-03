package tomd.ovh.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tomd.ovh.data.AppConfig
import tomd.ovh.data.ReminderState
import tomd.ovh.data.UsageStats
import tomd.ovh.data.WatchedApps
import tomd.ovh.data.startOfTodayMs

/**
 * Réveillé par [ReminderScheduler] une fois par intervalle, fait le tour, se reprogramme.
 *
 * Le cycle est volontairement simple, sans service ni état en mémoire :
 *
 *  1. on reprogramme **avant** de travailler, pour que le process puisse mourir sans
 *     casser la chaîne
 *  2. si le rappel est désactivé, on s'arrête
 *  3. si on n'a pas le droit de poster, ou pas la permission de lecture, on s'arrête
 *  4. pour chaque app visible, on compare son cumul du jour au dernier rappel
 *  5. passage à minuit : on replace les repères sur le cumul du jour, sans notifier
 *
 * Receveur plutôt que service : Android relance le process pour nous, et on n'a
 * pas à gérer de cycle de vie.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val config = AppConfig.from(context)

        // Les alarmes sont perdues au redémarrage : le téléphone les oublie.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (config.isReminderEnabled()) {
                ReminderScheduler.scheduleNext(context, config.reminderIntervalMs())
            }
            return
        }

        if (!config.isReminderEnabled()) return

        val intervalMs = config.reminderIntervalMs()

        // Avant tout travail : si le process est tué par manque de mémoire pendant
        // une lecture disque, le prochain réveil existe déjà.
        ReminderScheduler.scheduleNext(context, intervalMs)

        if (!Reminder.canNotify(context)) return
        if (!UsageStats.hasPermission(context)) return

        val state = ReminderState.from(context)
        val today = startOfTodayMs()

        // ⚠️ Ce qui distingue cette logique d'une simple comparaison à un seuil :
        // le premier passage de la journée NE déclenche aucune notification. Sinon,
        // à 11h on installerait l'app et on recevrait « 3h42 aujourd'hui » pour la
        // première fois. Le repère part du cumul *actuel*.
        val isNewDay = state.lastResetDay() != today

        for (app in WatchedApps.all) {
            if (!config.isVisible(app.packageName)) continue

            val totalMs = UsageStats.todayForegroundMs(context, app.packageName)
            val baseline = state.baseline(app.packageName)

            val shouldNotify = !isNewDay &&
                baseline != ReminderState.NO_BASELINE &&
                totalMs - baseline >= intervalMs

            if (shouldNotify) {
                Reminder.notify(context, app, totalMs)
            }

            if (shouldNotify || isNewDay || baseline == ReminderState.NO_BASELINE) {
                state.setBaseline(app.packageName, totalMs)
            }
        }

        if (isNewDay) state.markResetFor(today)
    }
}