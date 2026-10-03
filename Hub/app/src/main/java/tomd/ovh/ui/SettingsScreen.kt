package tomd.ovh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tomd.ovh.R
import tomd.ovh.data.AppConfig
import tomd.ovh.data.WatchedApp
import tomd.ovh.data.WatchedApps

/**
 * Page de personnalisation : pour chaque app surveillée, un interrupteur de visibilité
 * et un champ libre pour le libellé du bouton.
 *
 * L'écran ne lit que [AppConfig] et n'écrit nulle part ailleurs : toute modification
 * est persistée immédiatement par `AppConfig`, donc l'écran Hub est à jour au retour.
 */
@Composable
fun SettingsScreen(
    config: AppConfig,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )

        // key = packageName : sans ça, LazyColumn recycle les lignes par position et
        // le champ texte afficherait le libellé de l'app voisine pendant le défilement.
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(24.dp),
            // weight(1f) = occupe la place restante et devient scrollable.
            // Sans lui, la liste se borne à sa hauteur de contenu et déborde du bas.
            modifier = Modifier.weight(1f)
        ) {
            items(WatchedApps.all, key = { it.packageName }) { app ->
                AppSettingRow(app = app, config = config)
            }
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