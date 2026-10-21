package de.anypos.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.anypos.AppContainer
import de.anypos.backup.BackupService
import de.anypos.data.User
import de.anypos.domain.Key
import de.anypos.kiosk.Kiosk
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

private val stampFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("Europe/Berlin"))

/** Datensicherung: Ordner, Passwort, Sofortsicherung, Wiederherstellung. */
@Composable
fun BackupSection(container: AppContainer, user: User, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val values by container.settings.values.collectAsState()
    var askPassword by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var restorePassword by remember { mutableStateOf<String?>(null) }
    var manualPassword by remember { mutableStateOf<Uri?>(null) }

    val tree = values[Key.BACKUP_TREE_URI.name].orEmpty()
    val last = values[Key.BACKUP_LAST.name]?.toLongOrNull()
    val overdue = last == null || System.currentTimeMillis() - last > 7L * 24 * 3600 * 1000

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            container.settings.put(Key.BACKUP_TREE_URI, uri.toString())
            container.audit.log(user.name, "SICHERUNGSORDNER", uri.toString())
        }
    }
    val manualTarget = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) manualPassword = uri
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Datensicherung", style = MaterialTheme.typography.titleMedium)
        Text(
            "Letzte Sicherung: " + (last?.let { stampFmt.format(Instant.ofEpochMilli(it)) } ?: "noch nie"),
            color = if (overdue) Color(0xFFB71C1C) else Color.Unspecified,
        )
        Text("Automatische Sicherung nach jedem Tagesabschluss: " + if (container.backup.hasAutoBackup) "aktiv" else "nicht eingerichtet (Ordner und Passwort festlegen)")
        // "content://…/tree/primary%3ADocuments" -> "Documents"
        val folderName = tree.takeIf { it.isNotBlank() }?.let { Uri.decode(Uri.parse(it).lastPathSegment.orEmpty()).substringAfter(':').ifBlank { "Hauptspeicher" } }
        Text("Ordner: " + (folderName ?: "–") + "  ·  Monatsexport (Kassenbuch, DATEV, DSFinV-K) ebenfalls in diesen Ordner",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { treePicker.launch(null) }) { Text("Sicherungsordner wählen") }
            OutlinedButton(onClick = { askPassword = true }) { Text(if (container.backup.hasPassword()) "Passwort ändern" else "Passwort festlegen") }
            OutlinedButton(onClick = {
                if (container.backup.hasAutoBackup) scope.launch {
                    runCatching { container.backup.autoBackup(user.name) }
                        .onSuccess { onMessage("Sicherung erstellt: $it") }.onFailure { onMessage("Sicherung fehlgeschlagen: ${it.message}") }
                } else manualTarget.launch(BackupService.fileName(container.settings[Key.REGISTER_ID]))
            }) { Text("Jetzt sichern") }
            OutlinedButton(onClick = { restorePicker.launch(arrayOf("*/*")) }) { Text("Wiederherstellen …") }
        }
        Text(
            "Das Passwort sicher (außerhalb der Kasse) aufbewahren – ohne Passwort ist keine Wiederherstellung möglich. " +
                "Sicherungen ersetzen nicht die DSFinV-K-/TSE-Exporte für die Aufbewahrungspflicht.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (askPassword) TextInputDialog("Sicherungspasswort", label = "mind. ${BackupService.MIN_PASSWORD} Zeichen", password = true, onDismiss = { askPassword = false }) { pw ->
        askPassword = false
        scope.launch {
            runCatching { container.backup.setPassword(pw) }
                .onSuccess { container.audit.log(user.name, "SICHERUNGSPASSWORT"); onMessage("Passwort gespeichert") }
                .onFailure { onMessage(it.message ?: "Fehler") }
        }
    }
    manualPassword?.let { uri ->
        TextInputDialog("Passwort für diese Sicherung", label = "mind. ${BackupService.MIN_PASSWORD} Zeichen", password = true, onDismiss = { manualPassword = null }) { pw ->
            manualPassword = null
            if (pw.length < BackupService.MIN_PASSWORD) { onMessage("Passwort zu kurz"); return@TextInputDialog }
            scope.launch {
                runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { container.backup.backupTo(it, pw, user.name) } }
                    .onSuccess { onMessage("Sicherung erstellt") }.onFailure { onMessage("Sicherung fehlgeschlagen: ${it.message}") }
            }
        }
    }
    restoreUri?.let { uri ->
        if (restorePassword == null) {
            TextInputDialog("Passwort der Sicherung", password = true, onDismiss = { restoreUri = null }) { restorePassword = it }
        } else {
            ConfirmDialog(
                "Sicherung wiederherstellen?",
                "Die aktuelle Kassendatenbank wird durch die Sicherung ersetzt. Die bisherige Datenbank bleibt als Kopie auf dem Gerät " +
                    "erhalten. Belege, die nach der Sicherung entstanden sind, sind danach nur noch in dieser Kopie vorhanden. Die App startet neu.",
                confirm = "Wiederherstellen",
                onDismiss = { restoreUri = null; restorePassword = null },
            ) {
                val pw = restorePassword!!
                restoreUri = null; restorePassword = null
                scope.launch {
                    try {
                        context.contentResolver.openInputStream(uri)!!.use { container.backup.prepareRestore(it, pw, user.name) }
                        val restart = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        context.startActivity(restart)
                        exitProcess(0)
                    } catch (e: Exception) {
                        onMessage("Wiederherstellung nicht möglich: ${e.message}")
                    }
                }
            }
        }
    }
}

/** Kioskmodus ein-/ausschalten; nur für Administratoren erreichbar. */
@Composable
fun KioskSection(container: AppContainer, user: User) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val values by container.settings.values.collectAsState()
    val on = values[Key.KIOSK_MODE.name] == "true"
    val owner = remember { Kiosk.isDeviceOwner(context) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Kioskmodus", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = on, onCheckedChange = { v ->
                scope.launch {
                    container.settings.put(Key.KIOSK_MODE, v.toString())
                    container.audit.log(user.name, "KIOSKMODUS", if (v) "ein" else "aus")
                }
            })
            Text(if (on) "aktiv" else "aus")
        }
        Text(
            if (owner) "Gerät ist für den Kioskbetrieb eingerichtet (Geräteeigentümer): volle Sperre, Kasse startet als Startbildschirm."
            else "Ohne Geräteeinrichtung wird die Bildschirmfixierung verwendet (Android fragt einmal nach). " +
                "Für eine vollständige Sperre siehe Anleitung docs/Kioskmodus.md.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
