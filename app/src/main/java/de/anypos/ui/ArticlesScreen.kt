package de.anypos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.anypos.AppContainer
import de.anypos.data.Article
import de.anypos.data.Category
import de.anypos.data.TaxRate
import de.anypos.data.User
import de.anypos.domain.Money
import kotlinx.coroutines.launch

@Composable
fun ArticlesScreen(container: AppContainer, user: User) {
    val dao = container.db.masterData()
    val articles by remember { dao.articles() }.collectAsState(initial = emptyList())
    val categories by remember { dao.categories() }.collectAsState(initial = emptyList())
    val taxRates by remember { dao.taxRates() }.collectAsState(initial = emptyList())
    var search by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Article?>(null) }
    var newCategory by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ScreenTitle("Artikel")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(search, { search = it }, label = { Text("Suche") }, singleLine = true, modifier = Modifier.width(320.dp))
            Button(onClick = { editing = Article(sku = "", name = "", priceCents = 0, taxRateId = 1) }) { Text("Neuer Artikel") }
            OutlinedButton(onClick = { newCategory = true }) { Text("Neue Warengruppe") }
            Text("${articles.size} Artikel, ${categories.size} Warengruppen")
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        val list = articles.filter { search.isBlank() || it.name.contains(search, true) || it.sku.contains(search, true) || it.barcode?.contains(search) == true }
        LazyColumn {
            items(list, key = { it.id }) { a ->
                Row(Modifier.fillMaxWidth().clickable { editing = a }.padding(vertical = 8.dp)) {
                    Text(a.sku, Modifier.width(120.dp))
                    Text(a.name, Modifier.weight(1f), color = if (a.active) Color.Unspecified else Color.Gray)
                    Text(categories.firstOrNull { it.id == a.categoryId }?.name ?: "", Modifier.width(160.dp))
                    Text(a.barcode ?: "", Modifier.width(150.dp))
                    Text(taxRates.firstOrNull { it.id == a.taxRateId }?.label ?: "?", Modifier.width(110.dp))
                    Text(if (a.openPrice) "Freipreis" else Money.formatEur(a.priceCents), Modifier.width(100.dp))
                }
                HorizontalDivider()
            }
        }
    }

    editing?.let { a ->
        ArticleDialog(a, categories, taxRates, onDismiss = { editing = null }) { updated ->
            if (articles.any { it.sku == updated.sku && it.id != updated.id }) {
                message = "Artikelnummer ${updated.sku} ist bereits vergeben"
                return@ArticleDialog
            }
            editing = null
            scope.launch {
                dao.upsertArticle(updated.copy(updatedAt = System.currentTimeMillis()))
                container.audit.log(user.name, if (a.id == 0L) "ARTIKEL_NEU" else "ARTIKEL_GEAENDERT",
                    "${updated.sku} ${updated.name} ${Money.format(updated.priceCents)} St=${updated.taxRateId} aktiv=${updated.active}")
            }
        }
    }
    if (newCategory) TextInputDialog("Neue Warengruppe", label = "Name", onDismiss = { newCategory = false }) { name ->
        newCategory = false
        if (name.isNotBlank()) scope.launch { dao.insertCategory(Category(name = name.trim(), sortOrder = categories.size)) }
    }
    message?.let { MessageDialog("Artikel", it) { message = null } }
}

@Composable
private fun ArticleDialog(article: Article, categories: List<Category>, taxRates: List<TaxRate>, onDismiss: () -> Unit, onSave: (Article) -> Unit) {
    var sku by remember { mutableStateOf(article.sku) }
    var name by remember { mutableStateOf(article.name) }
    var price by remember { mutableStateOf(if (article.id == 0L) "" else Money.format(article.priceCents)) }
    var barcode by remember { mutableStateOf(article.barcode.orEmpty()) }
    var taxId by remember { mutableStateOf(article.taxRateId) }
    var categoryId by remember { mutableStateOf(article.categoryId) }
    var active by remember { mutableStateOf(article.active) }
    var openPrice by remember { mutableStateOf(article.openPrice) }
    val priceCents = Money.parse(price)
    val valid = sku.isNotBlank() && name.isNotBlank() && (openPrice || (priceCents != null && priceCents >= 0))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (article.id == 0L) "Neuer Artikel" else "Artikel bearbeiten") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(sku, { sku = it }, label = { Text("Artikelnummer") }, singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("Bezeichnung") }, singleLine = true)
                OutlinedTextField(price, { price = it }, label = { Text("Bruttopreis (€)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(barcode, { barcode = it }, label = { Text("Barcode / EAN") }, singleLine = true)
                Text("Steuersatz", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    taxRates.forEach { t -> FilterChip(selected = taxId == t.id, onClick = { taxId = t.id }, label = { Text(t.label) }) }
                }
                Text("Warengruppe", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = categoryId == null, onClick = { categoryId = null }, label = { Text("keine") })
                    categories.forEach { c -> FilterChip(selected = categoryId == c.id, onClick = { categoryId = c.id }, label = { Text(c.name) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(active, { active = it }); Text("verkaufbar")
                    Checkbox(openPrice, { openPrice = it }); Text("Preis beim Verkauf abfragen")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(article.copy(sku = sku.trim(), name = name.trim(), priceCents = priceCents ?: 0, barcode = barcode.trim().ifEmpty { null },
                    taxRateId = taxId, categoryId = categoryId, active = active, openPrice = openPrice))
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
