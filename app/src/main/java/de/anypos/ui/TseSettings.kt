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
import de.anypos.tse.TlsPinning
import de.anypos.tse.TseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.security.cert.X509Certificate

private val Danger = Color(0xFFB71C1C)

/** Epson-TSE: Verbindung, TLS, Zugangsdaten, Schreibschutz (nur Admin, Einstellungen sind Admin-Bereich). */
@Composable
fun TseEpsonSection(container: AppContainer, user: User, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val values by container.settings.values.collectAsState()
    val epson = (values[Key.TSE_MODE.name] ?: Key.TSE_MODE.default) == "epson"
    val writeOn = values[Key.TSE_WRITE_ENABLED.name] == "true"
    val tlsOn = values[Key.TSE_TLS.name] != "false"
    val fingerprint = values[Key.TSE_TLS_FINGERPRINT.name].orEmpty()
    val pinSet = !values[Key.TSE_TIMEADMIN_PIN.name].isNullOrBlank()
    val secretSet = !values[Key.TSE_SHARED_SECRET.name].isNullOrBlank()

    var details by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmWrite by remember { mutableStateOf(false) }
    var confirmPlain by remember { mutableStateOf(false) }
    var offeredCert by remember { mutableStateOf<X509Certificate?>(null) }
    var askPin by remember { mutableStateOf(false) }
    var askSecret by remember { mutableStateOf(false) }

    /** Läuft im Hintergrund; Fehler landen als Meldung, nie als Absturz. */
    fun run(block: suspend () -> Unit) = scope.launch {
        busy = true
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { onMessage(e.message ?: e.toString()) } finally { busy = false }
    }

    suspend fun setting(key: Key, value: String, auditDetails: String) {
        container.settings.put(key, value)
        container.audit.log(user.name, "TSE_EINSTELLUNG", auditDetails)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("TSE-Host: ${container.tseHost()}:${container.tsePort()} ${if (tlsOn) "(TLS)" else "(UNVERSCHLÜSSELT)"} – Host/Port nach „Speichern“ wirksam",
            style = MaterialTheme.typography.bodySmall)

        // --- TLS
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = tlsOn, onCheckedChange = { on ->
                if (!on) confirmPlain = true else scope.launch { setting(Key.TSE_TLS, "true", "TLS ein") }
            })
            if (tlsOn) Text("Verschlüsselt (TLS)") else Text("Ohne TLS – nur für Mock/Development-TSE", color = Danger, fontWeight = FontWeight.Bold)
        }
        if (tlsOn) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(enabled = epson && !busy, onClick = {
                run { offeredCert = TlsPinning.fetchCertificate(container.tseHost(), container.tsePort()) }
            }) { Text(if (fingerprint.isEmpty()) "Zertifikat des Druckers bestätigen" else "Zertifikat neu bestätigen") }
            Text(if (fingerprint.isEmpty()) "noch nicht bestätigt – keine Verbindung möglich" else "bestätigt: ${fingerprint.take(23)}…",
                style = MaterialTheme.typography.bodySmall, color = if (fingerprint.isEmpty()) Danger else Color.Unspecified)
        }

        // --- Zugangsdaten
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { askPin = true }) { Text("TimeAdmin-PIN setzen") }
            Text(if (pinSet) "gesetzt" else "nicht gesetzt", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { askSecret = true }) { Text("Shared Secret setzen") }
            Text(if (secretSet) "eigenes Secret gesetzt" else "Werkseinstellung (EPSONKEY)", style = MaterialTheme.typography.bodySmall)
            if (secretSet) TextButton(onClick = { scope.launch { setting(Key.TSE_SHARED_SECRET, "", "Shared Secret zurückgesetzt") } }) { Text("zurücksetzen") }
        }
        Text("Admin-PIN und PUK der TSE werden nie gespeichert.", style = MaterialTheme.typography.bodySmall)

        // --- Prüfen
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(enabled = epson && !busy, onClick = {
                run {
                    val tse = container.tse() as EpsonTse
                    try {
                        val info = tse.storageInfo()
                        val clients = try { tse.registeredClients().joinToString() } catch (e: TseException) { "Fehler: ${e.message}" }
                        details = info.details() + ("registeredClientIdList" to clients)
                        container.audit.log(user.name, "TSE_PRUEFUNG", "GetStorageInfo, GetRegisteredClientList")
                    } catch (e: TseException) {
                        details = null
                        throw TseException("TSE nicht erreichbar: ${e.message}")
                    }
                }
            }) { Text(if (busy) "Bitte warten …" else "Verbindung prüfen (nur lesend)") }
            OutlinedButton(enabled = epson && !busy && writeOn, onClick = {
                run {
                    val tse = container.tse() as EpsonTse
                    try {
                        tse.ensureReady()
                        container.audit.log(user.name, "TSE_ANMELDETEST", "erfolgreich")
                        onMessage("Anmeldung erfolgreich: " + tse.status().message)
                    } catch (e: TseException) {
                        container.audit.log(user.name, "TSE_ANMELDETEST", "fehlgeschlagen")
                        throw e
                    }
                }
            }) { Text("Anmeldung testen") }
        }
        if (!writeOn) Text("„Anmeldung testen“ braucht Schreibzugriff (Anmelden und Zeit setzen sind schreibende TSE-Befehle).",
            style = MaterialTheme.typography.bodySmall)

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

        // --- Schreibschutz
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = writeOn, onCheckedChange = { on ->
                if (on) confirmWrite = true else scope.launch {
                    container.settings.put(Key.TSE_WRITE_ENABLED, "false")
                    container.audit.log(user.name, "TSE_SCHREIBSCHUTZ", "aktiviert")
                }
            })
            if (writeOn) Text("Schreibzugriff auf die TSE ERLAUBT", color = Danger, fontWeight = FontWeight.Bold)
            else Text("Schreibschutz aktiv: nur lesende TSE-Aufrufe")
        }
    }

    if (askPin) TextInputDialog("TimeAdmin-PIN der TSE", label = "PIN", numeric = true, password = true, onDismiss = { askPin = false }) { pin ->
        askPin = false
        if (pin.isBlank()) return@TextInputDialog
        scope.launch { setting(Key.TSE_TIMEADMIN_PIN, container.secrets.encrypt(pin), "TimeAdmin-PIN geändert") }
    }
    if (askSecret) TextInputDialog("Shared Secret des Druckers", label = "leer lassen = EPSONKEY", password = true, onDismiss = { askSecret = false }) { secret ->
        askSecret = false
        scope.launch {
            if (secret.isBlank()) setting(Key.TSE_SHARED_SECRET, "", "Shared Secret zurückgesetzt")
            else setting(Key.TSE_SHARED_SECRET, container.secrets.encrypt(secret), "Shared Secret geändert")
        }
    }

    offeredCert?.let { cert ->
        val fp = TlsPinning.fingerprint(cert)
        AlertDialog(
            onDismissRequest = { offeredCert = null },
            title = { Text("Zertifikat des Druckers") },
            text = {
                Text(
                    "Aussteller: ${cert.subjectX500Principal.name}\nGültig bis: ${cert.notAfter}\n\nSHA-256:\n$fp\n\n" +
                        "Nur bestätigen, wenn dies sicher der Kassendrucker ist (Fingerabdruck z. B. mit der Statusseite des " +
                        "Druckers vergleichen). Danach akzeptiert die Kasse nur noch genau dieses Zertifikat.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    offeredCert = null
                    scope.launch { setting(Key.TSE_TLS_FINGERPRINT, fp, "Druckerzertifikat bestätigt $fp") }
                }) { Text("Vertrauen") }
            },
            dismissButton = { TextButton(onClick = { offeredCert = null }) { Text("Abbrechen") } },
        )
    }

    if (confirmPlain) AlertDialog(
        onDismissRequest = { confirmPlain = false },
        title = { Text("TLS ausschalten?") },
        text = {
            Text("Ohne TLS gehen TimeAdmin-PIN und alle TSE-Daten unverschlüsselt durch das Netz und können mitgelesen " +
                "werden. Nur für die Mock-TSE oder eine Development-TSE verwenden – nie für die Produktiv-TSE ohne Zustimmung " +
                "des Betreibers. Die Änderung wird protokolliert.")
        },
        confirmButton = {
            TextButton(onClick = { confirmPlain = false; scope.launch { setting(Key.TSE_TLS, "false", "TLS AUS") } }) {
                Text("Ohne TLS", color = Danger)
            }
        },
        dismissButton = { TextButton(onClick = { confirmPlain = false }) { Text("Abbrechen") } },
    )

    if (confirmWrite) AlertDialog(
        onDismissRequest = { confirmWrite = false },
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
                confirmWrite = false
                scope.launch {
                    container.settings.put(Key.TSE_WRITE_ENABLED, "true")
                    container.audit.log(user.name, "TSE_SCHREIBSCHUTZ", "aufgehoben")
                }
            }) { Text("Erlauben", color = Danger) }
        },
        dismissButton = { TextButton(onClick = { confirmWrite = false }) { Text("Abbrechen") } },
    )
}
