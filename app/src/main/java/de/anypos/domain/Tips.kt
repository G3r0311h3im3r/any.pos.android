package de.anypos.domain

import de.anypos.data.BusinessCase
import de.anypos.data.PaymentType
import de.anypos.data.ReceiptState
import de.anypos.data.ReceiptWithDetails
import de.anypos.export.CashBook
import java.math.BigDecimal
import java.math.RoundingMode

/** Eine Auswahl der Trinkgeldabfrage; [id] wird auch vom Kundendisplay zurückgemeldet. */
data class TipOption(val id: String, val label: String, val cents: Long)

/**
 * Trinkgeldabfrage: Prozentstufen auf den zu zahlenden Betrag (ohne Trinkgeld) und „Aufrunden“ auf den nächsten
 * vollen Euro (bei glattem Betrag um 1 €). „Kein Trinkgeld“ steht immer als erste Auswahl.
 */
object Tips {
    const val NONE = "none"
    const val ROUND = "round"

    fun options(baseCents: Long, percents: List<Int>, roundUp: Boolean): List<TipOption> = buildList {
        add(TipOption(NONE, "Kein Trinkgeld", 0))
        if (baseCents <= 0) return@buildList
        for (p in percents.filter { it in 1..100 }.distinct()) add(TipOption("p$p", "$p %", percentOf(baseCents, p)))
        if (roundUp) {
            val cents = roundUpCents(baseCents)
            add(TipOption(ROUND, "Aufrunden auf ${Money.formatEur(baseCents + cents)}", cents))
        }
    }

    fun percentOf(baseCents: Long, percent: Int): Long =
        BigDecimal.valueOf(baseCents).multiply(BigDecimal.valueOf(percent.toLong()))
            .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).toLong()

    fun roundUpCents(baseCents: Long): Long = 100 - baseCents % 100

    /** Prozentstufen aus der Einstellung („5,10“); ungültige Einträge werden übergangen. */
    fun parsePercents(setting: String): List<Int> = setting.split(',', ';', ' ').mapNotNull { it.trim().toIntOrNull() }
}

/** Trinkgeld im Zeitraum (Z-Bon): erhalten (davon unbar) und an Mitarbeitende ausgezahlt (positiv). */
data class TipTotals(
    val employeeCents: Long = 0,
    val employeeCardCents: Long = 0,
    val employeePaidOutCents: Long = 0,
    val ownerCents: Long = 0,
) {
    val isEmpty get() = employeeCents == 0L && employeePaidOutCents == 0L && ownerCents == 0L

    companion object {
        fun of(receipts: List<ReceiptWithDetails>): TipTotals {
            var employee = 0L; var card = 0L; var paidOut = 0L; var owner = 0L
            for (r in receipts.filter { it.receipt.state == ReceiptState.FINISHED && !it.receipt.training }) {
                val tips = r.lines.filter { it.businessCase == BusinessCase.TIP_EMPLOYEE }.sumOf { it.totalCents }
                owner += r.lines.filter { it.businessCase == BusinessCase.TIP_OWNER }.sumOf { it.totalCents }
                if (tips == 0L) continue
                if (r.lines.all { it.businessCase == BusinessCase.TIP_EMPLOYEE }) {
                    paidOut -= tips // Auszahlungsbeleg (negativ); Storno einer Auszahlung zählt dagegen
                    continue
                }
                employee += tips
                val rest = r.lines.filter { it.businessCase != BusinessCase.TIP_EMPLOYEE }.sumOf { it.totalCents }
                val cardPaid = r.payments.filter { it.type != PaymentType.CASH }.sumOf { it.amountCents }
                card += CashBook.allocate(mapOf("tip" to tips, "rest" to rest), cardPaid).getValue("tip")
            }
            return TipTotals(employee, card, paidOut, owner)
        }
    }
}
