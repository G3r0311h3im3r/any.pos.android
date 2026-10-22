package de.anypos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.anypos.data.Article
import de.anypos.data.PaymentType
import de.anypos.data.PosDatabase
import de.anypos.data.ReceiptState
import de.anypos.data.ReceiptType
import de.anypos.data.Role
import de.anypos.data.User
import de.anypos.domain.AuditLog
import de.anypos.domain.Cart
import de.anypos.domain.ClosingService
import de.anypos.domain.Key
import de.anypos.domain.PaymentInput
import de.anypos.domain.PosService
import de.anypos.domain.SettingsRepository
import de.anypos.tse.EpsonTse
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Kassenablauf mit EpsonTse gegen die Mock-TSE (In-Memory-Datenbank, keine echte TSE). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PosEpsonFlowTest {
    private lateinit var db: PosDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var settings: SettingsRepository
    private lateinit var audit: AuditLog
    private lateinit var fixture: MockTseFixture
    private lateinit var epson: EpsonTse
    private lateinit var pos: PosService
    private lateinit var user: User
    private val coffee = Article(id = 1, sku = "1", name = "Kaffee", priceCents = 250, taxRateId = 1)

    @Before fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PosDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        audit = AuditLog(db.audit())
        settings = SettingsRepository(db.settings(), scope)
        settings.load()
        fixture = MockTseFixture().also { it.writeEnabled = true }
        epson = fixture.epson()
        pos = PosService(db, { epson }, settings, audit, scope = scope)
        db.masterData().insertTaxRates(PosDatabase.DEFAULT_TAX_RATES)
        db.masterData().upsertArticles(listOf(coffee))
        user = User(name = "Anna", pinHash = "x", role = Role.ADMIN).let { it.copy(id = db.users().upsert(it)) }
    }

    @After fun tearDown() { db.close(); scope.cancel(); fixture.close() }

    private fun cart() = Cart().add(coffee, PosDatabase.DEFAULT_TAX_RATES[0])

    @Test fun saleIsSignedByEpsonTse() = runBlocking<Unit> {
        val open = pos.open(user)
        val r = pos.complete(open, cart(), listOf(PaymentInput(PaymentType.CASH, "Bar", 250, 500))).receipt
        assertEquals(ReceiptState.FINISHED, r.state)
        assertNull(r.tseError)
        assertEquals(1L, r.tseTxNumber)
        assertTrue(EpsonTse.isMockSerial(r.tseSerial))
        assertNotNull(r.tseSignature)
        assertEquals("ecdsa-plain-SHA256", r.tseSigAlgorithm)
        assertTrue(r.tseStart!! <= r.tseEnd!!)
        // TSE-Stammdaten für die DSFinV-K (tse.csv) wurden abgelegt
        val info = db.tseInfo().all().single()
        assertEquals(r.tseSerial, info.serial)
        assertTrue(info.certificate.isNotEmpty())
    }

    @Test fun writeProtectionMeansTseOutageButSaleContinues() = runBlocking<Unit> {
        fixture.writeEnabled = false
        val open = pos.open(user)
        val r = pos.complete(open, cart(), listOf(PaymentInput(PaymentType.CASH, "Bar", 250, 500))).receipt
        assertEquals(ReceiptState.FINISHED, r.state)
        assertNull(r.tseSignature)
        assertTrue(r.tseError!!, r.tseError!!.contains("gesperrt"))
        assertTrue(fixture.mock.writeCalls.isEmpty())
    }

    @Test fun crashWithOpenTransactionIsRecoveredAndOrphanClosed() = runBlocking<Unit> {
        // Vorgang begonnen, App „stürzt ab“ (kein complete)
        val open = pos.open(user)
        pos.complete(pos.open(user), cart(), listOf(PaymentInput(PaymentType.CASH, "Bar", 250, 250)))
        // dazu eine TSE-Transaktion, deren Start-Antwort verloren ging (verwaist)
        val restarted = PosService(db, { fixture.epson() }, settings, audit, scope = scope)
        val orphan = fixture.epson().startTransaction("KASSE-01").txNumber

        restarted.recoverOpenReceipts()                       // offener Beleg wird als Abbruch abgeschlossen (mit TSE)
        assertEquals(ReceiptState.ABORTED, restarted.load(open.id).receipt.state)
        assertTrue(restarted.load(open.id).receipt.tseSignature != null)

        // Verwaiste Transaktion: auf der TSE abgeschlossen UND als eigener Vorgang „AVBelegabbruch“ in der Kasse
        val abort = restarted.reconcile().single()
        assertEquals(orphan, abort.tseTxNumber)
        assertEquals(ReceiptType.ABORT, abort.type)
        assertEquals(ReceiptState.ABORTED, abort.state)
        assertEquals(0L, abort.totalCents)
        assertNull(abort.tseStart)
        assertNotNull(abort.tseSignature)
        assertEquals("AVBelegabbruch^0.00_0.00_0.00_0.00_0.00^", abort.tseProcessData)
        assertEquals(EpsonTse.ORPHAN_PROCESS_DATA, abort.tseProcessData)
        assertTrue(abort.tseError!!.contains("Startzeit unbekannt"))
        assertTrue(restarted.load(abort.id).lines.isEmpty() && restarted.load(abort.id).payments.isEmpty())
        assertTrue(fixture.mock.startedTransactions.isEmpty())
        assertTrue(db.audit().all().any { it.action == "TSE_ABGLEICH" })
    }

    @Test fun closingReconcilesFirst() = runBlocking<Unit> {
        pos.complete(pos.open(user), cart(), listOf(PaymentInput(PaymentType.CASH, "Bar", 250, 250)))
        val orphan = fixture.epson().startTransaction("KASSE-01").txNumber
        ClosingService(db, pos, audit).close(user, countedCashCents = 250)
        // Kein offener TSE-Vorgang über den Abschluss hinweg; der Abbruch-Vorgang gehört zum Abschluss
        assertTrue(fixture.mock.startedTransactions.isEmpty())
        assertTrue(db.audit().all().joinToString { it.action + ":" + it.details }, db.audit().all().any { it.action == "TSE_ABGLEICH" && it.details.contains("Transaktion $orphan ") })
        assertTrue(db.receipts().unclosed().isEmpty()) // auch der Abbruch-Vorgang ist im Abschluss enthalten
    }

    @Test fun orphanClosingCanBeSwitchedOff() = runBlocking<Unit> {
        settings.put(Key.TSE_CLOSE_ORPHANS, "false")
        fixture.epson().startTransaction("KASSE-01")
        assertTrue(pos.reconcile().isEmpty())
        assertEquals(1, fixture.mock.startedTransactions.size)
    }
}
