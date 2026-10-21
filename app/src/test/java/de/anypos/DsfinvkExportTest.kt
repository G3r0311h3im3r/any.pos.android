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
import de.anypos.export.DsfinvkExport
import de.anypos.export.DsfinvkInput
import de.anypos.export.DsfinvkResult
import de.anypos.export.DsfinvkSchema
import de.anypos.export.DsfinvkTableWriter
import de.anypos.export.loadDsfinvkInput
import de.anypos.hardware.Company
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DsfinvkExportTest {
    private val indexFile = File("src/main/assets/dsfinvk/index.xml")
    private val schema = indexFile.inputStream().use { DsfinvkSchema.parse(it) }
    private val company = Company("Muster GmbH", "Hauptstr. 1", "10115", "Berlin", "12/345/67890", "DE123456789", "KASSE-01", "Danke")

    private lateinit var db: PosDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var pos: PosService
    private lateinit var closing: ClosingService
    private lateinit var user: User
    private lateinit var input: DsfinvkInput
    private lateinit var result: DsfinvkResult
    private val rates = PosDatabase.DEFAULT_TAX_RATES
    private val coffee = Article(id = 1, sku = "10001", name = "Kaffee", priceCents = 250, taxRateId = 1, categoryId = 1, barcode = "4012345678901")
    private val book = Article(id = 2, sku = "10002", name = "Buch \"Kasse\"; 2. Aufl.", priceCents = 1490, taxRateId = 2)

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PosDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audit = AuditLog(db.audit())
        val tse = SimulatedTse()
        pos = PosService(db, { tse }, SettingsRepository(db.settings(), scope), audit)
        closing = ClosingService(db, pos, audit)
        db.masterData().insertTaxRates(rates)
        db.masterData().upsertArticles(listOf(coffee, book))
        user = User(name = "Anna", pinHash = "x", role = Role.ADMIN).let { it.copy(id = db.users().upsert(it)) }

        // Tag 1
        pos.cashMovement(user, BusinessCase.OPENING, 10000, "Wechselgeld")
        var cart = Cart().add(coffee, rates[0], quantityMilli = 3000, categoryName = "Getränke").add(book, rates[1])
        cart = cart.setDiscountPercent(cart.lines[0].key, 1000) // 10 % auf 7,50 -> 0,75
        pos.complete(pos.open(user), cart, listOf(PaymentInput(PaymentType.CASH, "Bar", cart.totalCents, 5000)))
        val card = pos.complete(pos.open(user), Cart().add(book, rates[1]), listOf(PaymentInput(PaymentType.CARD, "EC-Karte", 1490)))
        pos.abort(pos.open(user), Cart().add(coffee, rates[0]))
        pos.cashMovement(user, BusinessCase.PRIVATE_OUT, -500, "Privat")
        closing.close(user, countedCashCents = 10000 + 2165 - 500)

        // Tag 2: Storno eines Belegs aus Tag 1
        pos.cancel(card.receipt.id, user)
        pos.complete(pos.open(user), Cart().add(coffee, rates[0]), listOf(PaymentInput(PaymentType.CASH, "Bar", 250)))
        closing.close(user, countedCashCents = 11665 + 250)

        input = loadDsfinvkInput(db, pos, company, "KASSE-01", "Test Tablet", "0.2.0", 0, Long.MAX_VALUE)
        result = DsfinvkExport.build(input, schema)
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    private fun rows(file: String): List<Map<String, String>> {
        val parsed = Csv.parse(result.files.getValue(file), ';')
        val header = parsed.first()
        return parsed.drop(1).map { r -> header.zip(r).toMap() }
    }

    private fun num(s: String) = BigDecimal(s.replace(',', '.'))

    @Test fun schemaHasAll20TablesFromOfficialIndex() {
        assertEquals(20, schema.tables.size)
        assertEquals(DsfinvkExport.FILES, schema.tables.map { it.file })
    }

    @Test fun everyFileMatchesIndexXmlStructureAndNumberFormat() {
        for (t in schema.tables) {
            val parsed = Csv.parse(result.files.getValue(t.file), ';')
            assertEquals(t.file, t.columns.map { it.name }, parsed.first())
            for (row in parsed.drop(1)) {
                assertEquals("${t.file}: Spaltenzahl", t.columns.size, row.size)
                t.columns.zip(row).filter { it.first.numeric && it.second.isNotEmpty() }.forEach { (c, v) ->
                    assertTrue("${t.file}.${c.name} = $v", Regex("-?\\d+(,\\d+)?").matches(v))
                    val decimals = v.substringAfter(',', "").length
                    assertTrue("${t.file}.${c.name} = $v", decimals <= c.accuracy)
                    if (c.accuracy == 2) assertEquals("${t.file}.${c.name} = $v", 2, decimals)
                }
                t.columns.zip(row).filter { it.first.maxLength != null }.forEach { (c, v) ->
                    assertTrue("${t.file}.${c.name} zu lang", v.length <= c.maxLength!!)
                }
            }
        }
        assertTrue(result.files.getValue("cashpointclosing.csv").endsWith("\r\n"))
    }

    @Test fun mandatoryMasterDataAndEnumerations() {
        val z = rows("cashpointclosing.csv")
        assertEquals(listOf("1", "2"), z.map { it["Z_NR"] })
        z.forEach {
            assertEquals("2.4", it["TAXONOMIE_VERSION"])
            assertEquals("DEU", it["LAND"])
            assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2}").matches(it["Z_ERSTELLUNG"]!!))
        }
        assertEquals(listOf("Beleg", "AVBelegabbruch"), rows("transactions.csv").map { it["BON_TYP"] }.distinct())
        val gv = setOf("Umsatz", "Anfangsbestand", "Privatentnahme", "DifferenzSollIst", "Einzahlung", "Auszahlung", "Geldtransit", "Privateinlage")
        assertTrue(rows("lines.csv").all { it["GV_TYP"] in gv })
        assertTrue(rows("datapayment.csv").all { it["ZAHLART_TYP"] in setOf("Bar", "Unbar") })
        assertEquals(setOf("1", "2", "3", "4", "5", "6"), rows("vat.csv").map { it["UST_SCHLUESSEL"] }.toSet())
        assertEquals("19,00", rows("vat.csv").first { it["UST_SCHLUESSEL"] == "1" }["UST_SATZ"])
    }

    @Test fun closingSumsOnlyContainBelegeAndMatchDetails() {
        val bonTyp = rows("transactions.csv").associate { (it["Z_NR"] + "/" + it["BON_ID"]) to it["BON_TYP"] }
        for (z in rows("cashpointclosing.csv")) {
            val nr = z["Z_NR"]
            val belegIds = bonTyp.filter { it.key.startsWith("$nr/") && it.value == "Beleg" }.keys.map { it.substringAfter('/') }.toSet()
            val payments = rows("datapayment.csv").filter { it["Z_NR"] == nr && it["BON_ID"] in belegIds }
            assertEquals(payments.sumOf { num(it["BASISWAEH_BETRAG"]!!) }, num(z["Z_SE_ZAHLUNGEN"]!!))
            assertEquals(payments.filter { it["ZAHLART_TYP"] == "Bar" }.sumOf { num(it["BASISWAEH_BETRAG"]!!) }, num(z["Z_SE_BARZAHLUNGEN"]!!))
            val lineGross = rows("lines_vat.csv").filter { it["Z_NR"] == nr && it["BON_ID"] in belegIds }.sumOf { num(it["POS_BRUTTO"]!!) }
            val gvGross = rows("businesscases.csv").filter { it["Z_NR"] == nr }.sumOf { num(it["Z_UMS_BRUTTO"]!!) }
            assertEquals(0, lineGross.compareTo(gvGross))
        }
        // Abbruch (Kaffee 2,50) taucht in keiner Abschlusssumme auf
        val umsatz19Day1 = rows("businesscases.csv").first { it["Z_NR"] == "1" && it["GV_TYP"] == "Umsatz" && it["UST_SCHLUESSEL"] == "1" }
        assertEquals(0, num(umsatz19Day1["Z_UMS_BRUTTO"]!!).compareTo(BigDecimal("6.75")))
        // Kassenbestand Tag 1: 100 + 21,65 - 5 = 116,65
        assertEquals("116,65", rows("cash_per_currency.csv").first { it["Z_NR"] == "1" }["ZAHLART_BETRAG_WAEH"])
    }

    @Test fun vatIsConsistentPerLineAndPerBon() {
        for (l in rows("lines_vat.csv")) {
            assertEquals(0, (num(l["POS_NETTO"]!!) + num(l["POS_UST"]!!)).compareTo(num(l["POS_BRUTTO"]!!)))
        }
        val tvat = rows("transactions_vat.csv")
        for (t in rows("transactions.csv")) {
            val sum = tvat.filter { it["Z_NR"] == t["Z_NR"] && it["BON_ID"] == t["BON_ID"] }.sumOf { num(it["BON_BRUTTO"]!!) }
            assertEquals("Bon ${t["BON_ID"]}", 0, sum.compareTo(num(t["UMS_BRUTTO"]!!)))
        }
    }

    @Test fun discountQuantityAndItemDataAreExported() {
        val coffeeLine = rows("lines.csv").first { it["BON_ID"] == "2" && it["ART_NR"] == "10001" }
        assertEquals("3,000", coffeeLine["MENGE"])
        assertEquals("2,50", coffeeLine["STK_BR"])
        assertEquals("4012345678901", coffeeLine["GTIN"])
        assertEquals("Getränke", coffeeLine["WARENGR"])
        val amounts = rows("itemamounts.csv").filter { it["BON_ID"] == "2" }
        assertEquals(listOf("base_amount", "discount"), amounts.map { it["TYP"] })
        assertEquals("7,50", amounts[0]["PF_BRUTTO"])
        assertEquals("-0,75", amounts[1]["PF_BRUTTO"])
        // Kassenbewegung ohne Artikeldaten
        val opening = rows("lines.csv").first { it["GV_TYP"] == "Anfangsbestand" }
        assertEquals("", opening["MENGE"])
        assertEquals("", opening["ART_NR"])
        // Anführungszeichen und Semikolon im Artikeltext bleiben erhalten
        assertTrue(rows("lines.csv").any { it["ARTIKELTEXT"] == "Buch \"Kasse\"; 2. Aufl." })
    }

    @Test fun cancellationReferencesOriginalAcrossClosings() {
        val storno = rows("transactions.csv").single { it["BON_STORNO"] == "1" }
        assertEquals("2", storno["Z_NR"])
        assertEquals("-14,90", storno["UMS_BRUTTO"])
        val ref = rows("references.csv").single()
        assertEquals("Transaktion", ref["REF_TYP"])
        assertEquals("3", ref["REF_BON_ID"])
        assertEquals("1", ref["REF_Z_NR"])
        assertEquals("KASSE-01", ref["REF_Z_KASSE_ID"])
        assertTrue(rows("transactions.csv").filter { it["BON_ID"] != storno["BON_ID"] }.all { it["BON_STORNO"] == "0" })
    }

    @Test fun tseDataIsReferencedAndFormatted() {
        val tse = rows("tse.csv")
        assertEquals(listOf("1", "2"), tse.map { it["Z_NR"] })
        assertTrue(tse.all { it["TSE_ID"] == "1" && it["TSE_SERIAL"] == SimulatedTse.SERIAL })
        val ta = rows("transactions_tse.csv")
        assertEquals(rows("transactions.csv").size, ta.size)
        ta.forEach {
            assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z").matches(it["TSE_TA_START"]!!))
            assertEquals("Kassenbeleg-V1", it["TSE_TA_VORGANGSART"])
        }
        assertTrue(result.warnings.any { it.contains("Simulation") })
    }

    /** Regression: Belege, deren TSE nicht in tse_info steht (z. B. vor Migration 1->2), verlieren keine TSE-Daten. */
    @Test fun tseDataIsNotDroppedWhenTseInfoIsMissing() {
        val r = DsfinvkExport.build(input.copy(tseInfos = emptyList()), schema)
        val tse = Csv.parse(r.files.getValue("tse.csv"), ';').drop(1)
        val ta = Csv.parse(r.files.getValue("transactions_tse.csv"), ';').drop(1)
        assertEquals(2, tse.size)
        assertEquals(rows("transactions.csv").size, ta.size)
        assertTrue(r.warnings.any { it.contains("Zertifikat") && it.contains("nicht gespeichert") })
    }

    @Test fun validationBlocksIncompleteMasterData() {
        assertTrue(DsfinvkExport.validate(input).isEmpty())
        val bad = input.copy(company = company.copy(zip = "", taxNumber = "", vatId = "", country = "DE"), closings = emptyList())
        // PLZ, Land, Steuernummer/USt-IdNr., kein Abschluss
        assertEquals(4, DsfinvkExport.validate(bad).size)
    }

    @Test fun zipContainsIndexDtdAndAllTables() {
        val out = ByteArrayOutputStream()
        DsfinvkExport.writeZip(out, result, indexFile.readText(), ByteArray(3), company)
        val names = mutableListOf<String>()
        var index = ""
        ZipInputStream(out.toByteArray().inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                names += e.name
                if (e.name == "index.xml") index = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(22, names.size)
        assertTrue(names.containsAll(listOf("index.xml", "gdpdu-01-09-2004.dtd") + DsfinvkExport.FILES))
        assertTrue(index.contains("<Name>Muster GmbH</Name>"))
        assertTrue(index.contains("<Location>10115 Berlin</Location>"))
    }

    @Test fun numberFormatting() {
        assertEquals("12,50", DsfinvkTableWriter.number(BigDecimal("12.5"), 2))
        assertEquals("0,39916", DsfinvkTableWriter.number(BigDecimal("0.399159"), 5))
        assertEquals("4,20", DsfinvkTableWriter.number(BigDecimal("4.2"), 5))
        assertEquals("1,500", DsfinvkTableWriter.number(BigDecimal("1.5"), 3))
        assertEquals("-7", DsfinvkTableWriter.number(BigDecimal("-7"), 0))
    }
}
