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
 * Page de personnalisation.
 *
 * Deux sections : les rappels de temps d'écran, puis les apps elles-mêmes. L'écran ne
 * lit que [AppConfig] et n'écrit nulle part ailleurs — chaque modification est
 * persistée immédiatement, donc l'écran Hub est à jour au retour.
 */
@Composable
fun SettingsScreen(
    config: AppConfig,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Même raison que pour les prefs : `canNotify` et `canReadUsage` sont des
    // lectures brutes de l'API système, sans état derrière. Sans ces `mutableStateOf`,
    // les bandeaux d'avertissement resteraient affichés après avoir accordé la
    // permission, et l'utilisateur conclurait que le bouton n'a rien fait.
    var canNotify by remember { mutableStateOf(Reminder.canNotify(context)) }
    var canReadUsage by remember { mutableStateOf(UsageStats.hasPermission(context)) }

    // La permission d'usage s'accorde dans les réglages Android : aucun callback de
    // retour. ON_RESUME est le seul signal fiable — il se déclenche quand
    // l'utilisateur revient des réglages système.
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

    // Demandée au moment où l'utilisateur active les rappels, jamais à froid au
    // lancement : une permission demandée sans contexte est refusée d'office.
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
            // weight(1f) = occupe la place restante et devient scrollable.
            // Sans lui, la liste se borne à sa hauteur de contenu et déborde du bas.
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

            // key = packageName : sans ça, LazyColumn recycle les lignes par position et
            // le champ texte afficherait le libellé de l'app voisine pendant le défilement.
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

                    // Activer : on programme le premier passage dans un intervalle.
                    // Désactiver : on annule, et on retire les rappels déjà posés —
                    // sinon la notif d'un quota déjà franchi resterait dans le volet.
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
                            // On reprogramme tout de suite : l'alarme en cours est déjà
                            // à l'ancien intervalle, on ne peut pas la rejouer.
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
                // Le nom d'origine reste visible même si l'utilisateur le renomme,
                // sinon on ne sait plus quelle app est laquelle.
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
            // Le libellé d'origine en exemple : un champ vide n'est pas un champ vide,
            // c'est « pas d'override ».
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