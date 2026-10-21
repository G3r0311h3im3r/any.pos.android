package de.anypos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.anypos.data.PaymentType
import de.anypos.data.TaxRate
import de.anypos.data.VoucherType
import de.anypos.domain.Money
import de.anypos.domain.PaymentInput

/**
 * Splitzahlung: Teilbeträge bar oder mit Karte erfassen, bis der Restbetrag 0 ist.
 * Eine Barzahlung über dem Restbetrag schließt ab und weist Rückgeld aus.
 */
@Composable
fun SplitPaymentDialog(totalCents: Long, onDismiss: () -> Unit, onPay: (List<PaymentInput>, Long?) -> Unit) {
    val payments = remember { mutableStateListOf<PaymentInput>() }
    var digits by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val remaining = totalCents - payments.sumOf { it.amountCents }
    val amount = digits.toLongOrNull() ?: remaining

    fun addCash() {
        error = null
        if (amount <= 0) return
        if (amount >= remaining) {
            onPay(payments + PaymentInput(PaymentType.CASH, "Bar", remaining, amount), amount - remaining)
        } else {
            payments += PaymentInput(PaymentType.CASH, "Bar", amount, amount)
            digits = ""
        }
    }

    fun addCard() {
        error = null
        if (amount <= 0) return
        if (amount > remaining) { error = "Kartenzahlung darf den Restbetrag nicht übersteigen"; return }
        val next = payments + PaymentInput(PaymentType.CARD, "EC-Karte", amount)
        if (amount == remaining) onPay(next, null) else { payments += next.last(); digits = "" }
    }

    WideDialog(
        title = "Zahlung aufteilen – ${Money.formatEur(totalCents)}",
        onDismiss = onDismiss,
        buttons = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    ) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.width(260.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    payments.forEachIndexed { i, p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.label, Modifier.weight(1f))
                            Text(Money.format(p.amountCents))
                            TextButton(onClick = { payments.removeAt(i) }) { Text("✕") }
                        }
                    }
                    HorizontalDivider()
                    Row { Text("Restbetrag", Modifier.weight(1f)); Text(Money.formatEur(remaining), style = MaterialTheme.typography.titleLarge) }
                    Spacer(Modifier.height(8.dp))
                    Text("Teilbetrag", style = MaterialTheme.typography.labelLarge)
                    Text(Money.formatEur(amount), style = MaterialTheme.typography.headlineMedium)
                    error?.let { Text(it, color = Color(0xFFB71C1C)) }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { addCard() }, modifier = Modifier.height(56.dp)) { Text("Karte", fontSize = 18.sp) }
                    Button(
                        onClick = { addCash() }, modifier = Modifier.height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    ) { Text("Bar", fontSize = 18.sp) }
                }
                NumPad(onKey = { k ->
                    digits = when (k) {
                        "C" -> ""
                        "⌫" -> digits.dropLast(1)
                        else -> if (digits.length < 9) (digits + k).trimStart('0') else digits
                    }
                })
            }
    }
}

/** Gutscheinverkauf: Art, Wert und optional eine vorgedruckte Gutscheinnummer. */
@Composable
fun VoucherSaleDialog(taxRates: List<TaxRate>, onDismiss: () -> Unit, onSell: (type: String, amountCents: Long, code: String, taxRateId: Long) -> Unit) {
    var type by remember { mutableStateOf(VoucherType.MULTI) }
    var taxId by remember { mutableStateOf(taxRates.firstOrNull()?.id ?: 1L) }
    var digits by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val amount = digits.toLongOrNull() ?: 0

    WideDialog(
        title = "Gutschein verkaufen",
        onDismiss = onDismiss,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
            TextButton(enabled = amount > 0, onClick = { onSell(type, amount, code, taxId) }) { Text("In den Warenkorb") }
        },
    ) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = type == VoucherType.MULTI, onClick = { type = VoucherType.MULTI }, label = { Text("Mehrzweck (Wertgutschein)") })
                        FilterChip(selected = type == VoucherType.SINGLE, onClick = { type = VoucherType.SINGLE }, label = { Text("Einzweck") })
                    }
                    Text(
                        if (type == VoucherType.MULTI) "Für beliebige Waren einlösbar. USt fällt erst bei Einlösung an."
                        else "Nur für Waren eines Steuersatzes. USt fällt sofort beim Verkauf an.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (type == VoucherType.SINGLE) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        taxRates.filter { it.percentBp > 0 }.forEach { t ->
                            FilterChip(selected = taxId == t.id, onClick = { taxId = t.id }, label = { Text(t.label) })
                        }
                    }
                    OutlinedTextField(code, { code = it }, label = { Text("Gutscheinnummer (leer = automatisch)") }, singleLine = true)
                    Text("Wert", style = MaterialTheme.typography.labelLarge)
                    Text(Money.formatEur(amount), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 8.dp))
                }
                NumPad(onKey = { k ->
                    digits = when (k) {
                        "C" -> ""
                        "⌫" -> digits.dropLast(1)
                        else -> if (digits.length < 7) (digits + k).trimStart('0') else digits
                    }
                })
            }
    }
}
