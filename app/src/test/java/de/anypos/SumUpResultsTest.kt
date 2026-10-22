package de.anypos

import de.anypos.payment.CardPaymentResult
import de.anypos.payment.SumUpResults
import de.anypos.payment.SumUpResults.Raw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** SumUp-Rückgabe → Kassenergebnis: „abgelehnt“ nur, wenn sicher nichts abgebucht wurde. */
class SumUpResultsTest {
    private fun map(raw: Raw, expected: Long = 1490) = SumUpResults.map(raw, expected)

    @Test fun successWithMatchingAmount() {
        val r = map(Raw(1, txCode = "TX1", status = "SUCCESSFUL", cardType = "VISA", last4 = "4242", amount = 14.90))
        assertEquals(CardPaymentResult.Approved("TX1", "VISA", "4242"), r)
    }

    @Test fun successButDifferentAmountOrNoCodeIsUnknown() {
        assertTrue(map(Raw(1, txCode = "TX1", status = "SUCCESSFUL", amount = 15.90)) is CardPaymentResult.Unknown)
        assertTrue(map(Raw(1, txCode = null, status = "SUCCESSFUL")) is CardPaymentResult.Unknown)
        assertTrue(map(Raw(1, txCode = "TX1", status = "PENDING")) is CardPaymentResult.Unknown)
    }

    @Test fun failedCancelledPending() {
        assertEquals(CardPaymentResult.Cancelled, map(Raw(2, status = "CANCELLED")))
        assertTrue(map(Raw(2, status = "FAILED", message = "Karte abgelehnt")) is CardPaymentResult.Declined)
        assertTrue(map(Raw(2, status = "PENDING")) is CardPaymentResult.Unknown)
    }

    @Test fun anythingThatMayHaveChargedIsUnknown() {
        assertTrue(map(Raw(15)) is CardPaymentResult.Unknown)       // Status unbekannt
        assertTrue(map(Raw(9)) is CardPaymentResult.Unknown)        // Referenz schon vorhanden
        assertTrue(map(Raw(null)) is CardPaymentResult.Unknown)     // keine Rückgabe (z. B. App beendet)
    }

    @Test fun setupErrorsAreDeclined() {
        for (code in listOf(3, 4, 5, 6, 7, 8, 10, 11, 12, 13, 14)) {
            assertTrue("Code $code", map(Raw(code)) is CardPaymentResult.Declined)
        }
    }

    @Test fun totalWithoutTipAndTipSeparately() {
        assertEquals(BigDecimal("13.00"), SumUpResults.total(1400, 100))
        assertEquals(BigDecimal("1.00"), SumUpResults.euros(100))
    }
}
