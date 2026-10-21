package de.anypos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.anypos.backup.BackupService
import de.anypos.backup.SecretStore
import de.anypos.data.Article
import de.anypos.data.BusinessCase
import de.anypos.data.PaymentType
import de.anypos.data.PosDatabase
import de.anypos.data.Role
import de.anypos.data.User
import de.anypos.data.VoucherType
import de.anypos.domain.AuditLog
import de.anypos.domain.Cart
import de.anypos.domain.ClosingService
import de.anypos.domain.PaymentInput
import de.anypos.domain.PosException
import de.anypos.domain.PosService
import de.anypos.domain.ReturnItem
import de.anypos.domain.SettingsRepository
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

/** Splitzahlung, Gutscheine, Teil-Retoure und Datensicherung gegen eine echte Room-Datenbank. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SalesFeaturesTest {
    private lateinit var db: PosDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var pos: PosService
    private lateinit var closing: ClosingService
    private lateinit var audit: AuditLog
    private lateinit var settings: SettingsRepository
    private lateinit var user: User
    private val rates = PosDatabase.DEFAULT_TAX_RATES
    private val vat19 = rates[0]
    private val vat7 = rates[1]
    private val noVat = rates.first { it.dsfinvkKey == 5 }
    private val coffee = Article(id = 1, sku = "1", name = "Kaffee", priceCents = 250, taxRateId = 1)
    private val book = Article(id = 2, sku = "2", name = "Buch", priceCents = 2000, taxRateId = 2)

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PosDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        audit = AuditLog(db.audit())
        settings = SettingsRepository(db.settings(), scope)
        val tse = SimulatedTse()
        pos = PosService(db, { tse }, settings, audit)
        closing = ClosingService(db, pos, audit)
        db.masterData().insertTaxRates(rates)
        db.masterData().upsertArticles(listOf(coffee, book))
        user = User(name = "Anna", pinHash = "x", role = Role.ADMIN).let { it.copy(id = db.users().upsert(it)) }
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    private suspend fun sell(cart: Cart, vararg payments: PaymentInput) = pos.complete(pos.open(user), cart, payments.toList())
    private fun cash(c: Long, given: Long? = null) = PaymentInput(PaymentType.CASH, "Bar", c, given)
    private fun card(c: Long) = PaymentInput(PaymentType.CARD, "EC-Karte", c)

    // ---------- Splitzahlung

    @Test fun splitPaymentCashAndCard() = runBlocking {
        val r = sell(Cart().add(book, vat7).add(coffee, vat19), cash(1000, 1000), card(1250))
        assertEquals(2, r.payments.size)
        assertEquals("Beleg^2.50_20.00_0.00_0.00_0.00^10.00:Bar_12.50:Unbar", r.receipt.tseProcessData)
        val z = closing.preview(user).closing
        assertEquals(1000L, z.cashSalesCents)
        assertEquals(1250L, z.cardSalesCents)
        val text = ReceiptDocument.toText(ReceiptDocument.receipt(company, r), 48)
        assertTrue(text.contains("EC-Karte") && text.contains("Bar"))
    }

    @Test fun splitPaymentMustMatchTotal() = runBlocking {
        try { sell(Cart().add(book, vat7), cash(1000), card(500)); fail() } catch (_: PosException) {}
    }

    // ---------- Gutscheine

    @Test fun multiPurposeVoucherIsNotTaxedOnSaleButOnRedemption() = runBlocking {
        val sale = sell(Cart().addVoucherSale("GS-TEST1", VoucherType.MULTI, 5000, noVat), cash(5000))
        assertEquals("Beleg^0.00_0.00_0.00_0.00_50.00^50.00:Bar", sale.receipt.tseProcessData)
        assertEquals(5000L, db.vouchers().get("GS-TEST1")!!.balanceCents)

        // Einlösung: Buch 20,00 komplett mit Gutschein
        val v = db.vouchers().get("GS-TEST1")!!
        val r = sell(Cart().add(book, vat7).addVoucherRedemption(v, 2000, noVat))
        assertEquals(0L, r.receipt.totalCents)
        assertEquals("Beleg^0.00_20.00_0.00_0.00_-20.00^", r.receipt.tseProcessData)
        assertEquals(3000L, db.vouchers().get("GS-TEST1")!!.balanceCents)

        val z = closing.preview(user)
        assertEquals(5000L, z.closing.voucherSalesCents)
        assertEquals(-2000L, z.closing.voucherRedemptionsCents)
        assertEquals(5000L, z.closing.cashSalesCents) // Barverkauf des Gutscheins zählt zum Kassenbestand
        // Steuerpflichtig ist nur das Buch, nicht der Gutscheinverkauf
        assertEquals(listOf(2 to 2000L), z.taxBuckets.map { it.dsfinvkKey to it.grossCents })
    }

    @Test fun voucherBalanceCannotBeExceededOrUsedTwice() = runBlocking {
        sell(Cart().addVoucherSale("GS-A", VoucherType.MULTI, 1000, noVat), cash(1000))
        val v = db.vouchers().get("GS-A")!!
        try { sell(Cart().add(book, vat7).addVoucherRedemption(v, 1500, noVat), cash(500)); fail() } catch (e: PosException) {
            assertTrue(e.message!!.contains("nur noch"))
        }
        try { Cart().addVoucherSale("GS-A", VoucherType.MULTI, 100, noVat).addVoucherSale("GS-A", VoucherType.MULTI, 100, noVat); fail() } catch (_: IllegalArgumentException) {}
        try { sell(Cart().addVoucherSale("GS-A", VoucherType.MULTI, 100, noVat), cash(100)); fail() } catch (e: PosException) {
            assertTrue(e.message!!.contains("bereits vergeben"))
        }
    }

    @Test fun singlePurposeVoucherIsTaxedOnSale() = runBlocking {
        sell(Cart().addVoucherSale("GS-EZ", VoucherType.SINGLE, 1190, vat19), card(1190))
        assertEquals(1L, db.vouchers().get("GS-EZ")!!.taxRateId)
        val v = db.vouchers().get("GS-EZ")!!
        val r = sell(Cart().add(coffee, vat19, quantityMilli = 4000).addVoucherRedemption(v, 1000, vat19))
        // Kaffee 10,00 abzüglich Einlösung 10,00 -> Steuer im Bon gleicht sich aus
        assertEquals("Beleg^0.00_0.00_0.00_0.00_0.00^", r.receipt.tseProcessData)
        val buckets = closing.preview(user).taxBuckets
        assertEquals(listOf(1 to 1190L), buckets.map { it.dsfinvkKey to it.grossCents }) // versteuert beim Verkauf
    }

    @Test fun cancelVoucherSaleOnlyWhileUnused() = runBlocking {
        val s1 = sell(Cart().addVoucherSale("GS-U", VoucherType.MULTI, 1000, noVat), cash(1000))
        pos.cancel(s1.receipt.id, user)
        assertFalse(db.vouchers().get("GS-U")!!.active)

        val s2 = sell(Cart().addVoucherSale("GS-V", VoucherType.MULTI, 1000, noVat), cash(1000))
        sell(Cart().add(coffee, vat19).addVoucherRedemption(db.vouchers().get("GS-V")!!, 250, noVat))
        try { pos.cancel(s2.receipt.id, user); fail() } catch (e: PosException) { assertTrue(e.message!!.contains("eingelöst")) }
    }

    @Test fun cancelRedemptionRestoresBalance() = runBlocking {
        sell(Cart().addVoucherSale("GS-R", VoucherType.MULTI, 1000, noVat), cash(1000))
        val r = sell(Cart().add(book, vat7).addVoucherRedemption(db.vouchers().get("GS-R")!!, 1000, noVat), cash(1000))
        assertEquals(0L, db.vouchers().get("GS-R")!!.balanceCents)
        pos.cancel(r.receipt.id, user)
        assertEquals(1000L, db.vouchers().get("GS-R")!!.balanceCents)
    }

    // ---------- Teil-Retoure

    @Test fun partialReturnWithProportionalDiscount() = runBlocking {
        var cart = Cart().add(coffee, vat19, quantityMilli = 3000).add(book, vat7)
        cart = cart.setDiscountPercent(cart.lines[0].key, 1000) // 7,50 - 0,75
        val sale = sell(cart, cash(cart.totalCents))
        val coffeeLine = sale.lines.first { it.sku == "1" }

        val ret = pos.returnItems(sale.receipt.id, listOf(ReturnItem(coffeeLine.id, 2000)), PaymentType.CASH, "Bar", user)
        assertTrue(ret.receipt.isReturn && !ret.receipt.isCancellation)
        assertEquals(-450L, ret.receipt.totalCents) // 5,00 - anteiliger Rabatt 0,50
        assertEquals(-2000L, ret.lines.single().quantityMilli)
        assertEquals(coffeeLine.id, ret.lines.single().referencesLineId)
        assertEquals(-450L, ret.payments.single().amountCents)
        assertTrue(ReceiptDocument.toText(ReceiptDocument.receipt(company, ret), 48).contains("RÜCKGABE zu Beleg ${sale.receipt.number}"))

        assertEquals(mapOf(coffeeLine.id to 2000L), pos.returnedQuantities(sale))
        try { pos.returnItems(sale.receipt.id, listOf(ReturnItem(coffeeLine.id, 2000)), PaymentType.CASH, "Bar", user); fail() } catch (e: PosException) {
            assertTrue(e.message!!.contains("höchstens 1"))
        }
        // Restmenge zurück: zusammen genau der ursprüngliche Positionsbetrag
        val ret2 = pos.returnItems(sale.receipt.id, listOf(ReturnItem(coffeeLine.id, 1000)), PaymentType.CARD, "EC-Karte", user)
        assertEquals(-225L, ret2.receipt.totalCents)
        assertEquals(coffeeLine.totalCents, 450L + 225L)
        // Nach einer Retoure ist kein Vollstorno mehr möglich
        try { pos.cancel(sale.receipt.id, user); fail() } catch (e: PosException) { assertTrue(e.message!!.contains("Retoure")) }
    }

    @Test fun returnNotPossibleAfterCancellation() = runBlocking {
        val sale = sell(Cart().add(book, vat7), card(2000))
        pos.cancel(sale.receipt.id, user)
        try { pos.returnItems(sale.receipt.id, listOf(ReturnItem(sale.lines[0].id, 1000)), PaymentType.CASH, "Bar", user); fail() } catch (_: PosException) {}
    }

    @Test fun exportsDistinguishReturnsVouchersAndAccounts() = runBlocking {
        val sale = sell(Cart().add(book, vat7), cash(2000))
        pos.returnItems(sale.receipt.id, listOf(ReturnItem(sale.lines[0].id, 1000)), PaymentType.CASH, "Bar", user)
        sell(Cart().addVoucherSale("GS-X", VoucherType.MULTI, 3000, noVat), cash(3000))
        closing.close(user, countedCashCents = 3000)

        // DSFinV-K
        val schema = File("src/main/assets/dsfinvk/index.xml").inputStream().use { DsfinvkSchema.parse(it) }
        val input = loadDsfinvkInput(db, pos, company, "KASSE-01", "Test", "0.3.0", 0, Long.MAX_VALUE)
        val files = DsfinvkExport.build(input, schema).files
        fun rows(f: String) = Csv.parse(files.getValue(f), ';').let { p -> p.drop(1).map { r -> p[0].zip(r).toMap() } }
        assertTrue(rows("transactions.csv").all { it["BON_STORNO"] == "0" })
        assertEquals("Rueckgabe", rows("references.csv").single()["REF_NAME"])
        val voucherLine = rows("lines.csv").single { it["GV_TYP"] == "MehrzweckgutscheinKauf" }
        assertEquals("GS-X", voucherLine["GUTSCHEIN_NR"])
        assertEquals("5", rows("lines_vat.csv").single { it["BON_ID"] == voucherLine["BON_ID"] }["UST_SCHLUESSEL"])

        // Kassenbuch: Gutscheinverkauf erscheint als Bareinnahme
        val book = CashBook.build(input.closings.flatMap { it.receipts }, 0)
        assertTrue(book.any { it.text == "Gutscheinverkauf (Mehrzweck)" && it.amountCents == 3000L })
        assertTrue(book.any { it.text.startsWith("Rückgabe zu Beleg") && it.amountCents == -2000L })
        assertEquals(3000L, book.last().balanceCents)

        // DATEV: Mehrzweckgutschein braucht ein eigenes Konto
        val accounts = DatevAccounts("1000", "8400", "8300", "8200", "1360", "1360", "1800", "1890", "1360", "4900", "2309")
        val cfg = DatevConfig("1", "2", "0101", 4, "KASSE-01", accounts, includeCardSales = true)
        try { DatevExport.bookings(input.closings.flatMap { it.receipts }, cfg); fail() } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Mehrzweckgutscheine"))
        }
        val bookings = DatevExport.bookings(input.closings.flatMap { it.receipts }, cfg.copy(accounts = accounts.copy(voucher = "1590")))
        assertEquals(3000L, bookings.single { it.contra == "1590" }.amountCents)
    }

    // ---------- Datensicherung

    @Test fun backupAndPreparedRestoreRoundTrip() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Platzhalter für die "bisherige" Datenbank früh anlegen (unter Windows sperrt der Virenscanner frische Dateien kurz)
        val dbFile = context.getDatabasePath("anypos.db").apply { parentFile!!.mkdirs(); writeText("alt") }
        sell(Cart().add(book, vat7), cash(2000))
        val plainStore = object : SecretStore {
            override fun encrypt(plain: String) = plain
            override fun decrypt(stored: String) = stored
        }
        val service = BackupService(context, db, settings, audit, plainStore)
        val out = ByteArrayOutputStream()
        service.backupTo(out, "sicher-123", user.name)
        assertTrue(settings[de.anypos.domain.Key.BACKUP_LAST].isNotBlank())

        try { service.prepareRestore(out.toByteArray().inputStream(), "falsch-123", user.name); fail() } catch (_: Exception) {}
        val meta = service.prepareRestore(out.toByteArray().inputStream(), "sicher-123", user.name)
        assertEquals(PosDatabase.VERSION.toString(), meta["schema"])

        // Anwendung beim nächsten Start: neue Datenbank an Ort und Stelle, alte bleibt als Kopie
        val kept = BackupService.applyPendingRestore(context)!!
        assertTrue(File(dbFile.parentFile, kept).readText() == "alt")
        assertTrue(dbFile.readBytes().copyOf(15).toString(Charsets.US_ASCII) == "SQLite format 3")
        // Wiederhergestellte Datenbank enthält den Beleg
        val restored = Room.databaseBuilder(context, PosDatabase::class.java, "anypos.db").allowMainThreadQueries().build()
        assertEquals(1L, restored.receipts().maxNumber())
        restored.close()
    }

    private val company = Company("Laden", "Str. 1", "12345", "Ort", "12/345/67890", "", "KASSE-01", "Danke")
}
