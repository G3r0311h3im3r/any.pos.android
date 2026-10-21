package de.anypos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.anypos.AppContainer
import de.anypos.data.Article
import de.anypos.data.User
import de.anypos.domain.CartLine
import de.anypos.domain.Money

@Composable
fun PosScreen(container: AppContainer, user: User) {
    val vm: PosViewModel = viewModel(key = "pos-${user.id}") { PosViewModel(container, user) }
    val articles by vm.articles.collectAsState()
    val categories by vm.categories.collectAsState()
    var categoryId by remember { mutableStateOf<Long?>(null) }
    var search by remember { mutableStateOf("") }
    var payDialog by remember { mutableStateOf(false) }
    var openPriceFor by remember { mutableStateOf<Article?>(null) }
    var priceEditKey by remember { mutableStateOf<Long?>(null) }
    var discountKey by remember { mutableStateOf<Long?>(null) }
    var showLast by remember { mutableStateOf(false) }
    var confirmAbort by remember { mutableStateOf(false) }
    var voucherSale by remember { mutableStateOf(false) }
    var voucherRedeem by remember { mutableStateOf(false) }
    var splitDialog by remember { mutableStateOf(false) }

    val visible = articles.filter { a ->
        a.active && (categoryId == null || a.categoryId == categoryId) &&
            (search.isBlank() || a.name.contains(search, true) || a.sku.contains(search, true) || a.barcode == search)
    }

    Row(Modifier.fillMaxSize()) {
        // Linke Seite: Artikelauswahl
        Column(Modifier.weight(1.4f).fillMaxHeight().padding(12.dp)) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Barcode / Artikelnummer / Suche") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.scan(search) { openPriceFor = it }; search = "" }),
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent {
                    if (it.key == Key.Enter && it.type == KeyEventType.KeyUp) {
                        vm.scan(search) { a -> openPriceFor = a }; search = ""; true
                    } else false
                },
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = categoryId == null, onClick = { categoryId = null }, label = { Text("Alle") }) }
                items(categories) { cat ->
                    FilterChip(selected = categoryId == cat.id, onClick = { categoryId = cat.id }, label = { Text(cat.name) })
                }
            }
            Spacer(Modifier.height(8.dp))
            if (articles.isEmpty()) {
                Text("Noch keine Artikel. Unter \"Import\" JTL-Daten übernehmen oder unter \"Artikel\" anlegen.", Modifier.padding(16.dp))
            }
            LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.id }) { a ->
                    Card(
                        onClick = { if (a.openPrice) openPriceFor = a else vm.add(a) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.height(96.dp),
                    ) {
                        Column(Modifier.padding(10.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                            Text(a.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text(if (a.openPrice) "Preis eingeben" else Money.formatEur(a.priceCents), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }

        // Rechte Seite: Warenkorb
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), modifier = Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(vm.openReceipt?.let { "Beleg ${it.number}" } ?: "Neuer Vorgang", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.openDrawer() }) { Text("Lade öffnen") }
                    TextButton(onClick = { showLast = true }, enabled = vm.lastReceipt != null) { Text("Letzter Beleg") }
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f)) {
                    items(vm.cart.lines, key = { it.key }) { line ->
                        CartRow(
                            line, selected = line.key == vm.selectedLineKey,
                            onClick = { vm.selectedLineKey = line.key },
                            onPlus = { vm.changeQuantity(line.key, 1000) },
                            onMinus = { vm.changeQuantity(line.key, -1000) },
                            onPrice = { priceEditKey = line.key },
                            onDiscount = { discountKey = line.key },
                            onRemove = { vm.remove(line.key) },
                        )
                    }
                }
                vm.lastChangeCents?.let { change ->
                    Surface(color = Color(0xFF2E7D32), modifier = Modifier.fillMaxWidth()) {
                        Text("Rückgeld: ${Money.formatEur(change)}", color = Color.White, fontSize = 28.sp, modifier = Modifier.padding(12.dp))
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Summe", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    Text(vm.totalText(), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    OutlinedButton(onClick = { voucherSale = true }, enabled = !vm.busy) { Text("Gutschein verkaufen") }
                    OutlinedButton(onClick = { voucherRedeem = true }, enabled = !vm.cart.isEmpty && !vm.busy) { Text("Gutschein einlösen") }
                    OutlinedButton(onClick = { splitDialog = true }, enabled = vm.cart.totalCents > 0 && !vm.busy) { Text("Aufteilen") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { confirmAbort = true }, enabled = vm.openReceipt != null && !vm.busy, modifier = Modifier.height(64.dp)) { Text("Abbruch") }
                    Button(
                        onClick = { vm.payCard() }, enabled = !vm.cart.isEmpty && !vm.busy,
                        modifier = Modifier.weight(1f).height(64.dp),
                    ) { Text("Karte", fontSize = 20.sp) }
                    // Vollständig durch Gutschein gedeckt: nichts zu kassieren, direkt abschließen
                    val nothingToPay = !vm.cart.isEmpty && vm.cart.totalCents == 0L
                    Button(
                        onClick = { if (nothingToPay) vm.payCash(0) else payDialog = true }, enabled = !vm.cart.isEmpty && !vm.busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        modifier = Modifier.weight(1.4f).height(64.dp),
                    ) { Text(if (nothingToPay) "Abschließen" else "Bar", fontSize = 20.sp) }
                }
            }
        }
    }

    if (voucherSale) {
        val rates by vm.taxRates.collectAsState()
        VoucherSaleDialog(rates, onDismiss = { voucherSale = false }) { type, amount, code, taxId ->
            voucherSale = false
            vm.sellVoucher(type, amount, code, taxId)
        }
    }
    if (voucherRedeem) TextInputDialog("Gutschein einlösen", label = "Gutscheinnummer (scannen oder eingeben)", onDismiss = { voucherRedeem = false }) { code ->
        voucherRedeem = false
        vm.redeemVoucher(code)
    }
    if (splitDialog) SplitPaymentDialog(totalCents = vm.cart.totalCents, onDismiss = { splitDialog = false }) { payments, change ->
        splitDialog = false
        vm.pay(payments, change)
    }
    if (payDialog) CashDialog(totalCents = vm.cart.totalCents, onDismiss = { payDialog = false }) { given ->
        payDialog = false
        vm.payCash(given)
    }
    openPriceFor?.let { a ->
        AmountDialog("Preis für ${a.name}", onDismiss = { openPriceFor = null }) { cents ->
            openPriceFor = null
            if (cents > 0) vm.add(a, cents)
        }
    }
    priceEditKey?.let { key ->
        AmountDialog("Preis ändern", initialCents = vm.cart.lines.firstOrNull { it.key == key }?.unitPriceCents ?: 0, onDismiss = { priceEditKey = null }) {
            priceEditKey = null
            vm.setPrice(key, it)
        }
    }
    discountKey?.let { key ->
        DiscountDialog(onDismiss = { discountKey = null }) { bp -> discountKey = null; vm.discountPercent(key, bp) }
    }
    if (confirmAbort) ConfirmDialog("Vorgang abbrechen?", "Der Vorgang wird als Abbruch protokolliert (nicht gelöscht).", onDismiss = { confirmAbort = false }) {
        confirmAbort = false
        vm.abort()
    }
    if (showLast) vm.lastReceipt?.let { r ->
        ReceiptPreviewDialog(vm.receiptText(r), onDismiss = { showLast = false }) {
            TextButton(onClick = { vm.reprintLast() }) { Text("Kopie drucken") }
        }
    }
    vm.message?.let { MessageDialog("Hinweis", it) { vm.message = null } }
}

@Composable
private fun CartRow(
    line: CartLine, selected: Boolean, onClick: () -> Unit, onPlus: () -> Unit, onMinus: () -> Unit,
    onPrice: () -> Unit, onDiscount: () -> Unit, onRemove: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${Money.formatQuantity(line.quantityMilli)} ×", Modifier.width(48.dp))
            Column(Modifier.weight(1f)) {
                Text(line.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${Money.format(line.unitPriceCents)} · ${line.taxRate.label}" + if (line.discountCents != 0L) " · Rabatt ${Money.format(line.discountCents)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(Money.format(line.totalCents), style = MaterialTheme.typography.titleMedium)
        }
        if (selected) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (line.editable) {
                TextButton(onClick = onMinus) { Text("−") }
                TextButton(onClick = onPlus) { Text("+") }
                TextButton(onClick = onPrice) { Text("Preis") }
                TextButton(onClick = onDiscount) { Text("Rabatt") }
            }
            TextButton(onClick = onRemove) { Text("Entfernen") }
        }
    }
}

@Composable
private fun CashDialog(totalCents: Long, onDismiss: () -> Unit, onPay: (Long) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    if (custom) {
        AmountDialog("Gegebener Betrag", onDismiss = { custom = false }) { onPay(it) }
        return
    }
    val suggestions = listOf(500L, 1000L, 2000L, 5000L, 10000L).filter { it > totalCents }.take(4)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Barzahlung ${Money.formatEur(totalCents)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onPay(totalCents) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Passend", fontSize = 20.sp) }
                if (totalCents > 0) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { s ->
                        OutlinedButton(onClick = { onPay(s) }, modifier = Modifier.height(56.dp)) { Text(Money.formatEur(s)) }
                    }
                }
                OutlinedButton(onClick = { custom = true }, modifier = Modifier.fillMaxWidth()) { Text("Anderer Betrag …") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun DiscountDialog(onDismiss: () -> Unit, onPercent: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rabatt auf Position") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 5, 10, 15, 20, 50).forEach { p ->
                    OutlinedButton(onClick = { onPercent(p * 100) }) { Text(if (p == 0) "kein" else "$p %") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
