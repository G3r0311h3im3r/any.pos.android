package de.anypos

import de.anypos.data.Article
import de.anypos.data.Payment
import de.anypos.data.PaymentType
import de.anypos.data.PosDatabase
import de.anypos.data.ReceiptLine
import de.anypos.domain.Cart
import de.anypos.domain.Money
import de.anypos.domain.TaxSummary
import de.anypos.tse.ProcessData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyAndTaxTest {
    private val vat19 = PosDatabase.DEFAULT_TAX_RATES[0]
    private val vat7 = PosDatabase.DEFAULT_TAX_RATES[1]

    @Test fun formatsGermanAmounts() {
        assertEquals("1.234,56", Money.format(123456))
        assertEquals("-0,05", Money.format(-5))
        assertEquals("12.50", Money.plain(1250))
        assertEquals("-3.00", Money.plain(-300))
    }

    @Test fun parsesAllCommonNotations() {
        assertEquals(1250L, Money.parse("12,50"))
        assertEquals(1250L, Money.parse("12.50"))
        assertEquals(123450L, Money.parse("1.234,50"))
        assertEquals(123450L, Money.parse("1,234.50"))
        assertEquals(1200L, Money.parse("12 €"))
        assertEquals(123456700L, Money.parse("1.234.567"))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse(""))
    }

    @Test fun taxIsExtractedFromGross() {
        assertEquals(190L, Money.taxFromGross(1190, 1900))
        assertEquals(7L, Money.taxFromGross(107, 700))
        assertEquals(0L, Money.taxFromGross(500, 0))
        // 2,50 brutto 19 %: 0,399… -> 0,40
        assertEquals(40L, Money.taxFromGross(250, 1900))
    }

    @Test fun grossFromNetRoundsCommercially() {
        assertEquals(1190L, Money.grossFromNet(java.math.BigDecimal("10.00"), 1900))
        assertEquals(1251L, Money.grossFromNet(java.math.BigDecimal("10.5126"), 1900)) // 12,50999 -> 12,51
    }

    @Test fun quantityTimesPrice() {
        assertEquals(375L, Money.lineTotal(250, 1500))
        assertEquals(-250L, Money.lineTotal(250, -1000))
        assertEquals(1L, Money.lineTotal(1, 500)) // 0,5 Cent -> aufrunden
    }

    @Test fun cartMergesSameArticleAndAppliesDiscount() {
        val coffee = Article(id = 1, sku = "1", name = "Kaffee", priceCents = 250, taxRateId = 1)
        var cart = Cart().add(coffee, vat19).add(coffee, vat19)
        assertEquals(1, cart.lines.size)
        assertEquals(500L, cart.totalCents)
        cart = cart.setDiscountPercent(cart.lines[0].key, 1000)
        assertEquals(450L, cart.totalCents)
        cart = cart.setQuantity(cart.lines[0].key, 0)
        assertEquals(0, cart.lines.size)
    }

    @Test fun taxSummaryPerRate() {
        val lines = listOf(line(1190, vat19.percentBp, 1), line(1190, vat19.percentBp, 1), line(107, vat7.percentBp, 2))
        val s = TaxSummary.of(lines)
        assertEquals(2, s.size)
        assertEquals(2380L, s[0].grossCents)
        assertEquals(380L, s[0].taxCents)
        assertEquals(2000L, s[0].netCents)
        assertEquals(7L, s[1].taxCents)
    }

    @Test fun processDataFollowsKassenbelegV1() {
        val lines = listOf(line(7533, 1900, 1), line(799, 700, 2))
        val payments = listOf(
            Payment(receiptId = 1, type = PaymentType.CARD, label = "EC", amountCents = 7332),
            Payment(receiptId = 1, type = PaymentType.CASH, label = "Bar", amountCents = 1000),
        )
        assertEquals("Beleg^75.33_7.99_0.00_0.00_0.00^10.00:Bar_73.32:Unbar", ProcessData.build("Beleg", lines, payments))
    }

    @Test fun processDataForAbortHasNoPayments() {
        assertEquals("AVBelegabbruch^2.50_0.00_0.00_0.00_0.00^", ProcessData.build("AVBelegabbruch", listOf(line(250, 1900, 1)), emptyList()))
    }

    @Test fun processDataZeroRatesShareFifthField() {
        assertEquals("Beleg^0.00_0.00_0.00_0.00_30.00^30.00:Bar",
            ProcessData.build("Beleg", listOf(line(1000, 0, 5), line(2000, 0, 6)), listOf(Payment(receiptId = 1, type = PaymentType.CASH, label = "Bar", amountCents = 3000))))
    }

    companion object {
        fun line(total: Long, bp: Int, key: Int, businessCase: String = "Umsatz") = ReceiptLine(
            receiptId = 1, position = 1, articleId = null, sku = "x", name = "x", quantityMilli = 1000,
            unitPriceCents = total, discountCents = 0, totalCents = total, taxRateId = key.toLong(),
            taxPercentBp = bp, dsfinvkKey = key, businessCase = businessCase,
        )
    }
}
