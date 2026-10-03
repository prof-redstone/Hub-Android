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

/** The two destinations of the graph. An object, not raw strings scattered around. */
private object Route {
    const val HUB = "hub"
    const val SETTINGS = "settings"
}

/**
 * The app shell: a single `Scaffold`, a single top bar, one `NavHost`.
 *
 * The top bar is driven by the current destination, so both screens share the same
 * `TopAppBar` instead of stacking two of them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubApp() {
    val context = LocalContext.current
    val navController = rememberNavController()

    // A single instance for the whole session: both screens read the same state, so a
    // change made in the settings is already visible back on the Hub screen.
    val config = remember { AppConfig.from(context) }

    // currentBackStackEntryAsState() is a State: the bar recomposes on every
    // navigation. Without this `by`, the title would stay frozen on "Hub".
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

    // Incremented on every return to the foreground. Used as a `remember` key inside
    // AppButton, this resets every timer at once.
    var resetKey by remember { mutableIntStateOf(0) }

    var hasPermission by remember { mutableStateOf(UsageStats.hasPermission(context)) }
    var usageMs by remember { mutableStateOf(emptyMap<String, Long>()) }

    val lifecycleOwner = remember(context) { context.findActivity() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resetKey++
                // The user may have granted the permission from the settings in between.
                hasPermission = UsageStats.hasPermission(context)

                // Safety net for the alarms: the receiver reschedules itself, but
                // Android drops alarms on reboot, on a force-stop, and when the user
                // clears the app's storage. Opening Hub repairs those cases.
                //
                // ⚠️ hasPending() guard, and this matters: a blind reschedule would
                // push the next check back by a full interval every single time the
                // app is opened. Since that check is the only way usage gets noticed,
                // opening Hub repeatedly would silently delay every reminder. This
                // re-arms only when there is genuinely nothing pending.
                if (config.isReminderEnabled() && !ReminderScheduler.hasPending(context)) {
                    ReminderScheduler.scheduleNext(context, config.reminderIntervalMs())
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // queryEvents() is a disk read: we keep it out of the function body.
    LaunchedEffect(resetKey, hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        usageMs = WatchedApps.all.associate { app ->
            app.packageName to UsageStats.todayForegroundMs(context, app.packageName)
        }
    }

    // `config` is read here, in the body of the composition: that is what makes the
    // list recompose when an app is ticked or unticked in the settings.
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
 * Friction button: the first tap starts a countdown, the second tap (once the bar
 * is full) opens the app.
 *
 * @param label       the displayed label, already resolved by [AppConfig.labelOf].
 * @param remainingMs milliseconds left. 0 = ready to launch.
 * @param running     true while the countdown runs.
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

    // Relaunched on every change of `running`. The coroutine is cancelled
    // automatically if the button leaves the screen.
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
            // CenterStart = the bar grows from the left.
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