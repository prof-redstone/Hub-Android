package tomd.ovh.data

import android.content.Context
import android.content.Intent
import android.widget.Toast
import tomd.ovh.R

/**
 * Launches an app by its package name.
 *
 * Returns `false` if the app is not installed, so the caller can warn the user.
 *
 * ⚠️ Since Android 11 (API 30), this function returns `null` even for an installed
 * app as long as that app is not declared in `<queries>` of the AndroidManifest.xml.
 */
fun Context.launchApp(app: WatchedApp): Boolean {
    val intent = packageManager.getLaunchIntentForPackage(app.packageName)
        ?: return false

    startActivity(intent)
    return true
}

/** Variant that shows an error toast if the app is missing. */
fun Context.launchAppOrWarn(app: WatchedApp) {
    if (!launchApp(app)) {
        Toast.makeText(
            this,
            getString(R.string.not_installed, app.label),
            Toast.LENGTH_LONG
        ).show()
    }
}