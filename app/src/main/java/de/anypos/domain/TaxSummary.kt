package de.anypos.domain

import de.anypos.data.ReceiptLine

/** Steueraufschlüsselung je Steuersatz, wie sie auf dem Beleg und im Abschluss erscheint. */
data class TaxBucket(
    val dsfinvkKey: Int,
    val percentBp: Int,
    val grossCents: Long,
    val taxCents: Long,
) {
    val netCents: Long get() = grossCents - taxCents
}

object TaxSummary {
    /**
     * Steuer wird je Steuersatz auf die Bruttosumme berechnet (nicht je Position),
     * damit Belegsumme und Steuerausweis rundungsfrei zusammenpassen.
     */
    fun of(lines: List<ReceiptLine>): List<TaxBucket> =
        lines.groupBy { it.dsfinvkKey to it.taxPercentBp }
            .map { (key, group) ->
                val gross = group.sumOf { it.totalCents }
                TaxBucket(key.first, key.second, gross, Money.taxFromGross(gross, key.second))
            }
            .sortedBy { it.dsfinvkKey }
}
