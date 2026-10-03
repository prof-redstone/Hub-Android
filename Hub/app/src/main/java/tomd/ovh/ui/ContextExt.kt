package tomd.ovh.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity

/**
 * Walks up to the Activity that holds this Context.
 *
 * The return type is `ComponentActivity` and not `android.app.Activity`: it is
 * `ComponentActivity` that implements `LifecycleOwner`, so only it exposes
 * `.lifecycle`. Returning the parent type would break the build — the Kotlin
 * compiler reasons on the **declared** type, not on the actual object.
 *
 * `LocalContext.current` is almost always a `ContextWrapper` (the Activity context
 * wraps the application context), hence the walk up the `baseContext` chain.
 */
internal tailrec fun Context.findActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("Context with no Activity: $this")
}