package tomd.ovh.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import tomd.ovh.R
import tomd.ovh.data.WatchedApp
import tomd.ovh.data.formatDuration

/**
 * Construction et envoi de la notification de rappel.
 *
 * Elle est **dismissable** : c'est une notification normale, pas celle d'un service
 * premier plan. Rien ne nous oblige à laisser quoi que ce soit à l'écran en
 * permanence — c'est tout l'intérêt d'avoir abandonné le service.
 */
object Reminder {

    private const val CHANNEL_ID = "hub_reminders"

    /**
     * Créé le canal de notification.
     *
     * ⚠️ `NotificationChannel` n'existe qu'à partir de l'API 26. En dessous,
     * `IMPORTANCE` est une propriété de l'app entière et le canal est ignoré.
     *
     * Créer un canal déjà existant ne le réinitialise pas : si l'utilisateur a baissé
     * son importance dans les réglages système, un appel ici ne le remit pas au
     * dessus. C'est le comportement voulu.
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.reminder_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * A-t-on le droit de poster une notification ?
     *
     * API 33+ : `POST_NOTIFICATIONS` est une permission runtime, donc absente par
     * défaut. Sans elle, `notify()` ne lève pas d'exception — **il ne se passe
     * strictement rien**. D'où la nécessité de tester avant de travailler.
     */
    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true

        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Poste le rappel pour [app].
     *
     * L'identifiant de notification est dérivé du package : deux rappels pour la
     * même app se **remplacent** au lieu de s'empiler. Une app ne peut donc jamais
     * laisser une pile de 6 notifications dans le volet.
     */
    fun notify(context: Context, app: WatchedApp, todayMs: Long) {
        ensureChannel(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                context.getString(
                    R.string.reminder_title,
                    app.label,
                    formatDuration(todayMs)
                )
            )
            .setContentText(context.getString(R.string.reminder_text))
            // Balayable, et disparaît au clic.
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context)
            .notify(app.packageName.hashCode(), notification)
    }

    /** Retire les rappels en attente de toutes les apps connues. */
    fun clearAll(context: Context, apps: List<WatchedApp>) {
        val manager = NotificationManagerCompat.from(context)
        apps.forEach { manager.cancel(it.packageName.hashCode()) }
    }
}