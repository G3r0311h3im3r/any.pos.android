package de.anypos.hardware

import de.anypos.data.BusinessCase
import de.anypos.data.DailyClosing
import de.anypos.data.PaymentType
import de.anypos.data.ReceiptType
import de.anypos.data.ReceiptWithDetails
import de.anypos.tse.EpsonTse
import de.anypos.domain.Money
import de.anypos.domain.TaxSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Geräteunabhängige Belegbeschreibung; wird auf ESC/POS oder als Text (Vorschau, Tests) ausgegeben. */
sealed interface DocLine {
    data class Text(val text: String, val bold: Boolean = false, val center: Boolean = false, val big: Boolean = false) : DocLine
    data class Row(val left: String, val right: String, val bold: Boolean = false) : DocLine
    data object Separator : DocLine
    data class Qr(val data: String) : DocLine
}

data class Company(
    val name: String,
    val street: String,
    val zip: String,
    val city: String,
    val taxNumber: String,
    val vatId: String,
    val registerId: String,
    val footer: String,
    val country: String = "DEU",
) {
    val zipCity: String get() = "$zip $city".trim()
}

object ReceiptDocument {
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val dateTime = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(zone)
    private val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zone)

    fun receipt(company: Company, r: ReceiptWithDetails, copy: Boolean = false): List<DocLine> = buildList {
        val receipt = r.receipt
        header(company)
        if (copy) add(DocLine.Text("*** KOPIE ***", bold = true, center = true))
        if (receipt.type == ReceiptType.TRAINING) add(DocLine.Text("*** TRAININGSBELEG ***", bold = true, center = true))
        if (receipt.tseSigAlgorithm == "SIMULATION") add(DocLine.Text("SIMULATION – KEIN GÜLTIGER BELEG", bold = true, center = true))
        if (EpsonTse.isMockSerial(receipt.tseSerial)) add(DocLine.Text(EpsonTse.MOCK_NOTICE, bold = true, center = true))
        if (receipt.isCancellation) add(DocLine.Text("STORNO zu Beleg ${receipt.referencesReceiptNumber}", bold = true, center = true))
        if (receipt.isReturn) add(DocLine.Text("RÜCKGABE zu Beleg ${receipt.referencesReceiptNumber}", bold = true, center = true))
        add(DocLine.Row("Beleg-Nr.", receipt.number.toString()))
        add(DocLine.Row("Datum", dateTime.format(Instant.ofEpochMilli(receipt.finishedAt ?: receipt.createdAt))))
        add(DocLine.Row("Kasse", company.registerId))
        add(DocLine.Row("Bediener", receipt.userName))
        add(DocLine.Separator)

        for (l in r.lines) {
            val label = when {
                l.businessCase == BusinessCase.SALE -> l.name
                l.voucherCode != null -> "${BusinessCase.label(l.businessCase)} ${l.voucherCode}"
                else -> "${l.businessCase}: ${l.name}"
            }
            val code = taxCode(l.dsfinvkKey)
            if (l.quantityMilli == 1000L && l.discountCents == 0L) {
                add(DocLine.Row(label, "${Money.format(l.totalCents)} $code"))
            } else {
                add(DocLine.Text(label))
                add(DocLine.Row("  ${Money.formatQuantity(l.quantityMilli)} x ${Money.format(l.unitPriceCents)}",
                    "${Money.format(Money.lineTotal(l.unitPriceCents, l.quantityMilli))} $code"))
                if (l.discountCents != 0L) add(DocLine.Row("  Rabatt", "-${Money.format(l.discountCents)} $code"))
            }
        }
        add(DocLine.Separator)
        add(DocLine.Row("SUMME EUR", Money.format(receipt.totalCents), bold = true))
        add(DocLine.Separator)
        for (l in r.lines.filter { it.businessCase in BusinessCase.voucherSales && it.quantityMilli > 0 }) {
            add(DocLine.Text("GUTSCHEIN ${l.voucherCode}", bold = true, center = true, big = true))
            add(DocLine.Text("Wert ${Money.formatEur(l.totalCents)}", center = true))
            if (l.businessCase == BusinessCase.MULTI_VOUCHER_SALE) add(DocLine.Text("Mehrzweckgutschein – USt bei Einlösung", center = true))
            add(DocLine.Qr(l.voucherCode!!))
            add(DocLine.Separator)
        }

        for (p in r.payments) {
            add(DocLine.Row(p.label, Money.format(p.amountCents)))
            if (p.type == PaymentType.CASH && p.givenCents != null && p.givenCents > p.amountCents) {
                add(DocLine.Row("  gegeben", Money.format(p.givenCents)))
                add(DocLine.Row("  Rückgeld", Money.format(p.givenCents - p.amountCents)))
            }
        }
        if (r.payments.isNotEmpty()) add(DocLine.Separator)

        add(DocLine.Text("MwSt       Netto      Steuer      Brutto"))
        for (b in TaxSummary.of(r.lines)) {
            add(DocLine.Text("${taxCode(b.dsfinvkKey)} ${Money.formatPercent(b.percentBp).padEnd(7)}" +
                Money.format(b.netCents).padStart(9) + Money.format(b.taxCents).padStart(12) + Money.format(b.grossCents).padStart(12)))
        }
        add(DocLine.Separator)

        tseBlock(receipt.tseError, r)
        add(DocLine.Text(""))
        company.footer.lines().forEach { add(DocLine.Text(it, center = true)) }
    }

    private fun MutableList<DocLine>.header(company: Company) {
        add(DocLine.Text(company.name, bold = true, center = true, big = true))
        listOf(company.street, company.zipCity).filter { it.isNotBlank() }.forEach { add(DocLine.Text(it, center = true)) }
        if (company.taxNumber.isNotBlank()) add(DocLine.Text("St.-Nr. ${company.taxNumber}", center = true))
        if (company.vatId.isNotBlank()) add(DocLine.Text("USt-IdNr. ${company.vatId}", center = true))
        add(DocLine.Separator)
    }

    private fun MutableList<DocLine>.tseBlock(error: String?, r: ReceiptWithDetails) {
        val x = r.receipt
        if (x.tseSignature == null) {
            add(DocLine.Text("TSE AUSGEFALLEN / NICHT VERFÜGBAR", bold = true, center = true))
            error?.let { add(DocLine.Text(it)) }
            add(DocLine.Row("Vorgangsbeginn", dateTime.format(Instant.ofEpochMilli(x.createdAt))))
            return
        }
        add(DocLine.Text("TSE-Daten", bold = true))
        add(DocLine.Row("TSE-Seriennr.", ""))
        add(DocLine.Text(x.tseSerial.orEmpty()))
        add(DocLine.Row("Transaktion", x.tseTxNumber.toString()))
        add(DocLine.Row("Signaturzähler", x.tseSigCounter.toString()))
        add(DocLine.Row("Start", dateTime.format(Instant.ofEpochMilli(x.tseStart!!))))
        add(DocLine.Row("Ende", dateTime.format(Instant.ofEpochMilli(x.tseEnd!!))))
        add(DocLine.Text("Signatur:"))
        add(DocLine.Text(x.tseSignature))
        add(DocLine.Qr(qrPayload(r)))
    }

    /** QR-Code nach DSFinV-K Anhang I ("V0;…"). */
    fun qrPayload(r: ReceiptWithDetails): String {
        val x = r.receipt
        return listOf(
            "V0", x.tseSerial ?: "", x.tseProcessType ?: "", x.tseProcessData ?: "", x.tseTxNumber?.toString() ?: "",
            x.tseSigCounter?.toString() ?: "", x.tseStart?.let { iso.format(Instant.ofEpochMilli(it)) } ?: "",
            x.tseEnd?.let { iso.format(Instant.ofEpochMilli(it)) } ?: "", x.tseSigAlgorithm ?: "", x.tseTimeFormat ?: "",
            x.tseSignature ?: "", x.tsePublicKey ?: "",
        ).joinToString(";")
    }

    fun closing(company: Company, c: DailyClosing, final: Boolean, taxBuckets: List<de.anypos.domain.TaxBucket>): List<DocLine> = buildList {
        header(company)
        add(DocLine.Text(if (final) "Z-BON (Tagesabschluss)" else "X-BON (Zwischenbericht)", bold = true, center = true, big = true))
        if (final) add(DocLine.Row("Z-Nr.", c.number.toString()))
        add(DocLine.Row("Datum", dateTime.format(Instant.ofEpochMilli(c.createdAt))))
        add(DocLine.Row("Kasse", company.registerId))
        add(DocLine.Row("Bediener", c.userName))
        add(DocLine.Row("Belege", "${c.firstReceiptNumber ?: "-"} – ${c.lastReceiptNumber ?: "-"} (${c.receiptCount})"))
        add(DocLine.Separator)
        add(DocLine.Row("Warenumsatz", Money.format(c.totalCents), bold = true))
        if (c.voucherSalesCents != 0L) add(DocLine.Row("Gutscheinverkauf", Money.format(c.voucherSalesCents)))
        if (c.voucherRedemptionsCents != 0L) add(DocLine.Row("Gutscheineinlösung", Money.format(c.voucherRedemptionsCents)))
        for (b in taxBuckets) add(DocLine.Row("  ${taxCode(b.dsfinvkKey)} ${Money.formatPercent(b.percentBp)} brutto", Money.format(b.grossCents)))
        for (b in taxBuckets) add(DocLine.Row("  ${taxCode(b.dsfinvkKey)} Steuer", Money.format(b.taxCents)))
        add(DocLine.Row("Bar", Money.format(c.cashSalesCents)))
        add(DocLine.Row("Unbar", Money.format(c.cardSalesCents)))
        add(DocLine.Separator)
        add(DocLine.Row("Anfangsbestand", Money.format(c.openingCashCents)))
        add(DocLine.Row("+ Barumsatz", Money.format(c.cashSalesCents)))
        add(DocLine.Row("+/- Ein-/Auszahlungen", Money.format(c.cashMovementsCents)))
        add(DocLine.Row("= Soll-Bestand", Money.format(c.expectedCashCents), bold = true))
        if (final) {
            add(DocLine.Row("Ist-Bestand (gezählt)", Money.format(c.countedCashCents)))
            add(DocLine.Row("Differenz", Money.format(c.differenceCents), bold = true))
        }
        add(DocLine.Separator)
    }

    fun taxCode(dsfinvkKey: Int) = when (dsfinvkKey) { 1 -> "A"; 2 -> "B"; 3 -> "C"; 4 -> "D"; 5 -> "E"; 6 -> "F"; else -> "G" }

    fun toEscPos(lines: List<DocLine>, columns: Int, openDrawer: Boolean = false, drawerPin: Int = 2): ByteArray {
        val p = EscPos(columns).init()
        if (openDrawer) p.drawerKick(drawerPin)
        for (l in lines) when (l) {
            is DocLine.Text -> {
                p.align(if (l.center) EscPos.Align.CENTER else EscPos.Align.LEFT).bold(l.bold)
                if (l.big) p.size(doubleWidth = false, doubleHeight = true)
                EscPos.wrap(l.text, columns).forEach { p.line(it) }
                if (l.big) p.size(false, false)
                p.bold(false).align(EscPos.Align.LEFT)
            }
            is DocLine.Row -> p.bold(l.bold).row(l.left, l.right).bold(false)
            DocLine.Separator -> p.separator()
            is DocLine.Qr -> p.align(EscPos.Align.CENTER).qr(l.data, 3).line().align(EscPos.Align.LEFT)
        }
        return p.feed(4).cut().bytes()
    }

    fun toText(lines: List<DocLine>, columns: Int): String = buildString {
        for (l in lines) when (l) {
            is DocLine.Text -> EscPos.wrap(l.text, columns).forEach {
                appendLine(if (l.center) it.padStart((columns + it.length) / 2) else it)
            }
            is DocLine.Row -> {
                val gap = columns - l.left.length - l.right.length
                if (gap >= 1) appendLine(l.left + " ".repeat(gap) + l.right)
                else { appendLine(l.left); appendLine(l.right.padStart(columns)) }
            }
            DocLine.Separator -> appendLine("-".repeat(columns))
            is DocLine.Qr -> appendLine("[QR-Code]".padStart((columns + 9) / 2))
        }
    }
}
