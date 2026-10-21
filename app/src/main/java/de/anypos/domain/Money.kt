package de.anypos.domain

import java.math.BigDecimal
import java.math.RoundingMode

/** Alle Beträge sind Long in Cent. Gerechnet wird ausschließlich ganzzahlig oder mit BigDecimal. */
object Money {

    /** 1234 -> "12,34" */
    fun format(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val abs = kotlin.math.abs(cents)
        val euros = abs / 100
        val rest = abs % 100
        val grouped = euros.toString().reversed().chunked(3).joinToString(".").reversed()
        return "$sign$grouped,${rest.toString().padStart(2, '0')}"
    }

    fun formatEur(cents: Long): String = "${format(cents)} €"

    /** 1234 -> "12.34" (maschinenlesbar, für TSE/DSFinV-K/DATEV-Punktformat). */
    fun plain(cents: Long): String =
        BigDecimal.valueOf(cents).movePointLeft(2).setScale(2).toPlainString()

    /** 1234 -> "12,34" ohne Tausenderpunkte (DATEV). */
    fun plainComma(cents: Long): String = plain(cents).replace('.', ',')

    /**
     * Liest Beträge wie "12,50", "12.50", "1.234,50", "1,234.50", "12" in Cent.
     * Gibt null zurück, wenn der Text keine gültige Zahl ist.
     */
    fun parse(text: String): Long? = parseDecimal(text)?.movePointRight(2)?.setScale(0, RoundingMode.HALF_UP)?.toLong()

    fun parseDecimal(raw: String): BigDecimal? {
        var s = raw.trim().replace("€", "").replace("EUR", "").replace(" ", "").replace(" ", "")
        if (s.isEmpty()) return null
        val lastComma = s.lastIndexOf(',')
        val lastDot = s.lastIndexOf('.')
        s = when {
            lastComma >= 0 && lastDot >= 0 ->
                if (lastComma > lastDot) s.replace(".", "").replace(',', '.') else s.replace(",", "")
            lastComma >= 0 -> {
                // Nur Komma: Dezimaltrenner, sofern nicht eindeutig Tausendergruppierung ("1,234,567")
                if (s.count { it == ',' } > 1) s.replace(",", "") else s.replace(',', '.')
            }
            lastDot >= 0 && s.count { it == '.' } > 1 -> s.replace(".", "")
            else -> s
        }
        return s.toBigDecimalOrNull()
    }

    /** Menge (Tausendstel) * Einzelpreis, kaufmännisch gerundet. */
    fun lineTotal(unitPriceCents: Long, quantityMilli: Long): Long =
        BigDecimal.valueOf(unitPriceCents).multiply(BigDecimal.valueOf(quantityMilli))
            .divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_UP).toLong()

    /** Anteil in Basispunkten (1000 = 10 %), kaufmännisch gerundet. */
    fun percentOf(cents: Long, percentBp: Int): Long =
        BigDecimal.valueOf(cents).multiply(BigDecimal.valueOf(percentBp.toLong()))
            .divide(BigDecimal.valueOf(10000), 0, RoundingMode.HALF_UP).toLong()

    /** Steueranteil aus einem Bruttobetrag; [percentBp] 1900 = 19 %. */
    fun taxFromGross(grossCents: Long, percentBp: Int): Long {
        if (percentBp == 0) return 0
        return BigDecimal.valueOf(grossCents).multiply(BigDecimal.valueOf(percentBp.toLong()))
            .divide(BigDecimal.valueOf(10000L + percentBp), 0, RoundingMode.HALF_UP).toLong()
    }

    /** Netto -> Brutto (für Importe mit Nettopreisen). */
    fun grossFromNet(net: BigDecimal, percentBp: Int): Long =
        net.multiply(BigDecimal.valueOf(10000L + percentBp)).divide(BigDecimal.valueOf(10000))
            .movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()

    fun formatPercent(bp: Int): String {
        val whole = bp / 100
        val frac = bp % 100
        return if (frac == 0) "$whole %" else "$whole,${frac.toString().padStart(2, '0').trimEnd('0')} %"
    }

    fun formatQuantity(quantityMilli: Long): String {
        if (quantityMilli % 1000 == 0L) return (quantityMilli / 1000).toString()
        return BigDecimal.valueOf(quantityMilli).movePointLeft(3).stripTrailingZeros().toPlainString().replace('.', ',')
    }
}
