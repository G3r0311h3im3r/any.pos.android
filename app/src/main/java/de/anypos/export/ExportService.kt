package de.anypos.export

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import de.anypos.BuildConfig
import de.anypos.data.PosDatabase
import de.anypos.domain.AuditLog
import de.anypos.domain.Key
import de.anypos.domain.PosService
import de.anypos.domain.SettingsRepository
import de.anypos.hardware.Company
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Fertige Exportdatei (Inhalt im Speicher, Monatsdaten sind klein). */
class ExportFile(val name: String, val mime: String, val bytes: ByteArray, val warnings: List<String> = emptyList())

/** Export nicht möglich (fehlende Einstellungen oder Daten); [problems] sind für die Anzeige gedacht. */
class ExportException(val problems: List<String>) : Exception(problems.joinToString("\n"))

data class CashBookMonth(val openingCents: Long, val rows: List<CashBookRow>)

/**
 * Erzeugt Kassenbuch-, DATEV- und DSFinV-K-Exporte. Wird vom Bildschirm (Speichern/Teilen) und vom
 * automatischen Monatsexport gleichermaßen genutzt, damit alle Wege dieselben Dateien liefern.
 */
class ExportService(
    private val context: Context,
    private val db: PosDatabase,
    private val pos: PosService,
    private val settings: SettingsRepository,
    private val audit: AuditLog,
    private val company: () -> Company,
) {
    private val zone = ZoneId.of("Europe/Berlin")
    private val registerId get() = settings[Key.REGISTER_ID]

    fun datevConfig(includeCard: Boolean) = DatevConfig(
        advisorNumber = settings[Key.DATEV_ADVISOR],
        clientNumber = settings[Key.DATEV_CLIENT],
        fiscalYearStart = settings[Key.DATEV_FY_START],
        accountLength = settings.int(Key.DATEV_ACCOUNT_LENGTH),
        registerId = registerId,
        accounts = DatevAccounts(
            cash = settings[Key.ACC_CASH], revenue19 = settings[Key.ACC_REV_19], revenue7 = settings[Key.ACC_REV_7],
            revenue0 = settings[Key.ACC_REV_0], card = settings[Key.ACC_CARD], transit = settings[Key.ACC_TRANSIT],
            privateOut = settings[Key.ACC_PRIVATE_OUT], privateIn = settings[Key.ACC_PRIVATE_IN], deposit = settings[Key.ACC_DEPOSIT],
            payout = settings[Key.ACC_PAYOUT], difference = settings[Key.ACC_DIFFERENCE], voucher = settings[Key.ACC_VOUCHER],
        ),
        includeCardSales = includeCard,
    )

    /** Kassenbuch eines Monats mit Anfangsbestand aus allen früheren Bargeldbewegungen. */
    suspend fun cashBook(month: YearMonth): CashBookMonth {
        val (from, to) = CashBook.dayRange(month.atDay(1), month.atEndOfMonth())
        val before = db.receipts().finishedBetween(0, from).map { pos.load(it.id) }
        val opening = CashBook.build(before, 0).lastOrNull()?.balanceCents ?: 0
        val inRange = db.receipts().finishedBetween(from, to).map { pos.load(it.id) }
        return CashBookMonth(opening, CashBook.build(inRange, opening))
    }

    suspend fun cashBookCsv(month: YearMonth): ExportFile {
        val book = cashBook(month)
        val csv = CashBook.toCsv(book.rows, book.openingCents, registerId)
        return ExportFile("Kassenbuch_${safe(registerId)}_$month.csv", "text/csv", ("﻿" + csv).toByteArray(Charsets.UTF_8))
    }

    suspend fun datev(month: YearMonth, includeCard: Boolean): ExportFile {
        val config = datevConfig(includeCard)
        DatevExport.validate(config).takeIf { it.isNotEmpty() }?.let { throw ExportException(listOf("DATEV-Einstellungen unvollständig:") + it) }
        val (from, to) = CashBook.dayRange(month.atDay(1), month.atEndOfMonth())
        val receipts = db.receipts().finishedBetween(from, to).map { pos.load(it.id) }
        val bookings = try { DatevExport.bookings(receipts, config) } catch (e: IllegalStateException) { throw ExportException(listOf(e.message ?: "DATEV-Fehler")) }
        val text = DatevExport.render(bookings, config, month.atDay(1), month.atEndOfMonth())
        return ExportFile("EXTF_Buchungsstapel_${safe(registerId)}_$month.csv", "text/csv", DatevExport.encode(text), DatevExport.warnings(config))
    }

    /** DSFinV-K-Export aller Tagesabschlüsse, die im Zeitraum [from, to) erstellt wurden. */
    suspend fun dsfinvk(from: Long, to: Long, label: String, deviceModel: String): ExportFile = withContext(Dispatchers.IO) {
        val input = loadDsfinvkInput(db, pos, company(), registerId, deviceModel, BuildConfig.VERSION_NAME, from, to)
        DsfinvkExport.validate(input).takeIf { it.isNotEmpty() }?.let { throw ExportException(it) }
        val indexXml = context.assets.open("dsfinvk/index.xml").use { String(it.readBytes(), Charsets.UTF_8) }
        val schema = DsfinvkSchema.parse(indexXml.byteInputStream())
        val dtd = context.assets.open("dsfinvk/gdpdu-01-09-2004.dtd").use { it.readBytes() }
        val result = DsfinvkExport.build(input, schema)
        val out = ByteArrayOutputStream()
        DsfinvkExport.writeZip(out, result, indexXml, dtd, input.company)
        ExportFile("DSFinV-K_${safe(registerId)}_$label.zip", "application/zip", out.toByteArray(), result.warnings)
    }

    /**
     * Automatischer Monatsexport in den Sicherungsordner: für jeden abgeschlossenen, noch nicht
     * exportierten Kalendermonat Kassenbuch, DATEV-Buchungsstapel und DSFinV-K. Liefert Hinweise
     * für die Anzeige; ein Monat gilt als erledigt, sobald seine Dateien geschrieben sind.
     */
    suspend fun monthlyExport(userName: String, deviceModel: String, now: Long = System.currentTimeMillis()): List<String> {
        val tree = settings[Key.BACKUP_TREE_URI]
        if (tree.isBlank()) return listOf("Monatsexport nicht möglich: kein Sicherungsordner eingestellt (Einstellungen → Datensicherung)")
        val months = pendingMonths(now)
        val notes = mutableListOf<String>()
        for (m in months) {
            val written = mutableListOf<String>()
            val (from, to) = CashBook.dayRange(m.atDay(1), m.atEndOfMonth())
            write(tree, cashBookCsv(m)); written += "Kassenbuch"
            try { write(tree, datev(m, includeCard = true)); written += "DATEV" } catch (e: ExportException) {
                notes += "DATEV $m übersprungen: ${e.problems.joinToString(" ")}"
            }
            if (db.closings().between(from, to).isNotEmpty()) {
                try {
                    val f = dsfinvk(from, to, m.toString(), deviceModel)
                    write(tree, f); written += "DSFinV-K"
                    f.warnings.forEach { notes += "DSFinV-K $m: $it" }
                } catch (e: ExportException) {
                    notes += "DSFinV-K $m übersprungen: ${e.problems.joinToString(" ")}"
                }
            }
            settings.put(Key.EXPORT_LAST_MONTH, m.toString())
            audit.log(userName, "MONATSEXPORT", "$m: ${written.joinToString()}")
            notes.add(0, "Monatsexport $m gespeichert: ${written.joinToString()}")
        }
        return notes
    }

    /** Vollständig vergangene Monate seit dem letzten Export (beim ersten Mal ab dem ersten Beleg). */
    suspend fun pendingMonths(now: Long): List<YearMonth> {
        val previous = YearMonth.from(Instant.ofEpochMilli(now).atZone(zone)).minusMonths(1)
        val last = settings[Key.EXPORT_LAST_MONTH].takeIf { it.isNotBlank() }?.let(YearMonth::parse)
        val start = last?.plusMonths(1) ?: run {
            val first = db.receipts().firstFinishedAt() ?: return emptyList()
            YearMonth.from(Instant.ofEpochMilli(first).atZone(zone))
        }
        return generateSequence(start) { it.plusMonths(1) }.takeWhile { !it.isAfter(previous) }.toList()
    }

    private suspend fun write(tree: String, file: ExportFile) = withContext(Dispatchers.IO) {
        val uri = DocumentTree.createFile(context, Uri.parse(tree), file.name, file.mime)
        context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(file.bytes) }
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9-]"), "_")
}

/** Dateien in einem per Dateiauswahl freigegebenen Ordner (USB-Stick, Cloud-Ordner, NAS) anlegen. */
object DocumentTree {
    fun createFile(context: Context, tree: Uri, name: String, mime: String): Uri {
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return DocumentsContract.createDocument(context.contentResolver, dir, mime, name)
            ?: throw ExportException(listOf("Datei $name konnte im Ordner nicht angelegt werden (Ordner noch erreichbar?)"))
    }
}
