package de.anypos.export

import de.anypos.data.BusinessCase
import de.anypos.data.PaymentType
import de.anypos.data.ReceiptState
import de.anypos.data.ReceiptWithDetails
import de.anypos.domain.Money
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Kontenzuordnung. Standardwerte sind SKR03-Vorschläge und mit dem Steuerberater abzustimmen. */
data class DatevAccounts(
    val cash: String,
    val revenue19: String,
    val revenue7: String,
    val revenue0: String,
    val card: String,
    val transit: String,
    val privateOut: String,
    val privateIn: String,
    val deposit: String,
    val payout: String,
    val difference: String,
    /** Verbindlichkeit aus ausgegebenen Mehrzweckgutscheinen; nur nötig, wenn solche verkauft/eingelöst werden. */
    val voucher: String = "",
)

data class DatevConfig(
    val advisorNumber: String,
    val clientNumber: String,
    /** MMdd */
    val fiscalYearStart: String,
    val accountLength: Int,
    val registerId: String,
    val accounts: DatevAccounts,
    val includeCardSales: Boolean,
)

/**
 * DATEV-Format "Buchungsstapel" (EXTF, Version 700) mit Tagessummen je Steuersatz
 * sowie jeder Bargeldbewegung als Einzelbuchung. Ausgabe in Windows-1252, wie von DATEV erwartet.
 */
object DatevExport {
    private val zone = ZoneId.of("Europe/Berlin")

    data class Booking(
        val amountCents: Long,   // immer positiv
        val debit: Boolean,      // S = Soll auf "Konto"
        val account: String,
        val contra: String,
        val date: LocalDate,
        val document: String,
        val text: String,
    )

    fun validate(config: DatevConfig): List<String> = buildList {
        if (config.advisorNumber.isBlank()) add("DATEV-Beraternummer fehlt")
        if (config.clientNumber.isBlank()) add("DATEV-Mandantennummer fehlt")
        val a = config.accounts
        listOf("Kasse" to a.cash, "Erlöse 19 %" to a.revenue19, "Erlöse 7 %" to a.revenue7, "Erlöse 0 %" to a.revenue0,
            "Geldtransit" to a.transit, "Privatentnahme" to a.privateOut, "Privateinlage" to a.privateIn,
            "Einzahlung" to a.deposit, "Auszahlung" to a.payout, "Kassendifferenz" to a.difference,
        ).filter { it.second.isBlank() }.forEach { add("Konto für ${it.first} fehlt") }
        if (config.includeCardSales && a.card.isBlank()) add("Konto für Kartenzahlungen fehlt")
    }

    fun bookings(receipts: List<ReceiptWithDetails>, config: DatevConfig): List<Booking> {
        val a = config.accounts
        val out = mutableListOf<Booking>()
        val valid = receipts.filter { it.receipt.state == ReceiptState.FINISHED && !it.receipt.training }

        // 1. Bargeldbewegungen einzeln
        for (r in valid) {
            val date = dateOf(r)
            // Anfangsbestand ist ein Saldovortrag in der Buchhaltung, keine Buchung
            for (l in r.lines.filter { it.businessCase in BusinessCase.cashMovements && it.businessCase != BusinessCase.OPENING }) {
                val contra = when (l.businessCase) {
                    BusinessCase.DEPOSIT -> a.deposit
                    BusinessCase.PAYOUT -> a.payout
                    BusinessCase.PRIVATE_IN -> a.privateIn
                    BusinessCase.PRIVATE_OUT -> a.privateOut
                    BusinessCase.TRANSIT -> a.transit
                    else -> a.difference
                }
                out += Booking(kotlin.math.abs(l.totalCents), l.totalCents > 0, a.cash, contra, date, r.receipt.number.toString(), "${l.businessCase} ${l.name}".take(60))
            }
        }

        // 2. Umsätze als Tagessumme je Gegenkonto und Zahlungsart. Einzweckgutscheine sind bei Ausgabe
        //    steuerpflichtig (Erlöskonto), Mehrzweckgutscheine eine Verbindlichkeit (Gutscheinkonto).
        val multiVoucher = listOf(BusinessCase.MULTI_VOUCHER_SALE, BusinessCase.MULTI_VOUCHER_REDEEM)
        if (a.voucher.isBlank() && valid.any { r -> r.lines.any { it.businessCase in multiVoucher } }) {
            throw IllegalStateException("Konto für Mehrzweckgutscheine fehlt (Einstellungen → DATEV)")
        }
        val salesByDay = valid.filter { r -> r.lines.any { it.businessCase !in BusinessCase.cashMovements } }.groupBy { dateOf(it) }
        for ((date, list) in salesByDay.toSortedMap()) {
            val cashBy = LinkedHashMap<Pair<String, String>, Long>()
            val cardBy = LinkedHashMap<Pair<String, String>, Long>()
            for (r in list) {
                val sales = r.lines.filter { it.businessCase !in BusinessCase.cashMovements }
                    .groupBy { if (it.businessCase in multiVoucher) a.voucher to "Mehrzweckgutscheine" else revenueAccount(it.dsfinvkKey, a) to label(it.dsfinvkKey) }
                    .mapValues { (_, l) -> l.sumOf { it.totalCents } }
                val cash = r.payments.filter { it.type == PaymentType.CASH }.sumOf { it.amountCents }
                val card = r.payments.filter { it.type != PaymentType.CASH }.sumOf { it.amountCents }
                CashBook.allocate(sales, cash).forEach { (k, v) -> cashBy.merge(k, v, Long::plus) }
                CashBook.allocate(sales, card).forEach { (k, v) -> cardBy.merge(k, v, Long::plus) }
            }
            val doc = "Z" + date.format(DateTimeFormatter.BASIC_ISO_DATE)
            for ((key, sum) in cashBy) if (sum != 0L) {
                out += Booking(kotlin.math.abs(sum), sum > 0, a.cash, key.first, date, doc, "Barumsatz ${key.second} ${config.registerId}")
            }
            if (config.includeCardSales) for ((key, sum) in cardBy) if (sum != 0L) {
                out += Booking(kotlin.math.abs(sum), sum > 0, a.card, key.first, date, doc, "Kartenumsatz ${key.second} ${config.registerId}")
            }
        }
        return out.sortedWith(compareBy({ it.date }, { it.document }))
    }

    fun revenueAccount(dsfinvkKey: Int, a: DatevAccounts) = when (dsfinvkKey) { 1 -> a.revenue19; 2 -> a.revenue7; else -> a.revenue0 }

    private fun label(key: Int) = when (key) { 1 -> "19%"; 2 -> "7%"; else -> "USt-Schl. $key" }

    private fun dateOf(r: ReceiptWithDetails): LocalDate =
        Instant.ofEpochMilli(r.receipt.finishedAt ?: r.receipt.createdAt).atZone(zone).toLocalDate()

    fun render(bookings: List<Booking>, config: DatevConfig, from: LocalDate, to: LocalDate, now: LocalDateTime = LocalDateTime.now(zone)): String {
        val fyStart = LocalDate.of(from.year, config.fiscalYearStart.take(2).toInt(), config.fiscalYearStart.takeLast(2).toInt())
            .let { if (it.isAfter(from)) it.minusYears(1) else it }
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val d8 = DateTimeFormatter.BASIC_ISO_DATE
        val header = listOf(
            q("EXTF"), "700", "21", q("Buchungsstapel"), "13", now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")), "",
            q("AP"), q("Any.POS"), q(""), config.advisorNumber, config.clientNumber, fyStart.format(d8), config.accountLength.toString(),
            from.format(d8), to.format(d8), q("Kassenbuch ${config.registerId}"), q(""), "1", "0", "0", q("EUR"),
            "", q(""), "", "", q(""), "", "", q(""), q(""),
        ).joinToString(";")
        val columns = listOf(
            "Umsatz (ohne Soll/Haben-Kz)", "Soll/Haben-Kennzeichen", "WKZ Umsatz", "Kurs", "Basis-Umsatz", "WKZ Basis-Umsatz",
            "Konto", "Gegenkonto (ohne BU-Schlüssel)", "BU-Schlüssel", "Belegdatum", "Belegfeld 1", "Belegfeld 2", "Skonto", "Buchungstext",
        ).joinToString(";")
        return buildString {
            append(header).append("\r\n")
            append(columns).append("\r\n")
            for (b in bookings) {
                append(listOf(
                    Money.plainComma(b.amountCents), q(if (b.debit) "S" else "H"), q("EUR"), "", "", q(""),
                    b.account, b.contra, q(""), b.date.format(DateTimeFormatter.ofPattern("ddMM")),
                    q(b.document.take(36)), q(""), "", q(b.text.take(60)),
                ).joinToString(";")).append("\r\n")
            }
        }
    }

    fun encode(text: String): ByteArray = text.toByteArray(charset("windows-1252"))
}
