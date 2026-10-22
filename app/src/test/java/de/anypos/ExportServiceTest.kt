package de.anypos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.anypos.data.Article
import de.anypos.data.PaymentType
import de.anypos.data.PosDatabase
import de.anypos.data.Role
import de.anypos.data.User
import de.anypos.domain.AuditLog
import de.anypos.domain.Cart
import de.anypos.domain.ClosingService
import de.anypos.domain.Key
import de.anypos.domain.PaymentInput
import de.anypos.domain.PosService
import de.anypos.domain.SettingsRepository
import de.anypos.export.ExportException
import de.anypos.export.ExportService
import de.anypos.hardware.Company
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
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ExportServiceTest {
    private lateinit var db: PosDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var settings: SettingsRepository
    private lateinit var exports: ExportService
    private var now = at(2026, 8, 14)
    private val company = Company("Muster GmbH", "Hauptstr. 1", "10115", "Berlin", "12/345/67890", "", "KASSE-01", "")

    private fun at(y: Int, m: Int, d: Int) = LocalDateTime.of(y, m, d, 12, 0).atZone(ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()

    @Before fun setUp(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        settings = SettingsRepository(db.settings(), scope)
        val audit = AuditLog(db.audit())
        val tse = SimulatedTse(clock = { now })
        val pos = PosService(db, { tse }, settings, audit, clock = { now })
        val closing = ClosingService(db, pos, audit, clock = { now })
        exports = ExportService(context, db, pos, settings, audit) { company }
        db.masterData().insertTaxRates(PosDatabase.DEFAULT_TAX_RATES)
        val book = Article(id = 1, sku = "1", name = "Buch", priceCents = 2000, taxRateId = 2)
        db.masterData().upsertArticles(listOf(book))
        val user = User(name = "Anna", pinHash = "x", role = Role.ADMIN).let { it.copy(id = db.users().upsert(it)) }

        pos.complete(pos.open(user), Cart().add(book, PosDatabase.DEFAULT_TAX_RATES[1]), listOf(PaymentInput(PaymentType.CASH, "Bar", 2000)))
        closing.close(user, 2000)
        now = at(2026, 9, 3)
        pos.complete(pos.open(user), Cart().add(book, PosDatabase.DEFAULT_TAX_RATES[1]), listOf(PaymentInput(PaymentType.CARD, "EC-Karte", 2000)))
        closing.close(user, 2000)
        Unit
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    @Test fun pendingMonthsStartAtFirstReceiptAndEndBeforeCurrentMonth() = runBlocking {
        assertEquals(listOf(YearMonth.of(2026, 8), YearMonth.of(2026, 9)), exports.pendingMonths(at(2026, 10, 21)))
        settings.put(Key.EXPORT_LAST_MONTH, "2026-08")
        assertEquals(listOf(YearMonth.of(2026, 9)), exports.pendingMonths(at(2026, 10, 21)))
        settings.put(Key.EXPORT_LAST_MONTH, "2026-09")
        assertTrue(exports.pendingMonths(at(2026, 10, 21)).isEmpty())
    }

    @Test fun cashBookCarriesOpeningBalanceIntoNextMonth() = runBlocking {
        val sept = exports.cashBook(YearMonth.of(2026, 9))
        assertEquals(2000L, sept.openingCents) // Barverkauf aus August
        assertTrue(sept.rows.isEmpty())        // September nur Karte
        val csv = String(exports.cashBookCsv(YearMonth.of(2026, 8)).bytes, Charsets.UTF_8)
        assertTrue(csv.startsWith("﻿Kasse;Datum"))
        assertTrue(csv.contains("Barumsatz 7 %"))
    }

    @Test fun datevWorksWithoutAdvisorData() = runBlocking {
        // Ohne E-Mail, Berater- und Mandantennummer: Export entsteht, mit Hinweis
        val f = exports.datev(YearMonth.of(2026, 8), true)
        assertEquals("EXTF_Buchungsstapel_KASSE-01_2026-08.csv", f.name)
        assertTrue(String(f.bytes, charset("windows-1252")).startsWith("\"EXTF\";700"))
        assertTrue(f.warnings.any { it.contains("Beraternummer und Mandantennummer") })

        settings.put(Key.DATEV_ADVISOR, "12345"); settings.put(Key.DATEV_CLIENT, "678")
        val g = exports.datev(YearMonth.of(2026, 8), true)
        assertTrue(String(g.bytes, charset("windows-1252")).lines()[0].contains(";12345;678;"))
        assertTrue(g.warnings.none { it.contains("Beraternummer") })
    }

    @Test fun dsfinvkZipFromAssetsContainsAllTables() = runBlocking {
        val (from, to) = de.anypos.export.CashBook.dayRange(YearMonth.of(2026, 9).atDay(1), YearMonth.of(2026, 9).atEndOfMonth())
        val f = exports.dsfinvk(from, to, "2026-09", "Test")
        val names = mutableListOf<String>()
        ZipInputStream(f.bytes.inputStream()).use { z -> while (true) { names += (z.nextEntry ?: break).name } }
        assertEquals(22, names.size)
        assertEquals("DSFinV-K_KASSE-01_2026-09.zip", f.name)
    }

    @Test fun monthlyExportWithoutFolderExplainsWhatIsMissing() = runBlocking {
        val notes = exports.monthlyExport("Anna", "Test", at(2026, 10, 21))
        assertTrue(notes.single().contains("kein Sicherungsordner"))
        assertEquals("", settings[Key.EXPORT_LAST_MONTH]) // nichts als erledigt markiert
    }
}
