package de.anypos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.anypos.AppContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import de.anypos.data.BusinessCase
import de.anypos.data.PaymentType
import de.anypos.data.ReceiptState
import de.anypos.domain.PosService
import de.anypos.domain.ReturnItem
import de.anypos.data.ReceiptType
import de.anypos.data.ReceiptWithDetails
import de.anypos.data.Role
import de.anypos.data.User
import de.anypos.domain.Key
import de.anypos.domain.Money
import de.anypos.hardware.ReceiptDocument
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm").withZone(ZoneId.of("Europe/Berlin"))

@Composable
fun ReceiptsScreen(container: AppContainer, user: User) {
    val receipts by remember { container.db.receipts().recent(500) }.collectAsState(initial = emptyList())
    var selected by remember { mutableStateOf<ReceiptWithDetails?>(null) }
    var confirmCancel by remember { mutableStateOf(false) }
    var returnFor by remember { mutableStateOf<ReceiptWithDetails?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ScreenTitle("Belegjournal")
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("Nr.", Modifier.width(80.dp), style = MaterialTheme.typography.labelLarge)
            Text("Zeit", Modifier.width(140.dp), style = MaterialTheme.typography.labelLarge)
            Text("Art", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text("Bediener", Modifier.width(140.dp), style = MaterialTheme.typography.labelLarge)
            Text("Betrag", Modifier.width(110.dp), style = MaterialTheme.typography.labelLarge)
        }
        HorizontalDivider()
        LazyColumn {
            items(receipts, key = { it.id }) { r ->
                Row(
                    Modifier.fillMaxWidth().clickable { scope.launch { selected = container.pos.load(r.id) } }.padding(vertical = 10.dp),
                ) {
                    Text(r.number.toString(), Modifier.width(80.dp))
                    Text(timeFmt.format(Instant.ofEpochMilli(r.finishedAt ?: r.createdAt)), Modifier.width(140.dp))
                    val kind = when {
                        r.state == ReceiptState.ABORTED || r.type == ReceiptType.ABORT -> "Abbruch"
                        r.isCancellation -> "Storno zu ${r.referencesReceiptNumber}"
                        r.isReturn -> "Rückgabe zu ${r.referencesReceiptNumber}"
                        else -> "Beleg"
                    } + if (r.tseSignature == null) " · TSE-Ausfall" else ""
                    Text(kind, Modifier.weight(1f), color = if (r.tseSignature == null) Color(0xFFB71C1C) else Color.Unspecified)
                    Text(r.userName, Modifier.width(140.dp))
                    Text(Money.format(r.totalCents), Modifier.width(110.dp))
                }
                HorizontalDivider()
            }
        }
    }

    selected?.let { r ->
        val text = ReceiptDocument.toText(ReceiptDocument.receipt(container.company(), r), container.printer.columns)
        ReceiptPreviewDialog(text, onDismiss = { selected = null }) {
            TextButton(onClick = {
                scope.launch {
                    runCatching {
                        container.printer.print(ReceiptDocument.toEscPos(ReceiptDocument.receipt(container.company(), r, copy = true), container.printer.columns))
                    }.onFailure { message = it.message }
                }
            }) { Text("Kopie drucken") }
            val original = r.receipt.state == ReceiptState.FINISHED && r.receipt.type == ReceiptType.SALE &&
                r.receipt.referencesReceiptId == null && r.lines.none { it.businessCase in BusinessCase.cashMovements }
            if (original && user.role.allows(Role.MANAGER)) {
                if (r.lines.any { it.businessCase == BusinessCase.SALE && it.quantityMilli > 0 }) {
                    TextButton(onClick = { returnFor = r }) { Text("Rückgabe") }
                }
                TextButton(onClick = { confirmCancel = true }) { Text("Stornieren") }
            }
        }
    }
    returnFor?.let { r ->
        ReturnDialog(container, r, onDismiss = { returnFor = null }) { items, refundType, refundLabel ->
            returnFor = null
            scope.launch {
                try {
                    val ret = container.pos.returnItems(r.receipt.id, items, refundType, refundLabel, user)
                    selected = ret
                    if (refundType != PaymentType.CASH) refundHint(r)?.let { message = it }
                    if (container.printer.configured) runCatching {
                        container.printer.print(ReceiptDocument.toEscPos(ReceiptDocument.receipt(container.company(), ret), container.printer.columns,
                            openDrawer = refundType == PaymentType.CASH, drawerPin = container.settings.int(Key.DRAWER_PIN)))
                    }.onFailure { message = "Rückgabe gespeichert, Druck fehlgeschlagen: ${it.message}" }
                } catch (e: Exception) {
                    message = e.message
                }
            }
        }
    }
    if (confirmCancel) selected?.let { r ->
        ConfirmDialog("Beleg ${r.receipt.number} stornieren?", "Es wird ein Stornobeleg über ${Money.formatEur(-r.receipt.totalCents)} erstellt.", confirm = "Stornieren",
            onDismiss = { confirmCancel = false }) {
            confirmCancel = false
            scope.launch {
                try {
                    val storno = container.pos.cancel(r.receipt.id, user)
                    selected = storno
                    refundHint(r)?.let { message = it }
                    if (container.printer.configured) {
                        container.printer.print(ReceiptDocument.toEscPos(ReceiptDocument.receipt(container.company(), storno), container.printer.columns,
                            openDrawer = storno.payments.any { it.type == de.anypos.data.PaymentType.CASH }, drawerPin = container.settings.int(Key.DRAWER_PIN)))
                    }
                } catch (e: Exception) {
                    message = e.message
                }
            }
        }
    }
    message?.let { MessageDialog("Hinweis", it) { message = null } }
}

/** Auswahl der Rückgabemengen je Position und der Erstattungsart. */
@Composable
private fun ReturnDialog(
    container: AppContainer, original: ReceiptWithDetails, onDismiss: () -> Unit,
    onConfirm: (List<ReturnItem>, refundType: String, refundLabel: String) -> Unit,
) {
    val returned by produceState<Map<Long, Long>?>(null, original) { value = container.pos.returnedQuantities(original) }
    val chosen = remember { mutableStateMapOf<Long, Long>() }
    var refundCard by remember { mutableStateOf(original.payments.none { it.type == PaymentType.CASH }) }
    val lines = original.lines.filter { it.businessCase == BusinessCase.SALE && it.quantityMilli > 0 }

    val total = lines.sumOf { PosService.refundCents(it, chosen[it.id] ?: 0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rückgabe zu Beleg ${original.receipt.number}") },
        text = {
            Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val done = returned
                if (done == null) { Text("Lade …"); return@Column }
                lines.forEach { l ->
                    val left = l.quantityMilli - (done[l.id] ?: 0)
                    val qty = chosen[l.id] ?: 0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(l.name)
                            Text("gekauft ${Money.formatQuantity(l.quantityMilli)}, rückgabefähig ${Money.formatQuantity(left)}", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(enabled = qty >= 1000, onClick = { chosen[l.id] = qty - 1000 }) { Text("−") }
                        Text(Money.formatQuantity(qty), Modifier.width(48.dp))
                        TextButton(enabled = qty + 1000 <= left, onClick = { chosen[l.id] = qty + 1000 }) { Text("+") }
                        TextButton(enabled = left > 0, onClick = { chosen[l.id] = left }) { Text("alle") }
                    }
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Erstattung", Modifier.weight(1f))
                    FilterChip(selected = !refundCard, onClick = { refundCard = false }, label = { Text("Bar") })
                    FilterChip(selected = refundCard, onClick = { refundCard = true }, label = { Text("Karte") }, modifier = Modifier.padding(start = 8.dp))
                }
                Text("Erstattungsbetrag ${Money.formatEur(total)}", style = MaterialTheme.typography.titleLarge)
            }
        },
        confirmButton = {
            TextButton(enabled = total > 0, onClick = {
                onConfirm(
                    chosen.filterValues { it > 0 }.map { ReturnItem(it.key, it.value) },
                    if (refundCard) PaymentType.CARD else PaymentType.CASH,
                    if (refundCard) "EC-Karte" else "Bar",
                )
            }) { Text("Rückgabe buchen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/**
 * Kartenzahlungen über ein Terminal werden nicht automatisch erstattet (SumUp-SDK kann das nicht):
 * Hinweis mit Transaktionscode, damit die Erstattung im Händlerbereich ausgelöst wird.
 */
private fun refundHint(original: de.anypos.data.ReceiptWithDetails): String? {
    val card = original.payments.filter { it.providerTxCode != null }
    if (card.isEmpty()) return null
    return "Gebucht. Die Kartenzahlung bitte im SumUp-Händlerbereich bzw. in der SumUp-App erstatten: " +
        card.joinToString { "${Money.formatEur(it.amountCents)}, Transaktion ${it.providerTxCode}" } + "."
}
