package de.anypos

import de.anypos.tse.TseException
import de.anypos.tse.TseWriteBlockedException
import de.anypos.tsemock.MockConfig
import de.anypos.tsemock.Scenario
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant

/** Schritt 8: TSE-Export (TAR) gegen die Mock-TSE. */
class EpsonTseExportTest {

    private val fixtures = mutableListOf<MockTseFixture>()
    @After fun tearDown() = fixtures.forEach { it.close() }

    private fun fixture(scenario: Scenario = Scenario.OPERATIONAL, config: MockConfig = MockConfig(exportChunkSize = 700)) =
        MockTseFixture(scenario, config).also { it.writeEnabled = true; fixtures += it }

    private val adminPin = "11111"

    private suspend fun MockTseFixture.sale() {
        val epson = epson()
        val s = epson.startTransaction("KASSE-01")
        epson.finishTransaction("KASSE-01", s.txNumber, "Kassenbeleg-V1", "Beleg^2.50_0.00_0.00_0.00_0.00^2.50:Bar")
    }

    @Test fun fullExportWritesTarAndNeverDeletes() = runBlocking<Unit> {
        val f = fixture()
        f.sale()
        val out = ByteArrayOutputStream()
        val r = f.epson().exportTar(adminPin, out)
        val tar = out.toByteArray()

        assertEquals("ArchiveExport", r.function)
        assertEquals(tar.size.toLong(), r.bytes)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(tar).joinToString("") { "%02x".format(it) }, r.sha256)
        assertEquals("ustar", String(tar, 257, 5))
        assertTrue(String(tar).contains("journal.log"))
        assertTrue(f.count("GetExportData") >= 3)        // Suche + mehrere Blöcke
        assertEquals(1, f.count("FinalizeExport"))
        assertEquals(0, f.count("CancelExport"))
        assertEquals(1, f.count("LogOutForAdmin"))

        // Nichts gelöscht: ein zweiter Export enthält dieselben Protokolldaten
        val again = ByteArrayOutputStream()
        f.epson().exportTar(adminPin, again)
        assertTrue(String(again.toByteArray()).contains("FinishTransaction|KASSE-01|1|"))
        // TSE nicht mehr im Exportmodus: Kasse arbeitet weiter
        f.sale()
    }

    @Test fun periodExport() = runBlocking<Unit> {
        val f = fixture()
        val r = f.epson().exportTar(adminPin, ByteArrayOutputStream(), Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"))
        assertEquals("ExportFilteredByPeriodOfTime", r.function)
    }

    @Test fun failureCancelsExportMode() = runBlocking<Unit> {
        val f = fixture()
        f.mock.inject("TSE1_ERROR_EXPORT_FAILED", "GetExportData")
        try { f.epson().exportTar(adminPin, ByteArrayOutputStream()); fail() } catch (e: TseException) {}
        assertEquals(1, f.count("CancelExport"))
        assertEquals(0, f.count("FinalizeExport"))
        assertEquals(1, f.count("LogOutForAdmin"))
        f.sale() // TSE ist nicht im Exportmodus hängen geblieben
    }

    @Test fun exportSetsTimeFirst() = runBlocking<Unit> {
        // Von JTL eingerichtete TSE nach dem Einschalten: Zeit fehlt → wird vor dem Export gesetzt (Spez. S. 45)
        val f = fixture(Scenario.JTL_TAKEOVER)
        f.epson().exportTar(adminPin, ByteArrayOutputStream())
        val calls = f.mock.calls
        assertTrue(calls.indexOf("UpdateTime") in 0 until calls.indexOf("ArchiveExport"))
    }

    @Test fun writeProtectionBlocksExport() = runBlocking<Unit> {
        val f = fixture()
        f.writeEnabled = false
        try { f.epson().exportTar(adminPin, ByteArrayOutputStream()); fail() } catch (_: TseWriteBlockedException) {}
        assertTrue(f.mock.writeCalls.isEmpty())
        assertEquals(0, f.count("ArchiveExport"))
    }
}
