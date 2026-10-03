package tomd.ovh.data

import androidx.compose.ui.graphics.Color

/**
 * Représente une app qu'on peut lancer depuis le hub.
 *
 * @param label          le nom affiché par défaut, si l'app n'est pas installée
 * @param packageName    l'identifiant unique de l'app, stable et unique sur le Play Store
 * @param frictionMs     durée du délai de friction avant que l'app soit lançable
 * @param trackColor     couleur de la barre non remplie
 * @param progressColor  couleur de la barre remplie
 */
data class WatchedApp(
    val label: String,
    val packageName: String,
    val frictionMs: Long = 10_000L,
    val trackColor: Color = Color(0x1FFFFFFF),
    val progressColor: Color = Color.White,
)

/** La liste des apps cibles. Pour l'instant en dur, un écran de réglages viendra en v1. */
object WatchedApps {
    val all = listOf(
        WatchedApp(
            label = "Instagram",
            packageName = "com.instagram.android",
            trackColor = Color(0xFF202020),
            progressColor = Color(0xFF303030),
        ),
        // Feurstagram : l'APK Instagram officiel patché (github.com/jean-voila/FeurStagram).
        // Variante « clone », installée à côté de l'officiel → package distinct.
        WatchedApp(
            label = "Feurstagram",
            packageName = "com.instagram.android.feurstagram",
            trackColor = Color(0xFF202020),
            progressColor = Color(0xFF303030),
        ),
        WatchedApp(
            label = "TikTok",
            packageName = "com.zhiliaoapp.musically",
            trackColor = Color(0xFF202020),
            progressColor = Color(0xFF303030),
        ),
        WatchedApp(
            label = "X",
            packageName = "com.twitter.android",
            trackColor = Color(0xFF202020),
            progressColor = Color(0xFF303030),
        ),
    )
}