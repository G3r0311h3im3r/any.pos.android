package de.anypos.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.platform.LocalContext
import de.anypos.export.CashBook
import de.anypos.export.ExportException
import de.anypos.export.ExportFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.YearMonth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.anypos.AppContainer
import de.anypos.data.User
import de.anypos.domain.ClosingReport
import de.anypos.domain.Money
import de.anypos.domain.TaxSummary
import de.anypos.hardware.DocLine
import de.anypos.hardware.ReceiptDocument
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ClosingScreen(container: AppContainer, user: User) {
    val scope = rememberCoroutineScope()
    val closings by remember { container.db.closings().all() }.collectAsState(initial = emptyList())
    var preview by remember { mutableStateOf<Pair<List<DocLine>, Boolean>?>(null) }
    var counting by remember { mutableStateOf(false) }
    var confirmCounted by remember { mutableStateOf<Long?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var exportDialog by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<ExportFile?>(null) }
    val context = LocalContext.current
    val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("Europe/Berlin"))
    val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"

    fun hints(file: ExportFile) = if (file.warnings.isEmpty()) "" else "\n\nHinweise:\n" + file.warnings.joinToString("\n") { "• $it" }

    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val file = pendingExport
        pendingExport = null
        if (uri != null && file != null) scope.launch {
            runCatching { context.writeUri(uri, file.bytes) }
                .onSuccess {
                    container.audit.log(user.name, "EXPORT_DSFINVK", file.name)
                    message = "DSFinV-K-Export gespeichert." + hints(file)
                }
                .onFailure { message = "Export fehlgeschlagen: ${it.message}" }
        }
    }

    fun doc(report: ClosingReport, final: Boolean) = ReceiptDocument.closing(container.company(), report.closing, final, report.taxBuckets)

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ScreenTitle("Tagesabschluss")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { scope.launch { preview = doc(container.closing.preview(user), false) to false } }) { Text("X-Bon (Zwischenstand)") }
            Button(onClick = { counting = true }) { Text("Z-Bon / Tagesabschluss …") }
            OutlinedButton(onClick = { exportDialog = true }) { Text("DSFinV-K-Export …") }
        }
        Text(
            "Ablauf: Kassensturz durchführen (Bargeld zählen), gezählten Betrag eingeben. Eine Differenz wird als " +
                "Geschäftsvorfall \"DifferenzSollIst\" gebucht. Bargeld für die Bank vorher als \"Geldtransit\" im Kassenbuch erfassen.",
            Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium,
        )
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text("Z-Nr.", Modifier.width(70.dp), style = MaterialTheme.typography.labelLarge)
            Text("Datum", Modifier.width(160.dp), style = MaterialTheme.typography.labelLarge)
            Text("Belege", Modifier.width(140.dp), style = MaterialTheme.typography.labelLarge)
            Text("Umsatz", Modifier.width(120.dp), style = MaterialTheme.typography.labelLarge)
            Text("Bar", Modifier.width(120.dp), style = MaterialTheme.typography.labelLarge)
            Text("Unbar", Modifier.width(120.dp), style = MaterialTheme.typography.labelLarge)
            Text("Differenz", Modifier.width(120.dp), style = MaterialTheme.typography.labelLarge)
        }
        LazyColumn {
            items(closings, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(c.number.toString(), Modifier.width(70.dp))
                    Text(fmt.format(Instant.ofEpochMilli(c.createdAt)), Modifier.width(160.dp))
                    Text("${c.firstReceiptNumber ?: "-"}–${c.lastReceiptNumber ?: "-"}", Modifier.width(140.dp))
                    Text(Money.format(c.totalCents), Modifier.width(120.dp))
                    Text(Money.format(c.cashSalesCents), Modifier.width(120.dp))
                    Text(Money.format(c.cardSalesCents), Modifier.width(120.dp))
                    Text(Money.format(c.differenceCents), Modifier.width(120.dp))
                    TextButton(onClick = {
                        scope.launch {
                            val receipts = container.db.receipts().byClosing(c.id).map { container.pos.load(it.id) }
                            val sales = receipts.filter { it.receipt.state == de.anypos.data.ReceiptState.FINISHED }
                                .flatMap { it.lines }.filter { it.businessCase in de.anypos.data.BusinessCase.taxable }
                            preview = ReceiptDocument.closing(container.company(), c, true, TaxSummary.of(sales)) to true
                        }
                    }) { Text("Anzeigen") }
                }
                HorizontalDivider()
            }
        }
    }

    if (counting) AmountDialog("Gezählter Kassenbestand", onDismiss = { counting = false }) { cents ->
        counting = false
        confirmCounted = cents
    }
    confirmCounted?.let { counted ->
        ConfirmDialog(
            "Tagesabschluss durchführen?",
            "Gezählt: ${Money.formatEur(counted)}. Der Abschluss kann nicht rückgängig gemacht werden.",
            confirm = "Abschließen",
            onDismiss = { confirmCounted = null },
        ) {
            confirmCounted = null
            scope.launch {
                try {
                    val report = container.closing.close(user, counted)
                    val d = doc(report, true)
                    preview = d to true
                    if (container.printer.configured) runCatching { container.printer.print(ReceiptDocument.toEscPos(d, container.printer.columns)) }
                        .onFailure { message = "Abschluss gespeichert, Druck fehlgeschlagen: ${it.message}" }
                    if (container.backup.hasAutoBackup) {
                        runCatching { container.backup.autoBackup(user.name) }
                            .onFailure { message = listOfNotNull(message, "Automatische Sicherung fehlgeschlagen: ${it.message}").joinToString("\n") }
                    } else {
                        message = listOfNotNull(message, "Hinweis: Automatische Datensicherung ist nicht eingerichtet (Einstellungen → Datensicherung).").joinToString("\n")
                    }
                    // Abgeschlossene Vormonate automatisch exportieren (Kassenbuch, DATEV, DSFinV-K)
                    val monthly = runCatching { container.exports.monthlyExport(user.name, deviceModel) }
                        .getOrElse { listOf("Monatsexport fehlgeschlagen: ${it.message}") }
                    if (monthly.isNotEmpty() && container.settings[de.anypos.domain.Key.BACKUP_TREE_URI].isNotBlank()) {
                        message = listOfNotNull(message, monthly.joinToString("\n")).joinToString("\n")
                    }
                } catch (e: Exception) {
                    message = e.message
                }
            }
        }
    }
    preview?.let { (lines, _) ->
        ReceiptPreviewDialog(ReceiptDocument.toText(lines, container.printer.columns), onDismiss = { preview = null }) {
            TextButton(onClick = {
                scope.launch { runCatching { container.printer.print(ReceiptDocument.toEscPos(lines, container.printer.columns)) }.onFailure { message = it.message } }
            }) { Text("Drucken") }
        }
    }
    if (exportDialog) ExportPeriodDialog(onDismiss = { exportDialog = false }) { from, to, label, share ->
        exportDialog = false
        scope.launch {
            try {
                val file = container.exports.dsfinvk(from, to, label, deviceModel)
                if (share) {
                    container.audit.log(user.name, "EXPORT_DSFINVK_GETEILT", file.name)
                    if (file.warnings.isNotEmpty()) message = "Hinweise zum Export:" + hints(file)
                    context.shareFiles(listOf(file), "Kasse ${container.settings[de.anypos.domain.Key.REGISTER_ID]} – DSFinV-K $label",
                        "Anbei der DSFinV-K-Export ($label).", container.settings[de.anypos.domain.Key.ADVISOR_EMAIL])
                } else {
                    pendingExport = file
                    zipLauncher.launch(file.name)
                }
            } catch (e: ExportException) {
                message = "Export nicht möglich:\n" + e.problems.joinToString("\n") { "• $it" }
            }
        }
    }
    message?.let { MessageDialog("Abschluss", it) { message = null } }
}

/** Zeitraumwahl für den DSFinV-K-Export: ein Kalendermonat oder der gesamte Datenbestand. */
@Composable
private fun ExportPeriodDialog(onDismiss: () -> Unit, onExport: (from: Long, to: Long, label: String, share: Boolean) -> Unit) {
    val zone = ZoneId.of("Europe/Berlin")
    var month by remember { mutableStateOf(YearMonth.now(zone)) }
    var share by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("DSFinV-K-Export") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Exportiert werden alle Tagesabschlüsse (Z-Bons), die im Zeitraum erstellt wurden, mit allen Einzelaufzeichnungen.")
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(onClick = { month = month.minusMonths(1) }) { Text("◀") }
                    Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.GERMAN)), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { month = month.plusMonths(1) }) { Text("▶") }
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = share, onCheckedChange = { share = it })
                    Text("Teilen statt speichern (E-Mail, Cloud …)")
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onExport(0, Long.MAX_VALUE, "gesamt", share) }) { Text("Gesamter Zeitraum") }
                TextButton(onClick = {
                    val (from, to) = CashBook.dayRange(month.atDay(1), month.atEndOfMonth())
                    onExport(from, to, month.toString(), share)
                }) { Text("Monat exportieren") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
