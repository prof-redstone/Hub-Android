package tomd.ovh.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import tomd.ovh.R
import tomd.ovh.data.AppConfig
import tomd.ovh.data.REMINDER_INTERVAL_CHOICES_MS
import tomd.ovh.data.UsageStats
import tomd.ovh.data.WatchedApp
import tomd.ovh.data.WatchedApps
import tomd.ovh.reminder.Reminder
import tomd.ovh.reminder.ReminderScheduler

/**
 * Customisation screen.
 *
 * Two sections: the screen time reminders, then the apps themselves. The screen
 * only reads [AppConfig] and writes nowhere else — every change is persisted
 * immediately, so the Hub screen is up to date on the way back.
 */
@Composable
fun SettingsScreen(
    config: AppConfig,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Same reason as for the prefs: `canNotify` and `canReadUsage` are raw reads of
    // a system API, with no state behind them. Without these `mutableStateOf`, the
    // warning banners would stay on screen after the permission was granted, and
    // the user would conclude the button did nothing.
    var canNotify by remember { mutableStateOf(Reminder.canNotify(context)) }
    var canReadUsage by remember { mutableStateOf(UsageStats.hasPermission(context)) }

    // The usage access permission is granted in the Android settings: there is no
    // return callback. ON_RESUME is the only reliable signal — it fires when the
    // user comes back from the system settings.
    val lifecycleOwner = remember(context) { context.findActivity() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canNotify = Reminder.canNotify(context)
                canReadUsage = UsageStats.hasPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Requested at the moment the user enables reminders, never cold at launch: a
    // permission asked for without context is denied outright.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> canNotify = granted }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(24.dp),
            // weight(1f) = takes the remaining space and becomes scrollable.
            // Without it, the list is limited to its content height and overflows
            // the bottom.
            modifier = Modifier.weight(1f)
        ) {
            item {
                ReminderSection(
                    context = context,
                    config = config,
                    canNotify = canNotify,
                    canReadUsage = canReadUsage,
                    onRequestNotification = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onRequestUsage = { UsageStats.requestPermission(context) }
                )
            }

            item { HorizontalDivider() }

            item {
                Text(
                    text = stringResource(R.string.settings_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // key = packageName: without it, LazyColumn recycles rows by position and
            // the text field would show the neighbouring app's label while scrolling.
            items(WatchedApps.all, key = { it.packageName }) { app ->
                AppSettingRow(app = app, config = config)
            }
        }
    }
}

@Composable
private fun ReminderSection(
    context: Context,
    config: AppConfig,
    canNotify: Boolean,
    canReadUsage: Boolean,
    onRequestNotification: () -> Unit,
    onRequestUsage: () -> Unit,
) {
    val enabled = config.isReminderEnabled()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.settings_reminder_title),
            style = MaterialTheme.typography.titleMedium
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.settings_reminder_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = enabled,
                onCheckedChange = { isOn ->
                    config.setReminderEnabled(isOn)

                    // Enabling: we schedule the first pass one interval from now.
                    // Disabling: we cancel, and we clear the reminders already posted —
                    // otherwise the notification for an already crossed threshold
                    // would stay in the shade.
                    if (isOn) {
                        ReminderScheduler.scheduleNext(context, config.reminderIntervalMs())
                    } else {
                        ReminderScheduler.cancel(context)
                        Reminder.clearAll(context, WatchedApps.all)
                    }
                }
            )
        }

        if (enabled && canReadUsage) {
            Text(
                text = stringResource(R.string.settings_reminder_interval),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp)
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                for (choice in REMINDER_INTERVAL_CHOICES_MS) {
                    FilterChip(
                        selected = config.reminderIntervalMs() == choice,
                        onClick = {
                            config.setReminderIntervalMs(choice)
                            // We reschedule straight away: the pending alarm is
                            // already on the old interval and cannot be replayed.
                            ReminderScheduler.scheduleNext(context, choice)
                        },
                        label = { Text("${choice / 60_000L} min") }
                    )
                }
            }
        }

        if (enabled && !canNotify) {
            PermissionFix(
                message = stringResource(R.string.settings_reminder_permission_needed),
                buttonLabel = stringResource(R.string.settings_reminder_grant),
                onClick = onRequestNotification
            )
        }

        if (enabled && !canReadUsage) {
            PermissionFix(
                message = stringResource(R.string.settings_reminder_usage_needed),
                buttonLabel = stringResource(R.string.settings_reminder_grant),
                onClick = onRequestUsage
            )
        }
    }
}

@Composable
private fun PermissionFix(
    message: String,
    buttonLabel: String,
    onClick: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        OutlinedButton(onClick = onClick) {
            Text(buttonLabel)
        }
    }
}

@Composable
private fun AppSettingRow(
    app: WatchedApp,
    config: AppConfig,
) {
    val customLabel = config.customLabelOf(app)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // The original name stays visible even if the user renames the app,
                // otherwise there is no telling which one is which.
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = config.isVisible(app.packageName),
                onCheckedChange = { config.setVisible(app.packageName, it) }
            )
        }

        OutlinedTextField(
            value = customLabel,
            onValueChange = { config.setLabel(app.packageName, it) },
            label = { Text(stringResource(R.string.settings_custom_label)) },
            // The original label as a hint: an empty field is not an empty field,
            // it means "no override".
            placeholder = { Text(app.label) },
            singleLine = true,
            trailingIcon = {
                if (customLabel.isNotEmpty()) {
                    IconButton(onClick = { config.setLabel(app.packageName, "") }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.settings_reset_label)
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}