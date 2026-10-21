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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import de.anypos.AppContainer
import de.anypos.data.Category
import de.anypos.data.ImportRun
import de.anypos.data.User
import de.anypos.domain.Money
import de.anypos.importer.JtlImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer

/** Dekodiert UTF-8; fällt bei ungültigen Bytes auf Windows-1252 zurück (typisch für Excel/JTL-Ameise). */
fun decodeCsv(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
} catch (_: CharacterCodingException) {
    String(bytes, charset("windows-1252"))
}

@Composable
fun ImportScreen(container: AppContainer, user: User) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<JtlImporter.Plan?>(null) }
    var fileName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val runs by remember { container.db.importRuns().all() }.collectAsState(initial = emptyList())
    val dao = container.db.masterData()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            fileName = context.displayName(uri)
            plan = withContext(Dispatchers.IO) {
                JtlImporter.analyze(decodeCsv(context.readUri(uri)), dao.taxRatesOnce(), dao.articlesOnce(), dao.categoriesOnce())
            }
        }
    }

    fun apply(p: JtlImporter.Plan) = scope.launch {
        val now = System.currentTimeMillis()
        container.db.withTransaction {
            val existing = dao.categoriesOnce()
            var order = existing.size
            p.newCategories.forEach { dao.insertCategory(Category(name = it, sortOrder = order++)) }
            val ids = dao.categoriesOnce().associate { it.name.lowercase() to it.id }
            dao.upsertArticles(JtlImporter.toArticles(p, ids, now))
            val report = buildString {
                appendLine("Spaltenzuordnung: " + p.mapping.entries.joinToString { "${it.key}=${it.value}" })
                if (p.usedNetPrices) appendLine("Preise aus Nettospalte berechnet")
                p.articles.filter { it.change != JtlImporter.Change.UNCHANGED }.forEach {
                    appendLine("${it.change} Zeile ${it.row.line}: ${it.row.sku} ${it.row.name} ${Money.format(it.row.priceCents)}")
                }
            }
            container.db.importRuns().insert(ImportRun(timestamp = now, userName = user.name, fileName = fileName,
                created = p.created, updated = p.updated, unchanged = p.unchanged, errors = p.errors.size, report = report))
        }
        container.audit.log(user.name, "IMPORT_JTL", "$fileName: neu ${p.created}, geändert ${p.updated}, unverändert ${p.unchanged}")
        message = "Import abgeschlossen: ${p.created} neu, ${p.updated} geändert, ${p.unchanged} unverändert."
        plan = null
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ScreenTitle("Datenübernahme aus JTL")
        Text(
            "CSV-Export aus JTL-Wawi (JTL-Ameise) oder im Format jtl-items-v1 wählen. Die Datei wird zuerst vollständig geprüft; " +
                "übernommen wird erst nach Bestätigung. Artikel, die in der Datei fehlen, werden nicht gelöscht.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 12.dp)) {
            Button(onClick = { picker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "*/*")) }) { Text("CSV-Datei wählen …") }
            plan?.let { p ->
                Button(onClick = { apply(p) }, enabled = p.canApply) { Text("Import bestätigen (${p.created} neu, ${p.updated} geändert)") }
                OutlinedButton(onClick = { plan = null }) { Text("Verwerfen") }
            }
        }
        HorizontalDivider()
        val p = plan
        if (p != null) {
            Text("Vorschau: $fileName", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Text("Erkannte Spalten: " + p.mapping.entries.joinToString { "${it.value} → ${it.key}" })
            Text("Neu: ${p.created} · Geändert: ${p.updated} · Unverändert: ${p.unchanged} · Fehler: ${p.errors.size}" +
                (if (p.newCategories.isNotEmpty()) " · neue Warengruppen: ${p.newCategories.joinToString()}" else "") +
                (if (p.usedNetPrices) " · Bruttopreise aus Netto berechnet" else ""))
            if (p.missingInFile.isNotEmpty()) Text("${p.missingInFile.size} aktive Kassenartikel sind nicht in der Datei (bleiben unverändert).")
            LazyColumn(Modifier.fillMaxWidth()) {
                items(p.errors) { Text(it, color = Color(0xFFB71C1C)) }
                items(p.articles.filter { it.change != JtlImporter.Change.UNCHANGED }) { a ->
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text(if (a.change == JtlImporter.Change.NEW) "neu" else "geändert", Modifier.width(80.dp))
                        Text(a.row.sku, Modifier.width(120.dp))
                        Text(a.row.name, Modifier.weight(1f))
                        Text(a.row.taxRate.label, Modifier.width(120.dp))
                        Text(Money.formatEur(a.row.priceCents), Modifier.width(100.dp))
                    }
                }
            }
        } else {
            Text("Bisherige Importe", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            LazyColumn {
                items(runs) { r ->
                    Text("${java.text.DateFormat.getDateTimeInstance().format(r.timestamp)} · ${r.fileName} · ${r.userName} · neu ${r.created}, geändert ${r.updated}, unverändert ${r.unchanged}")
                }
            }
        }
    }
    message?.let { MessageDialog("Import", it) { message = null } }
}
