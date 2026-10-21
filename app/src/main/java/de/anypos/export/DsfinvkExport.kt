package de.anypos.export

import de.anypos.data.BusinessCase
import de.anypos.data.DailyClosing
import de.anypos.data.PaymentType
import de.anypos.data.PosDatabase
import de.anypos.domain.PosService
import de.anypos.data.ReceiptLine
import de.anypos.data.ReceiptType
import de.anypos.data.ReceiptWithDetails
import de.anypos.data.TaxRate
import de.anypos.data.TseInfo
import de.anypos.domain.Money
import de.anypos.domain.TaxSummary
import de.anypos.hardware.Company
import java.io.OutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Alles, was für einen DSFinV-K-Export benötigt wird; wird aus der Datenbank geladen. */
data class DsfinvkInput(
    val company: Company,
    val registerId: String,
    val deviceModel: String,
    val softwareVersion: String,
    val taxRates: List<TaxRate>,
    val tseInfos: List<TseInfo>,
    val closings: List<ClosingData>,
    /** Z-Nr. je Abschluss-ID, auch außerhalb des Exportzeitraums (für Storno-Referenzen). */
    val closingNumbers: Map<Long, Long>,
    /** Belegnummer -> (Abschluss-ID, Vorgangsende) für referenzierte Originalbelege. */
    val referencedReceipts: Map<Long, Pair<Long?, Long?>>,
)

data class ClosingData(val closing: DailyClosing, val receipts: List<ReceiptWithDetails>)

/** Lädt alle Tagesabschlüsse, die im Zeitraum [from, to) erstellt wurden, samt Belegen. */
suspend fun loadDsfinvkInput(
    db: PosDatabase, pos: PosService, company: Company, registerId: String,
    deviceModel: String, softwareVersion: String, from: Long, to: Long,
): DsfinvkInput {
    val closings = db.closings().between(from, to).map { z ->
        ClosingData(z, db.receipts().byClosing(z.id).map { pos.load(it.id) })
    }
    val refNumbers = closings.flatMap { c -> c.receipts.mapNotNull { it.receipt.referencesReceiptNumber } }.distinct()
    val referenced = if (refNumbers.isEmpty()) emptyMap()
    else db.receipts().byNumbers(refNumbers).associate { it.number to (it.closingId to it.finishedAt) }
    return DsfinvkInput(
        company = company,
        registerId = registerId,
        deviceModel = deviceModel,
        softwareVersion = softwareVersion,
        taxRates = db.masterData().taxRatesOnce(),
        tseInfos = db.tseInfo().all(),
        closings = closings,
        closingNumbers = db.closings().allOnce().associate { it.id to it.number },
        referencedReceipts = referenced,
    )
}

data class DsfinvkResult(val files: Map<String, String>, val warnings: List<String>)

/**
 * Export nach DSFinV-K 2.4 (Digitale Schnittstelle der Finanzverwaltung für Kassensysteme):
 * Stammdatenmodul, Kassenabschlussmodul und Einzelaufzeichnungsmodul je Tagesabschluss (Z-Nr.).
 *
 * Abbildungsregeln dieser Kasse:
 *  - BON_ID = Belegnummer (kassenweit eindeutig, fortlaufend)
 *  - Storno = eigener Beleg mit umgekehrten Vorzeichen, BON_STORNO = 1, Verweis in references.csv
 *  - Abschlusssummen (businesscases/payment/cash_per_currency) nur aus Bons vom Typ "Beleg"
 *  - USt je Position mit 5 Nachkommastellen, je Bon wie auf dem gedruckten Beleg (2 Stellen)
 */
object DsfinvkExport {
    const val TAXONOMY_VERSION = "2.4"
    const val CURRENCY = "EUR"
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val stampFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX").withZone(zone)
    private val tseFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    private const val CERT_CHUNK = 1000

    fun stamp(millis: Long): String = stampFmt.format(Instant.ofEpochMilli(millis))
    fun tseStamp(millis: Long): String = tseFmt.format(Instant.ofEpochMilli(millis))

    /** Blockierende Fehler: ohne diese Angaben ist der Export unvollständig. */
    fun validate(input: DsfinvkInput): List<String> = buildList {
        val c = input.company
        if (c.name.isBlank()) add("Firmenname fehlt")
        if (c.street.isBlank()) add("Straße fehlt")
        if (c.zip.isBlank()) add("PLZ fehlt")
        if (c.city.isBlank()) add("Ort fehlt")
        if (!Regex("[A-Z]{3}").matches(c.country)) add("Land muss ein ISO-3166-Alpha-3-Code sein (z. B. DEU)")
        if (c.taxNumber.isBlank() && c.vatId.isBlank()) add("Steuernummer oder USt-IdNr. muss angegeben sein")
        if (input.registerId.isBlank()) add("Kassen-ID fehlt")
        if (input.closings.isEmpty()) add("Im gewählten Zeitraum gibt es keinen Tagesabschluss (Z-Bon)")
    }

    /** Hinweise, die den Export nicht verhindern, aber vor einer Prüfung geklärt sein müssen. */
    private fun dataWarnings(input: DsfinvkInput, tseInfos: List<TseInfo>): List<String> = buildList {
        val receipts = input.closings.flatMap { it.receipts }
        val unsigned = receipts.count { it.receipt.tseSignature == null }
        if (unsigned > 0) add("$unsigned Vorgänge ohne TSE-Signatur (TSE-Ausfall) – Ausfall dokumentieren")
        if (tseInfos.any { it.signatureAlgorithm == "SIMULATION" }) add("Export enthält Vorgänge der TSE-Simulation – steuerlich ungültig")
        tseInfos.filter { it.certificate.isEmpty() }.forEach {
            add("Zertifikat der TSE ${it.serial} ist nicht gespeichert – aus dem TSE-Export (TAR) nachreichen")
        }
        tseInfos.filter { it.certificate.length > 2 * CERT_CHUNK }.forEach {
            add("Zertifikat der TSE ${it.serial} länger als 2000 Zeichen – wird gekürzt, Exportformat erweitern")
        }
    }

    /**
     * Gespeicherte TSE-Stammdaten, ergänzt um TSEs, die nur aus Belegen bekannt sind (z. B. Belege vor
     * Einführung der Tabelle tse_info). So gehen TSE-Daten im Export nie stillschweigend verloren.
     */
    fun effectiveTseInfos(input: DsfinvkInput): List<TseInfo> {
        val known = input.tseInfos.map { it.serial }.toSet()
        val derived = input.closings.flatMap { it.receipts }.map { it.receipt }
            .filter { it.tseSignature != null && it.tseSerial != null && it.tseSerial !in known }
            .groupBy { it.tseSerial!! }
            .map { (serial, list) ->
                val first = list.minBy { it.tseEnd ?: Long.MAX_VALUE }
                TseInfo(serial, first.tseSigAlgorithm.orEmpty(), first.tseTimeFormat.orEmpty(), first.tsePublicKey.orEmpty(),
                    certificate = "", firstUsedAt = first.tseEnd ?: first.createdAt)
            }
        return input.tseInfos + derived
    }

    fun build(input: DsfinvkInput, schema: DsfinvkSchema): DsfinvkResult {
        val w = DsfinvkTableWriter(schema)
        val tseInfos = effectiveTseInfos(input)
        val tseIds = tseInfos.sortedBy { it.firstUsedAt }.mapIndexed { i, t -> t.serial to (i + 1L) }.toMap()

        for (cd in input.closings) {
            val z = cd.closing
            val key = mapOf("Z_KASSE_ID" to input.registerId, "Z_ERSTELLUNG" to stamp(z.createdAt), "Z_NR" to z.number)
            val belege = cd.receipts.filter { it.receipt.type == ReceiptType.SALE && !it.receipt.training }
            val c = input.company

            // --- Stammdatenmodul
            w.add("cashpointclosing.csv", key + mapOf(
                "TAXONOMIE_VERSION" to TAXONOMY_VERSION,
                "Z_START_ID" to cd.receipts.minOfOrNull { it.receipt.number }?.toString(),
                "Z_ENDE_ID" to cd.receipts.maxOfOrNull { it.receipt.number }?.toString(),
                "NAME" to c.name, "STRASSE" to c.street, "PLZ" to c.zip, "ORT" to c.city, "LAND" to c.country,
                "STNR" to c.taxNumber.ifBlank { null }, "USTID" to c.vatId.ifBlank { null },
                "Z_SE_ZAHLUNGEN" to cents(belege.flatMap { it.payments }.sumOf { it.amountCents }),
                "Z_SE_BARZAHLUNGEN" to cents(belege.flatMap { it.payments }.filter { it.type == PaymentType.CASH }.sumOf { it.amountCents }),
            ))
            w.add("location.csv", key + mapOf(
                "LOC_NAME" to c.name, "LOC_STRASSE" to c.street, "LOC_PLZ" to c.zip, "LOC_ORT" to c.city,
                "LOC_LAND" to c.country, "LOC_USTID" to c.vatId.ifBlank { null },
            ))
            w.add("cashregister.csv", key + mapOf(
                "KASSE_BRAND" to "Any.POS", "KASSE_MODELL" to input.deviceModel, "KASSE_SERIENNR" to input.registerId,
                "KASSE_SW_BRAND" to "Any.POS", "KASSE_SW_VERSION" to input.softwareVersion,
                "KASSE_BASISWAEH_CODE" to CURRENCY, "KEINE_UST_ZUORDNUNG" to false,
            ))
            val usedSerials = cd.receipts.mapNotNull { r -> r.receipt.tseSerial.takeIf { r.receipt.tseSignature != null } }.toSet()
            for (t in tseInfos.filter { it.serial in usedSerials }) {
                w.add("tse.csv", key + mapOf(
                    "TSE_ID" to tseIds.getValue(t.serial), "TSE_SERIAL" to t.serial, "TSE_SIG_ALGO" to t.signatureAlgorithm,
                    "TSE_ZEITFORMAT" to t.timeFormat, "TSE_PD_ENCODING" to t.pdEncoding, "TSE_PUBLIC_KEY" to t.publicKey,
                    "TSE_ZERTIFIKAT_I" to t.certificate.take(CERT_CHUNK).ifEmpty { null },
                    "TSE_ZERTIFIKAT_II" to t.certificate.drop(CERT_CHUNK).take(CERT_CHUNK).ifEmpty { null },
                ))
            }
            for (rate in input.taxRates) {
                w.add("vat.csv", key + mapOf(
                    "UST_SCHLUESSEL" to rate.dsfinvkKey, "UST_SATZ" to BigDecimal.valueOf(rate.percentBp.toLong(), 2), "UST_BESCHR" to rate.label,
                ))
            }

            // --- Kassenabschlussmodul
            val gv = LinkedHashMap<Pair<String, Int>, Triple<BigDecimal, BigDecimal, BigDecimal>>()
            for (l in belege.flatMap { it.lines }) {
                val (gross, net, vat) = lineAmounts(l.totalCents, l.taxPercentBp)
                val k = l.businessCase to l.dsfinvkKey
                val prev = gv[k] ?: Triple(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
                gv[k] = Triple(prev.first + gross, prev.second + net, prev.third + vat)
            }
            gv.forEach { (k, v) ->
                w.add("businesscases.csv", key + mapOf(
                    "GV_TYP" to k.first, "UST_SCHLUESSEL" to k.second, "Z_UMS_BRUTTO" to v.first, "Z_UMS_NETTO" to v.second, "Z_UST" to v.third,
                ))
            }
            belege.flatMap { it.payments }.groupBy { it.type to it.label }.forEach { (k, p) ->
                w.add("payment.csv", key + mapOf("ZAHLART_TYP" to k.first, "ZAHLART_NAME" to k.second, "Z_ZAHLART_BETRAG" to cents(p.sumOf { it.amountCents })))
            }
            w.add("cash_per_currency.csv", key + mapOf(
                "ZAHLART_WAEH" to CURRENCY,
                "ZAHLART_BETRAG_WAEH" to cents(belege.flatMap { it.payments }.filter { it.type == PaymentType.CASH }.sumOf { it.amountCents }),
            ))

            // --- Einzelaufzeichnungsmodul
            for (r in cd.receipts) {
                val x = r.receipt
                val bon = key + mapOf("BON_ID" to x.number.toString())
                w.add("transactions.csv", bon + mapOf(
                    "BON_NR" to x.number, "BON_TYP" to x.type,
                    "BON_STORNO" to x.isCancellation,
                    "BON_START" to stamp(x.createdAt), "BON_ENDE" to stamp(x.finishedAt ?: x.createdAt),
                    "BEDIENER_ID" to x.userId.toString(), "BEDIENER_NAME" to x.userName,
                    "UMS_BRUTTO" to cents(x.totalCents),
                ))
                r.payments.groupBy { it.type to it.label }.forEach { (k, p) ->
                    w.add("datapayment.csv", bon + mapOf(
                        "ZAHLART_TYP" to k.first, "ZAHLART_NAME" to k.second, "ZAHLWAEH_CODE" to CURRENCY,
                        "BASISWAEH_BETRAG" to cents(p.sumOf { it.amountCents }),
                    ))
                }
                for (b in TaxSummary.of(r.lines)) {
                    w.add("transactions_vat.csv", bon + mapOf(
                        "UST_SCHLUESSEL" to b.dsfinvkKey, "BON_BRUTTO" to cents(b.grossCents), "BON_NETTO" to cents(b.netCents), "BON_UST" to cents(b.taxCents),
                    ))
                }
                if (x.referencesReceiptNumber != null) {
                    val (origClosing, origEnd) = input.referencedReceipts[x.referencesReceiptNumber] ?: (null to null)
                    w.add("references.csv", bon + mapOf(
                        "REF_TYP" to "Transaktion", "REF_NAME" to if (x.isReturn) "Rueckgabe" else "Storno",
                        "REF_DATUM" to origEnd?.let { stamp(it) },
                        "REF_Z_KASSE_ID" to input.registerId,
                        "REF_Z_NR" to origClosing?.let { input.closingNumbers[it] },
                        "REF_BON_ID" to x.referencesReceiptNumber.toString(),
                    ))
                }
                if (x.tseSignature != null && x.tseSerial != null) {
                    w.add("transactions_tse.csv", bon + mapOf(
                        "TSE_ID" to tseIds.getValue(x.tseSerial), "TSE_TANR" to x.tseTxNumber,
                        "TSE_TA_START" to x.tseStart?.let { tseStamp(it) }, "TSE_TA_ENDE" to x.tseEnd?.let { tseStamp(it) },
                        "TSE_TA_VORGANGSART" to x.tseProcessType, "TSE_TA_SIGZ" to x.tseSigCounter, "TSE_TA_SIG" to x.tseSignature,
                        "TSE_VORGANGSDATEN" to x.tseProcessData,
                    ))
                }
                for (l in r.lines) line(w, bon, l)
            }
        }
        return DsfinvkResult(FILES.associateWith { w.csv(it) }, dataWarnings(input, tseInfos) + w.warnings)
    }

    private fun line(w: DsfinvkTableWriter, bon: Map<String, Any?>, l: ReceiptLine) {
        val pos = bon + mapOf("POS_ZEILE" to l.position.toString())
        val item = l.businessCase == BusinessCase.SALE
        w.add("lines.csv", pos + mapOf(
            "ARTIKELTEXT" to l.name, "GV_TYP" to l.businessCase, "INHAUS" to false, "P_STORNO" to false,
            "GUTSCHEIN_NR" to l.voucherCode,
            "ART_NR" to l.sku.takeIf { item && it.isNotBlank() }, "GTIN" to l.gtin.takeIf { item },
            "WARENGR_ID" to l.categoryId?.toString().takeIf { item }, "WARENGR" to l.categoryName.takeIf { item },
            "MENGE" to BigDecimal.valueOf(l.quantityMilli, 3).takeIf { item },
            "STK_BR" to cents(l.unitPriceCents).takeIf { item },
        ))
        val (gross, net, vat) = lineAmounts(l.totalCents, l.taxPercentBp)
        w.add("lines_vat.csv", pos + mapOf("UST_SCHLUESSEL" to l.dsfinvkKey, "POS_BRUTTO" to gross, "POS_NETTO" to net, "POS_UST" to vat))
        if (l.discountCents != 0L) {
            val base = Money.lineTotal(l.unitPriceCents, l.quantityMilli)
            val (bg, bn, bv) = lineAmounts(base, l.taxPercentBp)
            w.add("itemamounts.csv", pos + mapOf("TYP" to "base_amount", "UST_SCHLUESSEL" to l.dsfinvkKey, "PF_BRUTTO" to bg, "PF_NETTO" to bn, "PF_UST" to bv))
            val (dg, dn, dv) = lineAmounts(-l.discountCents, l.taxPercentBp)
            w.add("itemamounts.csv", pos + mapOf("TYP" to "discount", "UST_SCHLUESSEL" to l.dsfinvkKey, "PF_BRUTTO" to dg, "PF_NETTO" to dn, "PF_UST" to dv))
        }
    }

    private fun cents(c: Long): BigDecimal = BigDecimal.valueOf(c, 2)

    /** Brutto, Netto, USt einer Position mit 5 Nachkommastellen (DSFinV-K-Genauigkeit). */
    fun lineAmounts(grossCents: Long, percentBp: Int): Triple<BigDecimal, BigDecimal, BigDecimal> {
        val gross = cents(grossCents)
        val vat = gross.multiply(BigDecimal.valueOf(percentBp.toLong()))
            .divide(BigDecimal.valueOf(10000L + percentBp), 5, RoundingMode.HALF_UP)
        return Triple(gross, gross - vat, vat)
    }

    /** Alle Dateien in index.xml-Reihenfolge; leere Tabellen werden mit Kopfzeile geschrieben. */
    val FILES = listOf(
        "cashpointclosing.csv", "location.csv", "cashregister.csv", "slaves.csv", "pa.csv", "tse.csv", "vat.csv",
        "businesscases.csv", "payment.csv", "cash_per_currency.csv", "transactions.csv", "datapayment.csv",
        "lines.csv", "itemamounts.csv", "subitems.csv", "transactions_tse.csv", "transactions_vat.csv",
        "lines_vat.csv", "allocation_groups.csv", "references.csv",
    )

    /** Schreibt index.xml (Datenlieferant eingetragen), DTD und alle CSV-Dateien in ein ZIP. */
    fun writeZip(out: OutputStream, result: DsfinvkResult, indexXml: String, dtd: ByteArray, company: Company) {
        val index = indexXml
            .replaceFirst("<Name />", "<Name>${xml(company.name)}</Name>")
            .replaceFirst("<Location />", "<Location>${xml(company.zipCity)}</Location>")
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            put("index.xml", index.toByteArray(Charsets.UTF_8))
            put("gdpdu-01-09-2004.dtd", dtd)
            result.files.forEach { (name, csv) -> put(name, csv.toByteArray(Charsets.UTF_8)) }
        }
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
