package de.anypos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.anypos.data.Article
import de.anypos.data.CardAttemptState
import de.anypos.data.PosDatabase
import de.anypos.data.Role
import de.anypos.data.User
import de.anypos.domain.AuditLog
import de.anypos.domain.Cart
import de.anypos.domain.PosService
import de.anypos.domain.SettingsRepository
import de.anypos.hardware.Company
import de.anypos.hardware.ReceiptDocument
import de.anypos.payment.CardPaymentResult
import de.anypos.payment.CardPayments
import de.anypos.payment.CardTerminals
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

/** Kartenterminal: Versuche vor dem Start gespeichert, Referenz nie doppelt, Unbekanntes wird geklärt. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CardPaymentsTest {
    private lateinit var db: PosDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var pos: PosService
    private lateinit var cards: CardPayments
    private lateinit var user: User
    private val vat19 = PosDatabase.DEFAULT_TAX_RATES[0]
    private val meal = Article(id = 1, sku = "1", name = "Essen", priceCents = 1235, taxRateId = 1)
    private val company = Company("Laden", "Str. 1", "12345", "Ort", "12/345/67890", "", "KASSE-01", "Danke")

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PosDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audit = AuditLog(db.audit())
        val tse = SimulatedTse()
        pos = PosService(db, { tse }, SettingsRepository(db.settings(), scope), audit)
        cards = CardPayments(db, audit, { "KASSE-01" })
        db.masterData().insertTaxRates(PosDatabase.DEFAULT_TAX_RATES)
        db.masterData().upsertArticles(listOf(meal))
        user = User(name = "Anna", pinHash = "x", role = Role.ADMIN).let { it.copy(id = db.users().upsert(it)) }
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    @Test fun approvedPaymentCarriesTerminalDataToReceipt() = runBlocking {
        val open = pos.open(user)
        val req = cards.begin(open, 1300, 65, CardTerminals.SIMULATED, user.name)
        assertEquals("KASSE-01-${open.number}-1", req.reference)
        assertEquals(CardAttemptState.PENDING, db.cardAttempts().get(req.attemptId)!!.state)  // vor dem Start gespeichert

        val a = cards.record(req, CardPaymentResult.Approved("TX123", "VISA", "4242"))
        assertEquals(CardAttemptState.APPROVED, a.state)
        val cart = Cart().add(meal, vat19).withTip(65, de.anypos.data.BusinessCase.TIP_EMPLOYEE, PosDatabase.DEFAULT_TAX_RATES.first { it.dsfinvkKey == 5 })
        val r = pos.complete(open, cart, listOf(cards.payment(a)))
        val p = r.payments.single()
        assertEquals("Karte (Simulation)", p.label)
        assertEquals("TX123", p.providerTxCode)
        assertEquals("4242", p.cardLast4)
        val text = ReceiptDocument.toText(ReceiptDocument.receipt(company, r), 48)
        assertTrue(text.contains("VISA ****4242") && text.contains("TX123"))
    }

    @Test fun referenceIsNeverReusedAfterDeclineOrCancel() = runBlocking {
        val open = pos.open(user)
        val r1 = cards.begin(open, 1235, 0, CardTerminals.SIMULATED, user.name)
        cards.record(r1, CardPaymentResult.Declined("abgelehnt"))
        val r2 = cards.begin(open, 1235, 0, CardTerminals.SIMULATED, user.name)
        cards.record(r2, CardPaymentResult.Cancelled)
        val r3 = cards.begin(open, 1235, 0, CardTerminals.SIMULATED, user.name)
        assertEquals(listOf("-1", "-2", "-3"), listOf(r1, r2, r3).map { it.reference.takeLast(2) })
    }

    @Test fun unknownBlocksNewAttemptUntilResolved() = runBlocking {
        val open = pos.open(user)
        val r1 = cards.begin(open, 1235, 0, CardTerminals.SIMULATED, user.name)
        cards.record(r1, CardPaymentResult.Unknown("Bluetooth weg"))
        try { cards.begin(open, 1235, 0, CardTerminals.SIMULATED, user.name); fail("darf nicht erneut abbuchen") } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ungeklärt"))
        }
        val resolved = cards.resolve(r1.attemptId, paid = true, userName = "Anna", txCode = "TX9", note = "laut SumUp-App abgebucht")
        assertEquals(CardAttemptState.RESOLVED_PAID, resolved.state)
        assertEquals("TX9", cards.payment(resolved).providerTxCode)
        try { cards.resolve(r1.attemptId, paid = false, userName = "Anna", txCode = null, note = "x"); fail() } catch (_: IllegalArgumentException) {}
        assertTrue(cards.unresolved().isEmpty())
    }

    @Test fun restartMarksRunningAttemptUnknown() = runBlocking {
        val open = pos.open(user)
        val req = cards.begin(open, 1235, 0, CardTerminals.SIMULATED, user.name)
        cards.recover()                           // App-Neustart mitten in der Zahlung
        pos.recoverOpenReceipts()                 // Vorgang wird als Abbruch abgeschlossen
        val a = cards.unresolved().single()
        assertEquals(req.attemptId, a.id)
        assertEquals(CardAttemptState.UNKNOWN, a.state)
        cards.resolve(a.id, paid = false, userName = "Anna", txCode = null, note = "nicht abgebucht")
        assertTrue(cards.unresolved().isEmpty())
    }

    @Test fun minimumAmount() = runBlocking {
        val open = pos.open(user)
        try { cards.begin(open, 99, 0, CardTerminals.SIMULATED, user.name); fail() } catch (_: IllegalArgumentException) {}
    }
}
