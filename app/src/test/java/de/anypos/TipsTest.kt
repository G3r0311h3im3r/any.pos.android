package de.anypos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.anypos.data.Article
import de.anypos.data.BusinessCase
import de.anypos.data.PaymentType
import de.anypos.data.PosDatabase
import de.anypos.data.Role
import de.anypos.data.User
import de.anypos.domain.AuditLog
import de.anypos.domain.Cart
import de.anypos.domain.ClosingService
import de.anypos.domain.PaymentInput
import de.anypos.domain.PosService
import de.anypos.domain.SettingsRepository
import de.anypos.domain.TaxSummary
import de.anypos.domain.TipTotals
import de.anypos.domain.Tips
import de.anypos.export.CashBook
import de.anypos.export.DatevAccounts
import de.anypos.export.DatevConfig
import de.anypos.export.DatevExport
import de.anypos.export.DsfinvkExport
import de.anypos.export.DsfinvkSchema
import de.anypos.export.loadDsfinvkInput
import de.anypos.hardware.Company
import de.anypos.hardware.ReceiptDocument
import de.anypos.importer.Csv
import de.anypos.tse.SimulatedTse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Trinkgeld: Stufen, Warenkorb, Bon, Z-Bon, Auszahlung, Kassenbuch, DATEV und DSFinV-K. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TipsTest {
    private lateinit var db: PosDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var pos: PosService
    private lateinit var closing: ClosingService
    private lateinit var user: User
    private val rates = PosDatabase.DEFAULT_TAX_RATES
    private val vat19 = rates[0]
    private val noVat = rates.first { it.dsfinvkKey == 5 }
    private val meal = Article(id = 1, sku = "1", name = "Essen", priceCents = 1235, taxRateId = 1)
    private val company = Company("Laden", "Str. 1", "12345", "Ort", "12/345/67890", "", "KASSE-01", "Danke")

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PosDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audit = AuditLog(db.audit())
        val tse = SimulatedTse()
        pos = PosService(db, { tse }, SettingsRepository(db.settings(), scope), audit)
        closing = ClosingService(db, pos, audit)
        db.masterData().insertTaxRates(rates)
        db.masterData().upsertArticles(listOf(meal))
        user = User(name = "Anna", pinHash = "x", role = Role.ADMIN).let { it.copy(id = db.users().upsert(it)) }
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    private suspend fun sell(cart: Cart, vararg payments: PaymentInput) = pos.complete(pos.open(user), cart, payments.toList())
    private fun cash(c: Long, given: Long? = null) = PaymentInput(PaymentType.CASH, "Bar", c, given)
    private fun card(c: Long) = PaymentInput(PaymentType.CARD, "EC-Karte", c)
    private fun employeeTip(cart: Cart, cents: Long) = cart.withTip(cents, BusinessCase.TIP_EMPLOYEE, noVat)

    @Test fun optionsPercentAndRoundUp() {
        val o = Tips.options(1235, listOf(5, 10), roundUp = true)
        assertEquals(listOf("none", "p5", "p10", "round"), o.map { it.id })
        assertEquals(listOf(0L, 62L, 124L, 65L), o.map { it.cents })     // 61,75 → 62; 123,5 → 124; auf 13,00
        assertEquals("Aufrunden auf 13,00 €", o[3].label)
        assertEquals(100L, Tips.roundUpCents(1300))                      // glatter Betrag: + 1 €
        assertEquals(listOf("none"), Tips.options(0, listOf(5), true).map { it.id })
        assertEquals(listOf(5, 10), Tips.parsePercents(" 5, 10;x"))
    }

    @Test fun cartHoldsAtMostOneTipAndRejectsReturns() {
        val c = Cart().add(meal, vat19)
        val t = employeeTip(employeeTip(c, 100), 200)
        assertEquals(200L, t.tipCents)
        assertEquals(1235L, t.totalWithoutTipCents)
        assertEquals(1, t.lines.count { it.businessCase == BusinessCase.TIP_EMPLOYEE })
        assertEquals(c.lines, employeeTip(t, 0).lines)
        assertEquals(c.lines, t.withoutTip().lines)
        try { employeeTip(Cart(), 100); fail() } catch (_: IllegalArgumentException) {}
        try { employeeTip(c, -1); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun employeeTipIsSeparateWithoutVatOnReceiptAndClosing() = runBlocking {
        val r = sell(employeeTip(Cart().add(meal, vat19), 165), card(1400))
        val tip = r.lines.single { it.businessCase == BusinessCase.TIP_EMPLOYEE }
        assertEquals(5, tip.dsfinvkKey)
        assertEquals(0, tip.taxPercentBp)
        assertEquals(1400L, r.receipt.totalCents)

        val text = ReceiptDocument.toText(ReceiptDocument.receipt(company, r), 48)
        assertTrue(text.contains("Trinkgeld (Mitarbeiter, ohne USt)"))
        // USt nur auf das Essen
        assertEquals(1235L, TaxSummary.of(r.lines).single { it.dsfinvkKey == 1 }.grossCents)

        val report = closing.preview(user)
        assertEquals(1235L, report.closing.totalCents)                 // Warenumsatz ohne Trinkgeld
        assertEquals(TipTotals(employeeCents = 165, employeeCardCents = 165), report.tips)
        val z = ReceiptDocument.toText(ReceiptDocument.closing(company, report.closing, false, report.taxBuckets, report.tips), 48)
        assertTrue(z.contains("Trinkgeld Mitarbeiter (kein Umsatz)"))
    }

    @Test fun payoutReducesBalanceAndIsInCashBook() = runBlocking {
        sell(employeeTip(Cart().add(meal, vat19), 165), card(1400))
        sell(employeeTip(Cart().add(meal, vat19), 65), cash(1300, 2000))
        assertEquals(230L, pos.tipBalance())
        val payout = pos.tipPayout(user, 230, "Max")
        assertEquals(BusinessCase.TIP_EMPLOYEE, payout.lines.single().businessCase)
        assertEquals(-230L, payout.payments.single().amountCents)
        assertTrue(payout.receipt.tseSignature != null)                 // mit TSE abgesichert
        assertEquals(0L, pos.tipBalance())

        val report = closing.preview(user)
        assertEquals(TipTotals(employeeCents = 230, employeeCardCents = 165, employeePaidOutCents = 230), report.tips)
        // Soll-Bestand: 13,00 bar (inkl. 0,65 Trinkgeld) − 2,30 Auszahlung
        assertEquals(1300L - 230L, report.closing.expectedCashCents)

        val book = CashBook.build(report.receipts, 0)
        assertTrue(book.any { it.text == "Trinkgeld (Mitarbeiter, ohne USt)" && it.amountCents == 65L })
        assertTrue(book.any { it.amountCents == -230L })
        assertEquals(1070L, book.last().balanceCents)
    }

    @Test fun cancellationReversesTip() = runBlocking {
        val r = sell(employeeTip(Cart().add(meal, vat19), 165), card(1400))
        pos.cancel(r.receipt.id, user)
        assertEquals(0L, pos.tipBalance())
        assertEquals(TipTotals(), closing.preview(user).tips)
    }

    @Test fun ownerTipIsRevenueWithVat() = runBlocking {
        val r = sell(Cart().add(meal, vat19).withTip(65, BusinessCase.TIP_OWNER, vat19), cash(1300))
        val report = closing.preview(user)
        assertEquals(65L, report.tips.ownerCents)
        assertEquals(1300L, TaxSummary.of(r.lines).single { it.dsfinvkKey == 1 }.grossCents) // Trinkgeld mit 19 % versteuert
        assertEquals(1300L, report.taxBuckets.single { it.dsfinvkKey == 1 }.grossCents)
    }

    @Test fun exportsUseTipBusinessCaseAndAccount() = runBlocking {
        sell(employeeTip(Cart().add(meal, vat19), 165), card(1400))
        sell(employeeTip(Cart().add(meal, vat19), 65), cash(1300))
        pos.tipPayout(user, 230, "Max")
        closing.close(user, countedCashCents = 1070)

        val schema = File("src/main/assets/dsfinvk/index.xml").inputStream().use { DsfinvkSchema.parse(it) }
        val input = loadDsfinvkInput(db, pos, company, "KASSE-01", "Test", "0.3.0", 0, Long.MAX_VALUE)
        val files = DsfinvkExport.build(input, schema).files
        fun rows(f: String) = Csv.parse(files.getValue(f), ';').let { p -> p.drop(1).map { r -> p[0].zip(r).toMap() } }
        val tipLines = rows("lines.csv").filter { it["GV_TYP"] == "TrinkgeldAN" }
        assertEquals(3, tipLines.size)                                 // zwei erhalten, eine Auszahlung
        val gv = rows("businesscases.csv").single { it["GV_TYP"] == "TrinkgeldAN" }
        assertEquals("5", gv["UST_SCHLUESSEL"])
        assertEquals("0,00", gv["Z_UMS_BRUTTO"])                       // erhalten 2,30 − ausgezahlt 2,30

        val receipts = input.closings.flatMap { it.receipts }
        val accounts = DatevAccounts("1000", "8400", "8300", "8200", "1360", "1360", "1800", "1890", "1360", "4900", "2309")
        val cfg = DatevConfig("1", "2", "0101", 4, "KASSE-01", accounts, includeCardSales = true)
        try { DatevExport.bookings(receipts, cfg); fail() } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Trinkgeld"))
        }
        val b = DatevExport.bookings(receipts, cfg.copy(accounts = accounts.copy(tip = "1755")))
        val tip = b.filter { it.contra == "1755" }
        // Karte: 1,65 Kartenkonto an Trinkgeld; bar: 0,65 − 2,30 = −1,65 → Trinkgeld an Kasse
        assertEquals(165L, tip.single { it.account == "1360" }.amountCents)
        assertEquals(165L, tip.single { it.account == "1000" }.amountCents)
        assertTrue(b.none { it.contra == "8400" && it.amountCents == 2635L }) // kein Trinkgeld im Erlös
    }
}
