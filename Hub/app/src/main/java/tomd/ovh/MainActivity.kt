package tomd.ovh

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import tomd.ovh.data.WatchedApp
import tomd.ovh.data.WatchedApps
import tomd.ovh.data.UsageStats
import tomd.ovh.data.formatDuration
import tomd.ovh.data.launchAppOrWarn
import tomd.ovh.ui.theme.HubTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HubTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HubScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun HubScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // Incrémenté à chaque retour au premier plan. Utilisé comme clé de `remember`
    // dans AppButton, ça remet tous les timers à zéro d'un coup.
    var resetKey by remember { mutableIntStateOf(0) }

    var hasPermission by remember { mutableStateOf(UsageStats.hasPermission(context)) }
    var usageMs by remember { mutableStateOf(emptyMap<String, Long>()) }

    val lifecycleOwner = remember(context) { context.findActivity() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resetKey++
                // L'utilisateur a pu accorder la permission dans les réglages entre-temps.
                hasPermission = UsageStats.hasPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // queryEvents() est une lecture disque : on le sort du corps de la fonction.
    LaunchedEffect(resetKey, hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        usageMs = WatchedApps.all.associate { app ->
            app.packageName to UsageStats.todayForegroundMs(context, app.packageName)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Hub",
            style = MaterialTheme.typography.headlineLarge
        )

        if (!hasPermission) {
            PermissionBanner(onClick = { UsageStats.requestPermission(context) })
        }

        for (app in WatchedApps.all) {
            AppButton(
                app = app,
                resetKey = resetKey,
                usageMs = usageMs[app.packageName] ?: 0L,
                onLaunch = { context.launchAppOrWarn(app) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PermissionBanner(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        Text(
            text = "Autoriser la lecture du temps d'écran",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * Bouton à friction : un premier clic lance un compte à rebours, le second clic
 * (une fois la barre pleine) ouvre l'app.
 *
 * @param remainingMs millisecondes restantes. 0 = prêt à lancer.
 * @param running     true pendant le compte à rebours.
 */
@Composable
fun AppButton(
    app: WatchedApp,
    resetKey: Int,
    usageMs: Long,
    onLaunch: () -> Unit,
    modifier: Modifier = Modifier
) {
    var remainingMs by remember(app.packageName, resetKey) { mutableLongStateOf(app.frictionMs) }
    var running by remember(app.packageName, resetKey) { mutableStateOf(false) }

    // Relancé à chaque changement de `running`. La coroutine est annulée
    // automatiquement si le bouton quitte l'écran.
    LaunchedEffect(running, app.packageName) {
        if (running) {
            val end = SystemClock.elapsedRealtime() + app.frictionMs
            while (true) {
                val left = end - SystemClock.elapsedRealtime()
                if (left <= 0L) {
                    remainingMs = 0L
                    running = false
                    break
                }
                remainingMs = left
                delay(32)
            }
        }
    }

    val progress = 1f - (remainingMs.toFloat() / app.frictionMs.toFloat()).coerceIn(0f, 1f)

    Column(modifier = modifier) {
        Text(
            text = formatDuration(usageMs),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(app.trackColor)
                .clickable {
                    if (running) return@clickable

                    if (remainingMs == 0L) {
                        remainingMs = app.frictionMs
                        onLaunch()
                    } else {
                        running = true
                    }
                }
        ) {
            // CentreStart = la barre grandit depuis la gauche.
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .background(app.progressColor)
            )

            Text(
                text = app.label,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HubScreenPreview() {
    HubTheme {
        HubScreen()
    }
}

/**
 * Remonte jusqu'à l'Activity qui contient ce Context.
 *
 * Le type de retour est `ComponentActivity` et non `android.app.Activity` : c'est
 * `ComponentActivity` qui implémente `LifecycleOwner`, donc lui seul expose `.lifecycle`.
 * Retourner le type parent ferait échouer la compilation.
 */
private tailrec fun Context.findActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("Context sans Activity : $this")
}