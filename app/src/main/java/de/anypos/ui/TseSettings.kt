package de.anypos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.anypos.AppContainer
import de.anypos.data.User
import de.anypos.domain.Key
import de.anypos.tse.EpsonTse
import de.anypos.tse.TseException
import kotlinx.coroutines.launch

/** Epson-TSE: Verbindung lesend prüfen und Schreibschutz verwalten (nur Admin, Einstellungen sind Admin-Bereich). */
@Composable
fun TseEpsonSection(container: AppContainer, user: User, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val values by container.settings.values.collectAsState()
    val epson = (values[Key.TSE_MODE.name] ?: Key.TSE_MODE.default) == "epson"
    val writeOn = values[Key.TSE_WRITE_ENABLED.name] == "true"
    var details by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("TSE-Host: ${container.tseHost()}:${values[Key.TSE_PORT.name] ?: Key.TSE_PORT.default} (nach „Speichern“ wirksam)",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = epson && !busy, onClick = {
            scope.launch {
                busy = true
                try {
                    val tse = container.tse() as EpsonTse
                    val info = tse.storageInfo()
                    val clients = try { tse.registeredClients().joinToString() } catch (e: TseException) { "Fehler: ${e.message}" }
                    details = info.details() + ("registeredClientIdList" to clients)
                    container.audit.log(user.name, "TSE_PRUEFUNG", "GetStorageInfo, GetRegisteredClientList")
                } catch (e: TseException) {
                    details = null
                    onMessage("TSE nicht erreichbar: ${e.message}")
                } finally {
                    busy = false
                }
            }
        }) { Text(if (busy) "Prüfe …" else "Verbindung prüfen (nur lesend)") }

        details?.let { rows ->
            Column {
                rows.forEach { (k, v) ->
                    Row {
                        Text(k, Modifier.width(240.dp), style = MaterialTheme.typography.bodySmall)
                        Text(v, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = writeOn, onCheckedChange = { on ->
                if (on) confirm = true
                else scope.launch {
                    container.settings.put(Key.TSE_WRITE_ENABLED, "false")
                    container.audit.log(user.name, "TSE_SCHREIBSCHUTZ", "aktiviert")
                }
            })
            if (writeOn) Text("Schreibzugriff auf die TSE ERLAUBT", color = Color(0xFFB71C1C), fontWeight = FontWeight.Bold)
            else Text("Schreibschutz aktiv: nur lesende TSE-Aufrufe")
        }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Schreibzugriff auf die TSE erlauben?") },
        text = {
            Text(
                "Danach darf die Kasse schreibende Befehle an die TSE senden (Anmeldung, Zeit setzen, Clients registrieren, " +
                    "Transaktionen). Nur für die Mock-TSE oder eine Development-TSE verwenden. Für die Produktiv-TSE ist eine " +
                    "schriftliche Freigabe des Betreibers nötig, und es darf keine Testbuchung auf einer TSE erfolgen, die " +
                    "parallel produktiv von JTL-POS genutzt wird. Die Änderung wird protokolliert.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                scope.launch {
                    container.settings.put(Key.TSE_WRITE_ENABLED, "true")
                    container.audit.log(user.name, "TSE_SCHREIBSCHUTZ", "aufgehoben")
                }
            }) { Text("Erlauben", color = Color(0xFFB71C1C)) }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Abbrechen") } },
    )
}
