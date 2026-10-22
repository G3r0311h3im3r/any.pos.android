package de.anypos

import de.anypos.MoneyAndTaxTest.Companion.line
import de.anypos.data.BusinessCase
import de.anypos.data.Payment
import de.anypos.data.PaymentType
import de.anypos.data.Receipt
import de.anypos.data.ReceiptState
import de.anypos.data.ReceiptWithDetails
import de.anypos.domain.AuditLog
import de.anypos.export.CashBook
import de.anypos.export.DatevAccounts
import de.anypos.export.DatevConfig
import de.anypos.export.DatevExport
import de.anypos.hardware.EscPos
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class CashBookAndDatevTest {
    private val day = LocalDate.of(2026, 10, 21)
    private val ts = day.atTime(10, 0).atZone(ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()

    private fun receipt(no: Long, lines: List<de.anypos.data.ReceiptLine>, payments: List<Pair<String, Long>>, state: String = ReceiptState.FINISHED) =
        ReceiptWithDetails(
            Receipt(id = no, number = no, state = state, createdAt = ts + no, finishedAt = ts + no, userId = 1, userName = "T", totalCents = lines.sumOf { it.totalCents }),
            lines,
            payments.map { Payment(receiptId = no, type = it.first, label = it.first, amountCents = it.second) },
        )

    private val data = listOf(
        receipt(1, listOf(line(5000, 0, 5, BusinessCase.DEPOSIT)), listOf(PaymentType.CASH to 5000)),                 // Wechselgeld
        receipt(2, listOf(line(1190, 1900, 1), line(107, 700, 2)), listOf(PaymentType.CASH to 1297)),                // bar
        receipt(3, listOf(line(2000, 1900, 1)), listOf(PaymentType.CARD to 2000)),                                      // Karte
        receipt(4, listOf(line(1000, 1900, 1), line(1000, 700, 2)), listOf(PaymentType.CASH to 500, PaymentType.CARD to 1500)), // gemischt
        receipt(5, listOf(line(-300, 0, 5, BusinessCase.PAYOUT)), listOf(PaymentType.CASH to -300)),                 // Porto
        receipt(6, listOf(line(999, 1900, 1)), emptyList(), state = ReceiptState.ABORTED),                              // Abbruch
    )

    @Test fun cashBookContainsOnlyCashWithRunningBalance() {
        val rows = CashBook.build(data, openingBalanceCents = 1000)
        assertEquals(listOf(5000L, 1190L, 107L, 250L, 250L, -300L), rows.map { it.amountCents })
        assertEquals(1000L + 5000 + 1297 + 500 - 300, rows.last().balanceCents)
    }

    @Test fun allocationKeepsTotalExact() {
        val r = CashBook.allocate(mapOf("a" to 333L, "b" to 333L, "c" to 334L), 100)
        assertEquals(100L, r.values.sum())
    }

    private val config = DatevConfig(
        advisorNumber = "12345", clientNumber = "678", fiscalYearStart = "0101", accountLength = 4, registerId = "KASSE-01",
        accounts = DatevAccounts("1000", "8400", "8300", "8200", "1360", "1360", "1800", "1890", "1360", "4900", "2309"),
        includeCardSales = true,
    )

    @Test fun datevBookingsBalanceWithSales() {
        val b = DatevExport.bookings(data, config)
        val cashRevenue = b.filter { it.account == "1000" && it.contra.startsWith("8") }.sumOf { it.amountCents }
        val cardRevenue = b.filter { it.account == "1360" && it.contra.startsWith("8") }.sumOf { it.amountCents }
        assertEquals(1297L + 500, cashRevenue)
        assertEquals(2000L + 1500, cardRevenue)
        val payout = b.single { it.contra == "4900" }
        assertEquals(300L, payout.amountCents)
        assertEquals(false, payout.debit)
    }

    @Test fun datevFileHasExtfHeader() {
        val text = DatevExport.render(DatevExport.bookings(data, config), config, day, day, LocalDateTime.of(2026, 10, 21, 12, 0))
        val lines = text.split("\r\n")
        assertTrue(lines[0].startsWith("\"EXTF\";700;21;\"Buchungsstapel\";13;20261021120000000;"))
        assertTrue(lines[0].contains(";12345;678;20260101;4;20261021;20261021;"))
        assertTrue(lines[1].startsWith("Umsatz (ohne Soll/Haben-Kz);Soll/Haben-Kennzeichen"))
        assertTrue(lines.any { it.startsWith("50,00;\"S\";\"EUR\";;;\"\";1000;1360;\"\";2110;") })
        assertTrue(DatevExport.validate(config).isEmpty())
        // Pflicht sind nur Konten; Kassendifferenz erst, wenn gebucht. Berater-/Mandantennummer sind nur Hinweise.
        assertTrue(DatevExport.validate(config.copy(accounts = config.accounts.copy(cash = ""))).isNotEmpty())
        assertTrue(DatevExport.validate(config.copy(accounts = config.accounts.copy(difference = ""))).isEmpty())
        val noNumbers = config.copy(advisorNumber = "", clientNumber = "")
        assertTrue(DatevExport.validate(noNumbers).isEmpty())
        assertTrue(DatevExport.warnings(noNumbers).any { it.contains("Beraternummer und Mandantennummer") })
        assertTrue(DatevExport.render(DatevExport.bookings(listOf(), noNumbers), noNumbers, java.time.LocalDate.of(2026, 10, 21),
            java.time.LocalDate.of(2026, 10, 21)).lines()[0].contains(";\"\";;;20260101;4;"))
    }

    @Test fun escPosEncodesGermanCharactersInPc858() {
        assertArrayEquals(byteArrayOf(0x8E.toByte(), 0x94.toByte(), 0xE1.toByte(), 0xD5.toByte(), 'A'.code.toByte()), EscPos.encode("Äöß€A"))
    }

    @Test fun escPosWrapsLongWords() {
        assertEquals(listOf("abc def", "ghijklmnop", "qr"), EscPos.wrap("abc def ghijklmnopqr", 10))
    }

    @Test fun auditHashChangesWithContent() {
        val a = AuditLog.hashOf("0", 1, "u", "A", "x")
        val b = AuditLog.hashOf("0", 1, "u", "A", "y")
        assertTrue(a != b && a.length == 64)
    }
}
