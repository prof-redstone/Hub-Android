package tomd.ovh.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
}