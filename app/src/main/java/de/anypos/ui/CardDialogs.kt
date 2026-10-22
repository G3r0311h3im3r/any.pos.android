package de.anypos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.anypos.data.CardAttempt
import de.anypos.domain.Money
import de.anypos.payment.CardPaymentRequest
import de.anypos.payment.CardPaymentResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Simuliertes Kartenterminal (nur Test/Schulung): Der Tester wählt das Ergebnis. */
@Composable
fun SimulatedTerminalDialog(request: CardPaymentRequest, onResult: (CardPaymentResult) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Kartenterminal (SIMULATION)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Betrag ${Money.formatEur(request.amountCents)}" +
                    if (request.tipCents > 0) " (darin Trinkgeld ${Money.formatEur(request.tipCents)})" else "")
                Text("Referenz ${request.reference}", style = MaterialTheme.typography.bodySmall)
                val tx = remember { "SIM-" + (100000..999999).random() }
                Button(onClick = { onResult(CardPaymentResult.Approved(tx, "VISA", "4242")) }, Modifier.fillMaxWidth().height(52.dp)) { Text("Genehmigt") }
                OutlinedButton(onClick = { onResult(CardPaymentResult.Declined("Karte abgelehnt (Simulation)")) }, Modifier.fillMaxWidth()) { Text("Abgelehnt") }
                OutlinedButton(onClick = { onResult(CardPaymentResult.Cancelled) }, Modifier.fillMaxWidth()) { Text("Abgebrochen") }
                OutlinedButton(onClick = { onResult(CardPaymentResult.Unknown("Verbindung zum Terminal verloren (Simulation)")) }, Modifier.fillMaxWidth()) {
                    Text("Status unbekannt")
                }
            }
        },
        confirmButton = {},
    )
}

/** Ergebnis unbekannt: Kassierer prüft in der SumUp-App, ob abgebucht wurde. Kein Schließen ohne Entscheidung. */
@Composable
fun CardUnknownDialog(attempt: CardAttempt, onResolve: (paid: Boolean, txCode: String?) -> Unit) {
    var tx by remember { mutableStateOf("") }
    var confirmPaid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Kartenzahlung ungeklärt") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ob ${Money.formatEur(attempt.amountCents)} abgebucht wurden, ist nicht bekannt" +
                    (attempt.note?.let { " ($it)" } ?: "") + ".")
                Text("Bitte in der SumUp-App unter „Transaktionen“ nachsehen (Betrag und Uhrzeit, Referenz ${attempt.reference}). " +
                    "Nicht einfach erneut abbuchen – sonst zahlt der Kunde doppelt.")
                if (confirmPaid) OutlinedTextField(tx, { tx = it }, label = { Text("Transaktionscode aus der SumUp-App (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            if (!confirmPaid) TextButton(onClick = { confirmPaid = true }) { Text("Wurde abgebucht") }
            else TextButton(onClick = { onResolve(true, tx) }) { Text("Abgebucht – Beleg abschließen") }
        },
        dismissButton = { TextButton(onClick = { onResolve(false, null) }) { Text("Nicht abgebucht") } },
    )
}

/** Kartenzahlung abgelehnt/abgebrochen; optional Notfall-Buchung ohne Terminal. */
@Composable
fun CardFailedDialog(text: String, manualAllowed: Boolean, onManual: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kartenzahlung") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text)
                Text("Der Vorgang bleibt offen: erneut mit Karte versuchen oder bar zahlen.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        dismissButton = {
            if (manualAllowed) TextButton(onClick = onManual) { Text("Ohne Terminal buchen …") }
        },
    )
}

/** Notfall-Buchung bestätigen: Zahlung wurde an einem anderen Gerät genehmigt. */
@Composable
fun CardManualConfirmDialog(amountCents: Long, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        "Ohne Terminal buchen?",
        "Nur buchen, wenn ${Money.formatEur(amountCents)} an einem anderen Kartengerät genehmigt wurden. " +
            "Die Buchung wird protokolliert.",
        confirm = "Genehmigt – buchen", onDismiss = onDismiss, onConfirm = onConfirm,
    )
}

/** Ungeklärte Kartenzahlung eines früheren, abgebrochenen Vorgangs (z. B. nach Neustart). */
@Composable
fun OldCardAttemptDialog(attempt: CardAttempt, onResolve: (paid: Boolean) -> Unit) {
    val time = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("Europe/Berlin")).format(Instant.ofEpochMilli(attempt.createdAt))
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Ungeklärte Kartenzahlung") },
        text = {
            Text("Vorgang ${attempt.receiptNumber} vom $time wurde unterbrochen (${attempt.note ?: "Ergebnis unbekannt"}). " +
                "Kartenzahlung ${Money.formatEur(attempt.amountCents)}, Referenz ${attempt.reference}.\n\n" +
                "Bitte in der SumUp-App unter „Transaktionen“ prüfen. Wurde abgebucht, gibt es zu dieser Zahlung keinen Beleg – " +
                "dann in SumUp erstatten oder den Verkauf neu erfassen.")
        },
        confirmButton = { TextButton(onClick = { onResolve(true) }) { Text("Wurde abgebucht") } },
        dismissButton = { TextButton(onClick = { onResolve(false) }) { Text("Nicht abgebucht") } },
    )
}
