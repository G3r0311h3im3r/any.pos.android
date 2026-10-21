package de.anypos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.anypos.data.Article
import de.anypos.data.BusinessCase
import de.anypos.data.PaymentType
import de.anypos.data.PosDatabase
import de.anypos.data.ReceiptState
import de.anypos.data.ReceiptType
import de.anypos.data.Role
import de.anypos.data.User
import de.anypos.domain.AuditLog
import de.anypos.domain.Cart
import de.anypos.domain.ClosingService
import de.anypos.domain.PaymentInput
import de.anypos.domain.PosException
import de.anypos.domain.PosService
import de.anypos.domain.SettingsRepository
import de.anypos.hardware.ReceiptDocument
import de.anypos.hardware.Company
import de.anypos.tse.NoTse
import de.anypos.tse.SimulatedTse
import de.anypos.tse.Tse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Integrationstest des Kassenablaufs gegen eine echte (In-Memory-)Room-Datenbank. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PosFlowTest {
    private lateinit var db: PosDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var pos: PosService
    private lateinit var closing: ClosingService
    private lateinit var audit: AuditLog
    private var tse: Tse = SimulatedTse()
    private lateinit var user: User
    private val coffee = Article(id = 1, sku = "1", name = "Kaffee", priceCents = 250, taxRateId = 1)
    private val book = Article(id = 2, sku = "2", name = "Buch", priceCents = 1490, taxRateId = 2)

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PosDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        audit = AuditLog(db.audit())
        val settings = SettingsRepository(db.settings(), scope)
        pos = PosService(db, { tse }, settings, audit)
        closing = ClosingService(db, pos, audit)
        db.masterData().insertTaxRates(PosDatabase.DEFAULT_TAX_RATES)
        db.masterData().upsertArticles(listOf(coffee, book))
        user = User(name = "Anna", pinHash = "x", role = Role.ADMIN).let { it.copy(id = db.users().upsert(it)) }
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    private val rates get() = PosDatabase.DEFAULT_TAX_RATES
    private fun cart() = Cart().add(coffee, rates[0]).add(coffee, rates[0]).add(book, rates[1])

    @Test fun saleIsSignedNumberedAndImmutable() = runBlocking {
        val open = pos.open(user)
        assertEquals(1L, open.number)
        assertNotNull(open.tseTxNumber)
        val cart = cart()
        val r = pos.complete(open, cart, listOf(PaymentInput(PaymentType.CASH, "Bar", 1990, 2000)))

        assertEquals(ReceiptState.FINISHED, r.receipt.state)
        assertEquals(1990L, r.receipt.totalCents)
        assertEquals("Beleg^5.00_14.90_0.00_0.00_0.00^19.90:Bar", r.receipt.tseProcessData)
        assertNotNull(r.receipt.tseSignature)
        assertEquals(2, r.lines.size)
        assertEquals(2000L, r.lines[0].quantityMilli)

        val text = ReceiptDocument.toText(ReceiptDocument.receipt(Company("Laden", "Str. 1", "12345", "Ort", "12/345/67890", "", "KASSE-01", "Danke"), r), 48)
        assertTrue(text, text.contains("SIMULATION – KEIN GÜLTIGER BELEG"))
        assertTrue(text, text.contains("Rückgeld"))
        assertTrue(text, text.contains("Signaturzähler"))
    }

    @Test fun wrongPaymentAmountIsRejected() = runBlocking {
        val open = pos.open(user)
        try {
            pos.complete(open, cart(), listOf(PaymentInput(PaymentType.CASH, "Bar", 100)))
            fail("Zahlbetrag hätte abgelehnt werden müssen")
        } catch (_: PosException) {}
    }

    @Test fun abortIsRecordedWithLines() = runBlocking {
        val open = pos.open(user)
        val r = pos.abort(open, cart())
        assertEquals(ReceiptState.ABORTED, r.receipt.state)
        assertEquals(ReceiptType.ABORT, r.receipt.type)
        assertEquals(2, r.lines.size)
        assertTrue(r.receipt.tseProcessData!!.startsWith("AVBelegabbruch^"))
    }

    @Test fun cancellationCreatesNegativeReceiptOnlyOnce() = runBlocking {
        val sale = pos.complete(pos.open(user), cart(), listOf(PaymentInput(PaymentType.CARD, "EC", 1990)))
        val storno = pos.cancel(sale.receipt.id, user)
        assertEquals(-1990L, storno.receipt.totalCents)
        assertEquals(sale.receipt.number, storno.receipt.referencesReceiptNumber)
        assertEquals(-1990L, storno.payments.single().amountCents)
        assertEquals("Beleg^-5.00_-14.90_0.00_0.00_0.00^-19.90:Unbar", storno.receipt.tseProcessData)
        try { pos.cancel(sale.receipt.id, user); fail() } catch (_: PosException) {}
        try { pos.cancel(storno.receipt.id, user); fail() } catch (_: PosException) {}
    }

    @Test fun tseFailureIsRecordedNotSilentlyIgnored() = runBlocking {
        tse = NoTse()
        val r = pos.complete(pos.open(user), cart(), listOf(PaymentInput(PaymentType.CASH, "Bar", 1990)))
        assertEquals(ReceiptState.FINISHED, r.receipt.state)
        assertNull(r.receipt.tseSignature)
        assertNotNull(r.receipt.tseError)
        val text = ReceiptDocument.toText(ReceiptDocument.receipt(Company("L", "", "", "", "", "", "K", ""), r), 48)
        assertTrue(text.contains("TSE AUSGEFALLEN"))
        assertTrue(db.audit().all().any { it.action == "TSE_START_FEHLER" })
    }

    @Test fun openReceiptsAreClosedAfterRestart() = runBlocking {
        pos.open(user)
        pos.recoverOpenReceipts()
        assertTrue(db.receipts().openReceipts().isEmpty())
    }

    @Test fun dailyClosingBooksDifferenceAndAssignsReceipts() = runBlocking {
        pos.cashMovement(user, BusinessCase.DEPOSIT, 10000, "Wechselgeld")
        pos.complete(pos.open(user), cart(), listOf(PaymentInput(PaymentType.CASH, "Bar", 1990)))
        pos.complete(pos.open(user), Cart().add(book, rates[1]), listOf(PaymentInput(PaymentType.CARD, "EC", 1490)))
        pos.cashMovement(user, BusinessCase.PRIVATE_OUT, -500, "Privat")

        val preview = closing.preview(user)
        assertEquals(10000L + 1990 - 500, preview.closing.expectedCashCents)
        assertEquals(1990L + 1490, preview.closing.totalCents)

        // 1,00 € zu wenig in der Kasse
        val report = closing.close(user, countedCashCents = 11390)
        assertEquals(1L, report.closing.number)
        assertEquals(0L, report.closing.differenceCents)
        assertEquals(11390L, report.closing.expectedCashCents)
        assertTrue(report.receipts.any { r -> r.lines.any { it.businessCase == BusinessCase.DIFFERENCE && it.totalCents == -100L } })
        assertTrue(db.receipts().unclosed().isEmpty())

        // Folgetag startet mit gezähltem Bestand
        assertEquals(11390L, closing.preview(user).closing.openingCashCents)
        assertNull(audit.verify())
    }
}
