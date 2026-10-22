package de.anypos.domain

import androidx.room.withTransaction
import de.anypos.data.BusinessCase
import de.anypos.data.DailyClosing
import de.anypos.data.PaymentType
import de.anypos.data.PosDatabase
import de.anypos.data.ReceiptState
import de.anypos.data.ReceiptWithDetails
import de.anypos.data.User
import java.time.Instant
import java.time.ZoneId

data class ClosingReport(val closing: DailyClosing, val taxBuckets: List<TaxBucket>, val receipts: List<ReceiptWithDetails>)

/** X-Bon (Zwischenstand) und Z-Bon (Tagesabschluss mit Kassensturz). */
class ClosingService(
    private val db: PosDatabase,
    private val pos: PosService,
    private val audit: AuditLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Berechnet den aktuellen Stand aller noch nicht abgeschlossenen Belege. */
    suspend fun preview(user: User, countedCashCents: Long = 0): ClosingReport {
        val unclosed = db.receipts().unclosed().map { pos.load(it.id) }
        return summarize(unclosed, user.name, number = db.closings().maxNumber() + 1, countedCashCents)
    }

    /**
     * Führt den Tagesabschluss durch. Eine Kassendifferenz wird vorher als eigener Geschäftsvorfall
     * gebucht, damit Kassenbuch und Zählbestand übereinstimmen.
     */
    suspend fun close(user: User, countedCashCents: Long): ClosingReport {
        if (db.receipts().openReceipts().isNotEmpty()) throw PosException("Es gibt noch einen offenen Vorgang")
        // Keine offene TSE-Transaktion über den Abschluss hinweg: verwaiste vorher als Belegabbruch abschließen
        pos.reconcile()
        val before = preview(user, countedCashCents)
        val diff = before.closing.differenceCents
        if (diff != 0L) {
            pos.cashMovement(user, BusinessCase.DIFFERENCE, diff, "Kassendifferenz Tagesabschluss")
        }
        val report = preview(user, countedCashCents)
        val id = db.withTransaction {
            val id = db.closings().insert(report.closing)
            db.receipts().assignClosing(id)
            id
        }
        audit.log(user.name, "TAGESABSCHLUSS", "Z-Nr. ${report.closing.number}, Umsatz ${Money.format(report.closing.totalCents)}, Differenz ${Money.format(diff)}")
        return report.copy(closing = report.closing.copy(id = id))
    }

    private suspend fun summarize(receipts: List<ReceiptWithDetails>, userName: String, number: Long, counted: Long): ClosingReport {
        val finished = receipts.filter { it.receipt.state == ReceiptState.FINISHED && !it.receipt.training }
        val allLines = finished.flatMap { it.lines }
        val salesLines = allLines.filter { it.businessCase == BusinessCase.SALE }
        // Alle Belege außer reinen Kassenbewegungen (Waren, Gutscheine, Retouren, Stornos)
        val salesReceipts = finished.filter { r -> r.lines.any { it.businessCase !in BusinessCase.cashMovements } }
        val cashSales = salesReceipts.sumOf { r -> r.payments.filter { it.type == PaymentType.CASH }.sumOf { it.amountCents } }
        val cardSales = salesReceipts.sumOf { r -> r.payments.filter { it.type != PaymentType.CASH }.sumOf { it.amountCents } }
        val movements = allLines.filter { it.businessCase in BusinessCase.cashMovements }.sumOf { it.totalCents }
        val opening = db.closings().last()?.countedCashCents ?: 0
        val expected = opening + cashSales + movements
        val now = clock()
        val closing = DailyClosing(
            number = number,
            createdAt = now,
            businessDate = Instant.ofEpochMilli(now).atZone(ZoneId.of("Europe/Berlin")).toLocalDate().toString(),
            userName = userName,
            firstReceiptNumber = receipts.minOfOrNull { it.receipt.number },
            lastReceiptNumber = receipts.maxOfOrNull { it.receipt.number },
            receiptCount = receipts.size,
            totalCents = salesLines.sumOf { it.totalCents },
            cashSalesCents = cashSales,
            cardSalesCents = cardSales,
            cashMovementsCents = movements,
            openingCashCents = opening,
            expectedCashCents = expected,
            countedCashCents = counted,
            differenceCents = counted - expected,
            voucherSalesCents = allLines.filter { it.businessCase in BusinessCase.voucherSales }.sumOf { it.totalCents },
            voucherRedemptionsCents = allLines.filter { it.businessCase in BusinessCase.voucherRedemptions }.sumOf { it.totalCents },
        )
        return ClosingReport(closing, TaxSummary.of(allLines.filter { it.businessCase in BusinessCase.taxable }), receipts)
    }
}
