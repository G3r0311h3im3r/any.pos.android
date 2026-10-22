package de.anypos.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.anypos.AppContainer
import de.anypos.data.BusinessCase
import de.anypos.data.User
import de.anypos.domain.Key
import de.anypos.domain.Money
import de.anypos.export.CashBookRow
import de.anypos.export.ExportException
import de.anypos.export.ExportFile
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val rowFmt = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.of("Europe/Berlin"))

@Composable
fun CashBookScreen(container: AppContainer, user: User) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var month by remember { mutableStateOf(YearMonth.now(ZoneId.of("Europe/Berlin"))) }
    var rows by remember { mutableStateOf<List<CashBookRow>>(emptyList()) }
    var opening by remember { mutableStateOf(0L) }
    var refresh by remember { mutableIntStateOf(0) }
    var movement by remember { mutableStateOf<String?>(null) }
    var movementAmount by remember { mutableStateOf<Long?>(null) }
    /** Offener Trinkgeldbetrag beim Öffnen der Auszahlung (Vorschlag im Betragsfeld). */
    var tipOpen by remember { mutableStateOf(0L) }
    var includeCard by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(month, refresh) {
        val book = container.exports.cashBook(month)
        opening = book.openingCents
        rows = book.rows
    }

    // Datei wird erst erzeugt, dann der Speicherort gewählt; so erscheinen Einstellungsfehler sofort
    var pendingSave by remember { mutableStateOf<ExportFile?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val file = pendingSave
        pendingSave = null
        if (uri != null && file != null) scope.launch {
            context.writeUri(uri, file.bytes)
            container.audit.log(user.name, "EXPORT", file.name)
            message = "${file.name} gespeichert" + file.warnings.joinToString("") { "\n• $it" }
        }
    }

    /** Erzeugt die gewählten Exporte und speichert (eine Datei) oder teilt sie. */
    fun export(kinds: List<String>, share: Boolean) = scope.launch {
        val files = mutableListOf<ExportFile>()
        val problems = mutableListOf<String>()
        for (k in kinds) {
            try {
                files += if (k == "csv") container.exports.cashBookCsv(month) else container.exports.datev(month, includeCard)
            } catch (e: ExportException) {
                problems += e.problems
            }
        }
        if (problems.isNotEmpty()) { message = problems.joinToString("\n"); return@launch }
        // Hinweise verhindern den Export nicht (z. B. fehlende DATEV-Berater-/Mandantennummer)
        val hints = files.flatMap { it.warnings }.distinct()
        if (hints.isNotEmpty()) message = "Hinweise zum Export:\n" + hints.joinToString("\n") { "• $it" }
        if (share) {
            container.audit.log(user.name, "EXPORT_GETEILT", files.joinToString { it.name })
            context.shareFiles(
                files,
                subject = "Kasse ${container.settings[Key.REGISTER_ID]} – Kassenbuch/DATEV ${month}",
                text = "Anbei die Kassendaten für ${month.format(DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.GERMAN))}.",
                email = container.settings[Key.ADVISOR_EMAIL],
            )
        } else {
            pendingSave = files.single()
            saveLauncher.launch(files.single().name)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ScreenTitle("Kassenbuch")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { month = month.minusMonths(1) }) { Text("◀") }
            Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.GERMAN)), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { month = month.plusMonths(1) }) { Text("▶") }
            Text("Anfangsbestand ${Money.formatEur(opening)} · Endbestand ${Money.formatEur(rows.lastOrNull()?.balanceCents ?: opening)}", Modifier.padding(start = 16.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            listOf(BusinessCase.OPENING, BusinessCase.DEPOSIT, BusinessCase.PAYOUT, BusinessCase.PRIVATE_IN, BusinessCase.PRIVATE_OUT, BusinessCase.TRANSIT).forEach { bc ->
                OutlinedButton(onClick = { movement = bc }) { Text(bc) }
            }
            // Gesammeltes Trinkgeld der Mitarbeitenden bar auszahlen (eigener, TSE-gesicherter Vorgang)
            var tipBalance by remember { mutableStateOf(0L) }
            LaunchedEffect(refresh) { tipBalance = container.pos.tipBalance() }
            if (tipBalance != 0L || container.settings.bool(Key.TIP_ENABLED)) {
                OutlinedButton(onClick = { tipOpen = tipBalance; movement = BusinessCase.TIP_EMPLOYEE }) {
                    Text("Trinkgeld auszahlen (offen ${Money.formatEur(tipBalance)})")
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { export(listOf("csv"), share = false) }) { Text("Kassenbuch speichern") }
            OutlinedButton(onClick = { export(listOf("csv"), share = true) }) { Text("Kassenbuch teilen") }
            OutlinedButton(onClick = { export(listOf("datev"), share = false) }) { Text("DATEV speichern") }
            OutlinedButton(onClick = { export(listOf("datev"), share = true) }) { Text("DATEV teilen") }
            androidx.compose.material3.Button(onClick = { export(listOf("csv", "datev"), share = true) }) { Text("An Steuerberater senden") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = includeCard, onCheckedChange = { includeCard = it })
            Text("Kartenumsätze in DATEV-Export")
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("Zeit", Modifier.width(110.dp), style = MaterialTheme.typography.labelLarge)
            Text("Beleg", Modifier.width(70.dp), style = MaterialTheme.typography.labelLarge)
            Text("Text", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text("Einnahme", Modifier.width(110.dp), style = MaterialTheme.typography.labelLarge)
            Text("Ausgabe", Modifier.width(110.dp), style = MaterialTheme.typography.labelLarge)
            Text("Bestand", Modifier.width(110.dp), style = MaterialTheme.typography.labelLarge)
        }
        LazyColumn {
            items(rows) { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(rowFmt.format(Instant.ofEpochMilli(r.timestamp)), Modifier.width(110.dp))
                    Text(r.receiptNumber.toString(), Modifier.width(70.dp))
                    Text(r.text, Modifier.weight(1f))
                    Text(if (r.amountCents > 0) Money.format(r.amountCents) else "", Modifier.width(110.dp))
                    Text(if (r.amountCents < 0) Money.format(-r.amountCents) else "", Modifier.width(110.dp))
                    Text(Money.format(r.balanceCents), Modifier.width(110.dp))
                }
            }
        }
    }

    movement?.let { bc ->
        if (movementAmount == null) {
            if (bc == BusinessCase.TIP_EMPLOYEE) AmountDialog("Trinkgeld auszahlen – Betrag", initialCents = tipOpen.coerceAtLeast(0), onDismiss = { movement = null }) { cents ->
                movementAmount = kotlin.math.abs(cents)
            } else AmountDialog("$bc – Betrag", allowNegative = bc == BusinessCase.TRANSIT, onDismiss = { movement = null }) { cents ->
                // Auszahlung/Privatentnahme verlassen die Kasse; Geldtransit Vorzeichen frei wählbar
                movementAmount = when (bc) {
                    BusinessCase.PAYOUT, BusinessCase.PRIVATE_OUT -> -kotlin.math.abs(cents)
                    BusinessCase.TRANSIT -> cents
                    else -> kotlin.math.abs(cents)
                }
            }
        } else {
            val tip = bc == BusinessCase.TIP_EMPLOYEE
            TextInputDialog(
                if (tip) "Trinkgeld auszahlen – an wen?" else "$bc – Buchungstext",
                label = if (tip) "Name der Mitarbeiterin / des Mitarbeiters (unterschreibt den Beleg)" else "z. B. Wechselgeld, Porto, Bankeinzahlung",
                onDismiss = { movement = null; movementAmount = null },
            ) { text ->
                val amount = movementAmount!!
                movement = null; movementAmount = null
                scope.launch {
                    try {
                        val r = if (tip) container.pos.tipPayout(user, amount, text) else container.pos.cashMovement(user, bc, amount, text)
                        if (container.printer.configured) runCatching {
                            container.printer.print(de.anypos.hardware.ReceiptDocument.toEscPos(
                                de.anypos.hardware.ReceiptDocument.receipt(container.company(), r), container.printer.columns,
                                openDrawer = true, drawerPin = container.settings.int(Key.DRAWER_PIN)))
                        }
                        refresh++
                    } catch (e: Exception) {
                        message = e.message
                    }
                }
            }
        }
    }
    message?.let { MessageDialog("Kassenbuch", it) { message = null } }
}
