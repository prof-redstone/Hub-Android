package tomd.ovh.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity

/**
 * Remonte jusqu'à l'Activity qui contient ce Context.
 *
 * Le type de retour est `ComponentActivity` et non `android.app.Activity` : c'est
 * `ComponentActivity` qui implémente `LifecycleOwner`, donc lui seul expose
 * `.lifecycle`. Retourner le type parent ferait échouer la compilation — le
 * compilateur Kotlin raisonne sur le type **déclaré**, pas sur l'objet réel.
 *
 * `LocalContext.current` est presque toujours un `ContextWrapper` (le contexte
 * d'Activity enveloppe le contexte d'application), d'où la remontée de la chaîne
 * `baseContext`.
 */
internal tailrec fun Context.findActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("Context sans Activity : $this")
}