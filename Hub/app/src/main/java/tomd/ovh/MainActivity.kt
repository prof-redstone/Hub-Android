package tomd.ovh

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
import tomd.ovh.data.AppConfig
import tomd.ovh.data.WatchedApp
import tomd.ovh.data.WatchedApps
import tomd.ovh.data.UsageStats
import tomd.ovh.data.formatDuration
import tomd.ovh.data.launchAppOrWarn
import tomd.ovh.reminder.ReminderScheduler
import tomd.ovh.ui.SettingsScreen
import tomd.ovh.ui.findActivity
import tomd.ovh.ui.theme.HubTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HubTheme {
                HubApp()
            }
        }
    }
}

/** Les deux destinations du graphe. Un objet, pas des string-crues dispersées. */
private object Route {
    const val HUB = "hub"
    const val SETTINGS = "settings"
}

/**
 * Coquille de l'app : une seule `Scaffold`, une seule barre du haut, un `NavHost`.
 *
 * La barre du haut est pilotée par la destination courante, donc les deux écrans
 * partagent le même `TopAppBar` au lieu d'en empiler deux.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubApp() {
    val context = LocalContext.current
    val navController = rememberNavController()

    // Une seule instance pour toute la session : les deux écrans lisent le même état,
    // donc un changement dans les réglages est déjà visible sur le Hub au retour.
    val config = remember { AppConfig.from(context) }

    // currentBackStackEntryAsState() est un State : la barre se recompose à chaque
    // navigation. Sans ce `by`, le titre resterait figé sur « Hub ».
    val backStackEntry by navController.currentBackStackEntryAsState()
    val onSettings = backStackEntry?.destination?.route == Route.SETTINGS

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(
                            if (onSettings) R.string.settings_title else R.string.hub_title
                        )
                    )
                },
                navigationIcon = {
                    if (onSettings) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                    }
                },
                actions = {
                    if (!onSettings) {
                        IconButton(onClick = { navController.navigate(Route.SETTINGS) }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.action_settings)
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Route.HUB,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Route.HUB) {
                HubScreen(config = config)
            }
            composable(Route.SETTINGS) {
                SettingsScreen(config = config)
            }
        }
    }
}

@Composable
fun HubScreen(
    config: AppConfig,
    modifier: Modifier = Modifier,
) {
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

                // Filet de sécurité des alarmes : le receiver se reprogramme lui-même,
                // mais Android perd les alarmes au redémarrage, et l'utilisateur peut
                // vider le stockage de l'app. Ouvrir Hub répare les deux cas.
                if (config.isReminderEnabled()) {
                    ReminderScheduler.scheduleNext(context, config.reminderIntervalMs())
                }
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

    // `config` est lu ici, dans le corps de la composition : c'est ce qui fait
    // recomposer la liste quand une app est cochée ou décochée dans les réglages.
    val visibleApps = WatchedApps.all.filter { config.isVisible(it.packageName) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!hasPermission) {
            PermissionBanner(onClick = { UsageStats.requestPermission(context) })
        }

        if (visibleApps.isEmpty()) {
            Text(
                text = stringResource(R.string.hub_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        for (app in visibleApps) {
            AppButton(
                label = config.labelOf(app),
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
            text = stringResource(R.string.permission_usage_stats),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * Bouton à friction : un premier clic lance un compte à rebours, le second clic
 * (une fois la barre pleine) ouvre l'app.
 *
 * @param label       libellé affiché, déjà résolu par [AppConfig.labelOf].
 * @param remainingMs millisecondes restantes. 0 = prêt à lancer.
 * @param running     true pendant le compte à rebours.
 */
@Composable
fun AppButton(
    label: String,
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
                text = label,
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
        HubScreen(config = AppConfig.from(LocalContext.current))
    }
}