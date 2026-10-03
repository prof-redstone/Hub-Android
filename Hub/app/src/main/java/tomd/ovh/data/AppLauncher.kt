package tomd.ovh.data

import android.content.Context
import android.content.Intent
import android.widget.Toast
import tomd.ovh.R

/**
 * Lance une app par son package name.
 *
 * Renvoie `false` si l'app n'est pas installée, pour que l'appelant puisse prévenir l'utilisateur.
 *
 * ⚠️ Depuis Android 11 (API 30), cette fonction renvoie `null` même pour une app installée
 * tant que celle-ci n'est pas déclarée dans `<queries>` du AndroidManifest.xml.
 */
fun Context.launchApp(app: WatchedApp): Boolean {
    val intent = packageManager.getLaunchIntentForPackage(app.packageName)
        ?: return false

    startActivity(intent)
    return true
}

/** Variante qui affiche un toast d'erreur si l'app est absente. */
fun Context.launchAppOrWarn(app: WatchedApp) {
    if (!launchApp(app)) {
        Toast.makeText(
            this,
            getString(R.string.not_installed, app.label),
            Toast.LENGTH_LONG
        ).show()
    }
}