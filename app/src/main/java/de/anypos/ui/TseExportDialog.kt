package de.anypos.ui

import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * TSE-Export (TAR) für Steuerberater/Prüfung (TSE-Entwicklung.md Schritt 8). Verlangt Admin-PIN und Freigabe,
 * weil der Export eine Admin-Anmeldung braucht und die TSE für seine Dauer blockiert. Es werden nie Daten
 * gelöscht. Die Datei wird direkt an den gewählten Ort geschrieben; Größe und SHA-256 kommen ins Protokoll.
 */
@Composable
fun TseExportDialog(container: AppContainer, user: User, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val values by container.settings.values.collectAsState()
    val zone = ZoneId.of("Europe/Berlin")
    val writeOn = values[Key.TSE_WRITE_ENABLED.name] == "true"

    var approved by remember { mutableStateOf(false) }
    var approvedBy by remember { mutableStateOf("") }
    var noSale by remember { mutableStateOf(false) }
    var adminPin by remember { mutableStateOf("") }
    var wholeStorage by remember { mutableStateOf(true) }
    var month by remember { mutableStateOf(YearMonth.now(zone).minusMonths(1)) }
    var busy by remember { mutableStateOf(false) }
    var written by remember { mutableLongStateOf(0L) }
    var result by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { adminPin = "" } }

    val canStart = writeOn && approved && approvedBy.isNotBlank() && noSale && adminPin.length >= 4 && !busy
    val period = if (wholeStorage) "gesamt" else month.toString()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-tar")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            written = 0
            result = null
            try {
                if (container.db.receipts().openReceipts().isNotEmpty()) {
                    throw IllegalStateException("Es ist noch ein Vorgang offen – erst abschließen, dann exportieren")
                }
                val tse = container.tse() as EpsonTse
                val from = if (wholeStorage) null else month.atDay(1).atStartOfDay(zone).toInstant()
                val to = if (wholeStorage) null else month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
                val r = context.contentResolver.openOutputStream(uri)?.use { out ->
                    tse.exportTar(adminPin, out, from, to) { written = it }
                } ?: throw IllegalStateException("Datei konnte nicht geöffnet werden")
                result = "✓ TSE-Export ($period) gespeichert: ${"%,d".format(Locale.GERMAN, r.bytes)} Byte\nSHA-256: ${r.sha256}"
                container.audit.log(user.name, "TSE_EXPORT", "${r.function} $period, ${r.bytes} Byte, SHA-256 ${r.sha256} (Freigabe: $approvedBy)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Unvollständige Datei nicht liegen lassen
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                result = "✗ TSE-Export fehlgeschlagen: ${e.message}"
                container.audit.log(user.name, "TSE_EXPORT", "fehlgeschlagen ($period): ${e.message} (Freigabe: $approvedBy)")
            } finally {
                busy = false
            }
        }
    }

    WideDialog(
        title = "TSE-Daten exportieren (TAR)",
        onDismiss = { if (!busy) { adminPin = ""; onDismiss() } },
        width = 820.dp,
        buttons = { TextButton(enabled = !busy, onClick = { adminPin = ""; onDismiss() }) { Text("Schließen") } },
    ) {
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
        Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Der TSE-Export enthält alle signierten Protokolldaten der TSE (auch früherer Kassen am selben Drucker) " +
                "und wird bei einer Prüfung zusammen mit dem DSFinV-K-Export verlangt. Es werden keine Daten gelöscht.",
                style = MaterialTheme.typography.bodySmall)
            Text("Während des Exports ist die TSE für ALLE Kassen gesperrt – nur außerhalb der Öffnungszeiten ausführen.",
                color = Danger, fontWeight = FontWeight.Bold)
            if (!writeOn) Text("Schreibschutz ist aktiv: Der Export braucht eine Admin-Anmeldung und ist daher erst nach Aufheben möglich.",
                color = Danger)

            Text("Umfang", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = wholeStorage, onClick = { wholeStorage = true }, label = { Text("Gesamter Speicher") })
                FilterChip(selected = !wholeStorage, onClick = { wholeStorage = false }, label = { Text("Ein Monat") })
                if (!wholeStorage) {
                    TextButton(onClick = { month = month.minusMonths(1) }) { Text("◀") }
                    Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMAN)))
                    TextButton(onClick = { month = month.plusMonths(1) }) { Text("▶") }
                }
            }

            Text("Freigabe", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = approved, onCheckedChange = { approved = it })
                Text("Die Freigabe des Betreibers für den Export liegt vor.")
            }
            OutlinedTextField(approvedBy, { approvedBy = it }, label = { Text("Freigabe von (Name, Datum)") }, singleLine = true,
                modifier = Modifier.width(420.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = noSale, onCheckedChange = { noSale = it })
                Text("Es wird gerade nicht kassiert – auch nicht an einer anderen Kasse am selben Drucker.")
            }

            OutlinedTextField(
                adminPin, { adminPin = it.filter(Char::isDigit) }, label = { Text("Admin-PIN der TSE (wird nicht gespeichert)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.width(360.dp),
            )

            OutlinedButton(enabled = canStart, onClick = {
                val stamp = LocalDateTime.now(zone).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
                launcher.launch("TSE-Export_${container.settings[Key.REGISTER_ID]}_${period}_$stamp.tar")
            }) { Text("Exportieren und speichern …") }

            if (busy) Text("Export läuft … ${"%,d".format(Locale.GERMAN, written)} Byte geschrieben – bitte warten, Kasse nicht benutzen.")
            result?.let { Text(it, color = if (it.startsWith("✓")) Color(0xFF2E7D32) else Danger) }
        }
    }
}
