package de.anypos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.anypos.domain.Money

/**
 * Trinkgeldabfrage an der Kasse. Ein Tipp des Kassierers auf eine Stufe übernimmt sie sofort; eine Auswahl des Kunden
 * am Kundendisplay wird hervorgehoben und muss mit „Übernehmen“ bestätigt werden.
 */
@Composable
fun TipDialog(
    request: TipRequest,
    customerChoice: String?,
    displayActive: Boolean,
    employeeTip: Boolean,
    onApply: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var custom by remember { mutableStateOf(false) }
    if (custom) {
        AmountDialog("Trinkgeld – anderer Betrag", onDismiss = { custom = false }) { cents -> custom = false; onApply(cents.coerceAtLeast(0)) }
        return
    }
    val chosen = request.options.firstOrNull { it.id == customerChoice }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trinkgeld? Betrag ${Money.formatEur(request.baseCents)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (displayActive) {
                    Text(
                        if (chosen == null) "Der Kunde kann am Kundendisplay wählen – oder hier antippen."
                        else "Kunde hat gewählt: ${chosen.label}" + if (chosen.cents > 0) " (${Money.formatEur(chosen.cents)})" else "",
                        fontWeight = if (chosen != null) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                request.options.forEach { o ->
                    val selected = o.id == customerChoice
                    val text = o.label + if (o.cents > 0) "  +${Money.formatEur(o.cents)}  = ${Money.formatEur(request.baseCents + o.cents)}" else ""
                    if (selected) {
                        Button(onClick = { onApply(o.cents) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                            modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(text, fontSize = 18.sp) }
                    } else {
                        OutlinedButton(onClick = { onApply(o.cents) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(text, fontSize = 18.sp) }
                    }
                }
                OutlinedButton(onClick = { custom = true }, modifier = Modifier.fillMaxWidth()) { Text("Anderer Betrag …") }
                Text(
                    if (employeeTip) "Trinkgeld für Mitarbeitende: steht getrennt und ohne USt auf dem Bon, kein Umsatz."
                    else "Trinkgeld an den Betrieb: zählt zum Umsatz (mit USt).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            if (chosen != null) TextButton(onClick = { onApply(chosen.cents) }) { Text("Übernehmen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
