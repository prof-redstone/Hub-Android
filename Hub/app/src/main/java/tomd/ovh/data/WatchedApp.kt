package tomd.ovh.data

import androidx.compose.ui.graphics.Color

/**
 * Represents an app that can be launched from the hub.
 *
 * @param label          the default displayed name, used if the app is not installed
 * @param packageName    the app's unique identifier, stable and unique on the Play Store
 * @param frictionMs     duration of the friction delay before the app becomes launchable
 * @param trackColor     colour of the unfilled bar
 * @param progressColor  colour of the filled bar
 */
data class WatchedApp(
    val label: String,
    val packageName: String,
    val frictionMs: Long = 10_000L,
    val trackColor: Color = Color(0x1FFFFFFF),
    val progressColor: Color = Color.White,
)

/** The list of target apps. Hardcoded for now, a settings screen will come in v1. */
object WatchedApps {
    val all = listOf(
        WatchedApp(
            label = "Instagram",
            packageName = "com.instagram.android",
            trackColor = Color(0xFF202020),
            progressColor = Color(0xFF303030),
        ),
        // Feurstagram: the official Instagram APK, patched (github.com/jean-voila/FeurStagram).
        // A "clone" variant, installed alongside the official one → distinct package.
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