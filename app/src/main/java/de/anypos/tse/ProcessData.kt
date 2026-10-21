package de.anypos.tse

import de.anypos.data.Payment
import de.anypos.data.PaymentType
import de.anypos.data.ReceiptLine
import de.anypos.domain.Money

/**
 * Erzeugt processType/processData für den Abschluss einer TSE-Transaktion
 * gemäß DSFinV-K Anhang "Kassenbeleg-V1":
 *
 *   <Vorgangstyp>^<Brutto 19%>_<7%>_<Durchschnittssatz Nr.3>_<Durchschnittssatz Nr.1>_<0%>^<Betrag>:<Bar|Unbar>[:<Währung>]_…
 *
 * Beispiel: "Beleg^75.33_7.99_0.00_0.00_0.00^10.00:Bar_73.32:Unbar"
 */
object ProcessData {
    const val PROCESS_TYPE = "Kassenbeleg-V1"

    fun build(type: String, lines: List<ReceiptLine>, payments: List<Payment>): String {
        val byKey = lines.groupBy { it.dsfinvkKey }.mapValues { (_, l) -> l.sumOf { it.totalCents } }
        // Schlüssel 5, 6, 7 (0 %, steuerfrei, nicht erhoben) werden im fünften Feld zusammengefasst
        val amounts = listOf(
            byKey[1] ?: 0, byKey[2] ?: 0, byKey[3] ?: 0, byKey[4] ?: 0,
            (byKey[5] ?: 0) + (byKey[6] ?: 0) + (byKey[7] ?: 0),
        ).joinToString("_") { Money.plain(it) }

        val pay = payments
            .groupBy { it.type }
            .map { (t, p) -> t to p.sumOf { it.amountCents } }
            .sortedBy { if (it.first == PaymentType.CASH) 0 else 1 }
            .joinToString("_") { (t, sum) -> "${Money.plain(sum)}:$t" }

        return "$type^$amounts^$pay"
    }
}
