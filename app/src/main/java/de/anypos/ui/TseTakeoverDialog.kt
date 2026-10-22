package de.anypos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.anypos.AppContainer
import de.anypos.data.User
import de.anypos.domain.Key
import de.anypos.tse.EpsonTse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Admin-Ablauf „TSE übernehmen“ (TSE-Entwicklung.md Schritt 7): registrierte Kassen lesen, diese Kasse
 * registrieren, bei Bedarf TSE an diesen Drucker binden, Bereitschaft prüfen.
 *
 * Jede Aktion ist eine eigene kurze Admin-Sitzung (anmelden → Aktion → abmelden). Die Admin-PIN lebt nur in
 * diesem Dialog und wird beim Schließen verworfen; sie wird nie gespeichert oder protokolliert.
 * Kein SetUp, kein Löschen, keine Änderung des Shared Secret (das folgt erst nach dem Umzug).
 */
@Composable
fun TseTakeoverDialog(container: AppContainer, user: User, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val values by container.settings.values.collectAsState()
    val registerId = values[Key.REGISTER_ID.name] ?: Key.REGISTER_ID.default
    val epsonMode = (values[Key.TSE_MODE.name] ?: Key.TSE_MODE.default) == "epson"
    val tlsOk = values[Key.TSE_TLS.name] == "false" || !values[Key.TSE_TLS_FINGERPRINT.name].isNullOrBlank()
    val writeOn = values[Key.TSE_WRITE_ENABLED.name] == "true"
    val pinSet = !values[Key.TSE_TIMEADMIN_PIN.name].isNullOrBlank()

    var approved by remember { mutableStateOf(false) }
    var approvedBy by remember { mutableStateOf("") }
    var adminPin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var clients by remember { mutableStateOf<List<String>?>(null) }
    var otherHost by remember { mutableStateOf(false) }
    var isMock by remember { mutableStateOf<Boolean?>(null) }
    val log = remember { mutableStateOf(listOf<String>()) }
    var confirmRegister by remember { mutableStateOf(false) }
    var confirmBind by remember { mutableStateOf(false) }

    // Admin-PIN beim Verlassen verwerfen
    DisposableEffect(Unit) { onDispose { adminPin = "" } }
    LaunchedEffect(epsonMode) {
        if (epsonMode) isMock = runCatching { (container.tse() as EpsonTse).storageInfo().isMock }.getOrNull()
    }

    val ready = epsonMode && tlsOk && writeOn && pinSet
    val canAct = ready && approved && approvedBy.isNotBlank() && adminPin.length >= 4 && !busy

    fun note(text: String) { log.value = log.value + text }

    /** Eine Aktion als eigene Admin-Sitzung; Ergebnis/Fehler in die Liste und ins Protokoll (ohne PIN). */
    fun admin(label: String, action: suspend EpsonTse.AdminSession.() -> String) = scope.launch {
        busy = true
        try {
            val tse = container.tse() as EpsonTse
            val result = tse.asAdmin(adminPin, action)
            note("✓ $label: $result")
            container.audit.log(user.name, "TSE_UEBERNAHME", "$label: $result (Freigabe: $approvedBy)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            note("✗ $label: ${e.message}")
            container.audit.log(user.name, "TSE_UEBERNAHME", "$label fehlgeschlagen: ${e.message} (Freigabe: $approvedBy)")
        } finally {
            busy = false
        }
    }

    WideDialog(
        title = "TSE übernehmen",
        onDismiss = { adminPin = ""; onDismiss() },
        width = 820.dp,
        buttons = { TextButton(onClick = { adminPin = ""; onDismiss() }) { Text("Schließen") } },
    ) {
        // Höhe an den Bildschirm anpassen, damit „Schließen“ immer sichtbar bleibt
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
        Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (isMock == true) Text("Mock-TSE – Übung ohne echte TSE", color = Color(0xFF1565C0), fontWeight = FontWeight.Bold)
            if (isMock == false) Text("ECHTE TSE – jede Aktion wird dauerhaft auf der TSE gespeichert", color = Danger, fontWeight = FontWeight.Bold)

            Text("1. Voraussetzungen", style = MaterialTheme.typography.titleSmall)
            check("Epson-TSE ausgewählt", epsonMode)
            check("Verbindung verschlüsselt (Zertifikat bestätigt) oder bewusst ohne TLS", tlsOk)
            check("Schreibschutz aufgehoben", writeOn)
            check("TimeAdmin-PIN gesetzt", pinSet)
            check("Kassen-ID „$registerId“ für die TSE zulässig", runCatching { EpsonTse.validateClientId(registerId) }.isSuccess)

            Text("2. Freigabe des Betreibers (TSE-Plan, Regel 1)", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = approved, onCheckedChange = { approved = it })
                Text("Die schriftliche Freigabe des Betreibers für Eingriffe an dieser TSE liegt vor.")
            }
            OutlinedTextField(approvedBy, { approvedBy = it }, label = { Text("Freigabe von (Name, Datum)") }, singleLine = true,
                modifier = Modifier.width(420.dp))

            Text("3. Admin-PIN der TSE", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                adminPin, { adminPin = it.filter(Char::isDigit) }, label = { Text("Admin-PIN (wird nicht gespeichert)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.width(320.dp),
            )
            Text("Nur einmal eingeben und nicht raten: Nach drei Fehlversuchen sperrt die TSE die PIN (Entsperren nur mit PUK).",
                style = MaterialTheme.typography.bodySmall)

            Text("4. Aktionen", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = canAct, onClick = {
                    admin("Registrierte Kassen") {
                        val list = registeredClients()
                        clients = list
                        list.joinToString().ifEmpty { "keine" }
                    }
                }) { Text("Registrierte Kassen lesen") }
                OutlinedButton(enabled = canAct, onClick = {
                    admin("Self-Test") {
                        if (selfTestOnThisHost()) "bestanden" else { otherHost = true; "TSE war zuletzt an einem anderen Drucker" }
                    }
                }) { Text("Self-Test") }
            }
            clients?.let { list ->
                if (registerId in list) Text("„$registerId“ ist an der TSE registriert.", color = Color(0xFF2E7D32))
                else OutlinedButton(enabled = canAct, onClick = { confirmRegister = true }) { Text("„$registerId“ registrieren") }
            }
            if (otherHost) OutlinedButton(enabled = canAct, onClick = { confirmBind = true }) { Text("TSE an diesen Drucker binden …") }

            Text("5. Bereitschaft", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(enabled = ready && !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        val tse = container.tse() as EpsonTse
                        tse.ensureReady()
                        val open = tse.startedTransactions()
                        note("✓ Bereitschaft: Anmeldung und Zeit in Ordnung, offene Transaktionen: ${open.joinToString().ifEmpty { "keine" }}")
                        container.audit.log(user.name, "TSE_UEBERNAHME", "Bereitschaft geprüft: in Ordnung")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        note("✗ Bereitschaft: ${e.message}")
                    } finally {
                        busy = false
                    }
                }
            }) { Text("Bereitschaft prüfen (TimeAdmin)") }
            Text("Die TSE prüft die Registrierung erst beim ersten Beleg – ist „$registerId“ oben aufgeführt, passt es.",
                style = MaterialTheme.typography.bodySmall)

            if (log.value.isNotEmpty()) {
                Text("Ergebnis", style = MaterialTheme.typography.titleSmall)
                log.value.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    if (confirmRegister) ConfirmDialog(
        "Kasse an der TSE registrieren?",
        "„$registerId“ wird dauerhaft als Client an dieser TSE registriert. Bestehende Kassen (z. B. JTL-POS) bleiben unverändert.",
        confirm = "Registrieren",
        onDismiss = { confirmRegister = false },
    ) {
        confirmRegister = false
        admin("Registrierung „$registerId“") { if (registerClient(registerId)) { clients = registeredClients(); "registriert" } else "war bereits registriert" }
    }

    if (confirmBind) ConfirmDialog(
        "TSE an diesen Drucker binden?",
        "Nur nötig, wenn die TSE aus einem anderen Drucker umgezogen ist. Danach arbeitet sie nur noch mit diesem Drucker. " +
            "Ein anderes System, das die TSE noch im alten Drucker erwartet, kann sie dann nicht mehr nutzen.",
        confirm = "Binden",
        onDismiss = { confirmBind = false },
    ) {
        confirmBind = false
        admin("SetUpForPrinter") {
            setUpForPrinter()
            if (selfTestOnThisHost()) { otherHost = false; "gebunden, Self-Test bestanden" } else "gebunden, Self-Test meldet weiterhin anderen Host"
        }
    }
}

@Composable
private fun check(label: String, ok: Boolean) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(if (ok) "✓" else "✗", color = if (ok) Color(0xFF2E7D32) else Danger, fontWeight = FontWeight.Bold)
    Text(label)
}
