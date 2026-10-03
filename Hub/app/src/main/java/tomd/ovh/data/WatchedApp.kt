package tomd.ovh.data

/**
 * Représente une app qu'on peut lancer depuis le hub.
 *
 * @param label       le nom affiché par défaut, si l'app n'est pas installée
 * @param packageName l'identifiant unique de l'app, stable et unique sur le Play Store
 */
data class WatchedApp(
    val label: String,
    val packageName: String
)

/** La liste des apps cibles. Pour l'instant en dur, un écran de réglages viendra en v1. */
object WatchedApps {
    val all = listOf(
        WatchedApp(label = "Instagram", packageName = "com.instagram.android"),
        WatchedApp(label = "TikTok", packageName = "com.zhiliaoapp.musically"),
        WatchedApp(label = "X", packageName = "com.twitter.android"),
    )
}