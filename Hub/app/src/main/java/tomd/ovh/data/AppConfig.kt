package tomd.ovh.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * L'intervalle de rappel sert **deux fois** : c'est la période entre deux
 * vérifications, et le seuil de temps à franchir pour déclencher un rappel.
 *
 * Un seul réglage, donc pas d'incohérence possible entre « je vérifie toutes les
 * 5 min » et « je préviens au bout de 10 min ». La conséquence assumée : à 10 min,
 * un rappel arrive en réalité toutes les 10 à 20 min d'usage cumulé.
 *
 * ⚠️ Déclaré en top-level, pas dans le `companion object` de [AppConfig] : un membre
 * de companion s'importe via `AppConfig.Companion.nom`, ce qui est verbeux. En
 * top-level, `import tomd.ovh.data.REMINDER_INTERVAL_CHOICES_MS` suffit — même
 * approche que `startOfTodayMs` et `formatDuration` dans `UsageStats.kt`.
 */
const val DEFAULT_REMINDER_INTERVAL_MS = 10L * 60_000L

/** Les intervalles proposés dans les réglages, en millisecondes. */
val REMINDER_INTERVAL_CHOICES_MS = listOf(5L, 10L, 15L, 30L).map { it * 60_000L }

/**
 * Personnalisation utilisateur : quelles apps afficher, et sous quel libellé.
 *
 * Volontairement séparée de [WatchedApp]. `WatchedApp` est la définition *statique*
 * d'une app (package, friction, couleurs), figée dans le code. `AppConfig` est la
 * couche d'*override* au-dessus, écrite par l'utilisateur et persistée sur disque.
 *
 * Analogie C++ : la table de compilation reste constante, la config utilisateur est
 * un fichier d'override chargé par-dessus au démarrage et rechargé à chaud.
 *
 * ⚠️ Les getters (`isVisible`, `labelOf`) lisent un `mutableStateOf`. Il faut donc
 * les appeler **pendant** une composition, jamais dans un `LaunchedEffect` : c'est
 * la lecture de cet état qui déclenche la recomposition quand on écrit.
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
         * Construit une instance depuis un [Context].
         *
         * On prend `applicationContext` et non l'Activity : une Activity a une durée
         * de vie courte, et la config doit survivre à sa destruction.
         */
        fun from(context: Context): AppConfig = AppConfig(
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        )
    }

    /**
     * L'état Compose qui pilote toute l'UI.
     *
     * Une `Map` immuable remplacée à chaque écriture : les lambdas de lecture
     * capturent l'ancienne map, donc le snapshot est sûr. On ne mute jamais en place.
     */
    private var entries by mutableStateOf(readAllFromPrefs())
        private set

    /**
     * Les préférences de rappel, dans l'état Compose — **pour la même raison que
     * `entries`**.
     *
     * `SharedPreferences` n'est pas un `MutableState` : écrire dedans ne déclenche
     * aucune recomposition. Sans cet état miroir, l'interrupteur et les chips
     * afficheraient l'ancienne valeur et il faudrait quitter l'écran pour voir la
     * prise en compte.
     *
     * On ne pourrait pas non plus se contenter de `notify()` : un `SharedPreferences`
     * n'a pas de flux d'observation. D'où l'image maintenue dans le snapshot.
     */
    private var reminder by mutableStateOf(readReminderFromPrefs())
        private set

    /** Le bouton de cette app doit-il apparaître sur l'écran Hub ? Défaut : oui. */
    fun isVisible(packageName: String): Boolean =
        entries[packageName]?.visible ?: true

    /** Libellé personnalisé s'il est non vide, sinon celui de la définition. */
    fun labelOf(app: WatchedApp): String =
        entries[app.packageName]?.label?.takeIf { it.isNotBlank() } ?: app.label

    /** Libellé brut tel que saisi. Vide = pas d'override. Utilisé par le champ texte. */
    fun customLabelOf(app: WatchedApp): String =
        entries[app.packageName]?.label.orEmpty()

    fun setVisible(packageName: String, visible: Boolean) =
        update(packageName) { it.copy(visible = visible) }

    /**
     * ⚠️ On stocke la saisie **brute**, sans `trim()`.
     *
     * Le champ texte est contrôlé : sa valeur relit `customLabelOf`. Si on
     * normalisait ici, taper une espace en fin de mot retrancherait le caractère
     * sous le curseur à chaque frappe.
     */
    fun setLabel(packageName: String, label: String) =
        update(packageName) { it.copy(label = label) }

    /**
     * Les rappels de temps d'écran sont-ils activés ?
     *
     * L'interrupteur est piloté depuis les réglages, mais c'est aussi une porte
     * d'entrée pour le [tomd.ovh.reminder.ReminderReceiver] : le receiver ne fait
     * rien du tout tant que c'est `false`.
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

    /** Lecture au démarrage : seules les apps connues sont chargées. */
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
     * Préférences de rappel.
     *
     * `data class` pour que `mutableStateOf` compare par égalité structurelle :
     * réécrire la même valeur ne déclenche alors aucune recomposition inutile.
     */
    private data class ReminderSettings(
        val enabled: Boolean = false,
        val intervalMs: Long = DEFAULT_REMINDER_INTERVAL_MS,
    )
}