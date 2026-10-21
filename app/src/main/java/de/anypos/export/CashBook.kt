package de.anypos.export

import de.anypos.data.BusinessCase
import de.anypos.data.PaymentType
import de.anypos.data.ReceiptState
import de.anypos.data.ReceiptWithDetails
import de.anypos.domain.Money
import de.anypos.importer.Csv
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Eine Kassenbuchzeile (nur Bargeldbewegungen). */
data class CashBookRow(
    val timestamp: Long,
    val receiptNumber: Long,
    val text: String,
    val businessCase: String,
    /** USt-Schlüssel (DSFinV-K) bei Umsätzen, sonst null. */
    val dsfinvkKey: Int?,
    val taxPercentBp: Int?,
    /** positiv = Einnahme, negativ = Ausgabe */
    val amountCents: Long,
    val balanceCents: Long,
)

/**
 * Kassenbuch: chronologische Aufstellung aller Bargeldbewegungen mit laufendem Bestand.
 * Barumsätze werden je Beleg und Steuersatz ausgewiesen; bei gemischter Zahlung (bar + Karte)
 * wird der Baranteil anteilig auf die Steuersätze verteilt.
 */
object CashBook {
    private val zone = ZoneId.of("Europe/Berlin")

    fun build(receipts: List<ReceiptWithDetails>, openingBalanceCents: Long): List<CashBookRow> {
        var balance = openingBalanceCents
        val rows = mutableListOf<CashBookRow>()
        for (r in receipts.sortedBy { it.receipt.number }) {
            if (r.receipt.state != ReceiptState.FINISHED || r.receipt.training) continue
            val ts = r.receipt.finishedAt ?: r.receipt.createdAt
            for (l in r.lines.filter { it.businessCase in BusinessCase.cashMovements }) {
                balance += l.totalCents
                rows += CashBookRow(ts, r.receipt.number, l.name, l.businessCase, null, null, l.totalCents, balance)
            }
            // Waren, Gutscheine, Retouren und Stornos: Baranteil je Geschäftsvorfall und Steuersatz
            val sales = r.lines.filter { it.businessCase !in BusinessCase.cashMovements }
            if (sales.isEmpty()) continue
            val cash = r.payments.filter { it.type == PaymentType.CASH }.sumOf { it.amountCents }
            if (cash == 0L) continue
            val prefix = when {
                r.receipt.isCancellation -> "Storno Beleg ${r.receipt.referencesReceiptNumber}"
                r.receipt.isReturn -> "Rückgabe zu Beleg ${r.receipt.referencesReceiptNumber}"
                else -> "Barumsatz"
            }
            val groups = sales.groupBy { Triple(it.businessCase, it.dsfinvkKey, it.taxPercentBp) }.mapValues { (_, l) -> l.sumOf { it.totalCents } }
            for ((key, part) in allocate(groups, cash)) {
                if (part == 0L) continue
                balance += part
                val text = if (key.first == BusinessCase.SALE) "$prefix ${Money.formatPercent(key.third)}" else BusinessCase.label(key.first)
                rows += CashBookRow(ts, r.receipt.number, text, key.first, key.second, key.third, part, balance)
            }
        }
        return rows
    }

    /** Verteilt [total] proportional auf die Gruppen; Rundungsrest geht an die größte Gruppe. */
    fun <K> allocate(groups: Map<K, Long>, total: Long): Map<K, Long> {
        val sum = groups.values.sum()
        if (sum == 0L) return groups.mapValues { 0L }
        if (sum == total) return groups
        val result = groups.mapValues { (_, v) ->
            java.math.BigDecimal.valueOf(v).multiply(java.math.BigDecimal.valueOf(total))
                .divide(java.math.BigDecimal.valueOf(sum), 0, java.math.RoundingMode.HALF_UP).toLong()
        }.toMutableMap()
        val rest = total - result.values.sum()
        if (rest != 0L) {
            val biggest = groups.maxBy { kotlin.math.abs(it.value) }.key
            result[biggest] = result.getValue(biggest) + rest
        }
        return result
    }

    fun toCsv(rows: List<CashBookRow>, openingBalanceCents: Long, registerId: String): String = buildString {
        val date = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(zone)
        val time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone)
        appendLine(Csv.line(listOf("Kasse", "Datum", "Uhrzeit", "Beleg-Nr", "Buchungstext", "Geschäftsvorfall", "USt-Satz", "Einnahme", "Ausgabe", "Bestand")))
        appendLine(Csv.line(listOf(registerId, "", "", "", "Anfangsbestand", "", "", "", "", Money.plainComma(openingBalanceCents))))
        for (r in rows) {
            val i = Instant.ofEpochMilli(r.timestamp)
            appendLine(Csv.line(listOf(
                registerId, date.format(i), time.format(i), r.receiptNumber.toString(), r.text, r.businessCase,
                r.taxPercentBp?.let { Money.formatPercent(it) } ?: "",
                if (r.amountCents > 0) Money.plainComma(r.amountCents) else "",
                if (r.amountCents < 0) Money.plainComma(-r.amountCents) else "",
                Money.plainComma(r.balanceCents),
            )))
        }
    }

    fun dayRange(from: LocalDate, toInclusive: LocalDate): Pair<Long, Long> =
        from.atStartOfDay(zone).toInstant().toEpochMilli() to toInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}
