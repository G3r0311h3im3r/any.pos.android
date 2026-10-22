package de.anypos

import de.anypos.tse.EpsonTse
import de.anypos.tse.TseResultUnknownException
import de.anypos.tse.TseTransportException
import de.anypos.tsemock.Fault
import de.anypos.tsemock.MockConfig
import de.anypos.tsemock.Scenario
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Schritt 8b: TSE-Zugang per ESC/POS (wie am USB-Kabel) gegen den ESC/POS-Zugang der Mock-TSE. */
class EscPosTseTransportTest {

    private val fixtures = mutableListOf<MockTseFixture>()
    @After fun tearDown() = fixtures.forEach { it.close() }

    private fun fixture(scenario: Scenario = Scenario.OPERATIONAL, config: MockConfig = MockConfig()) =
        MockTseFixture(scenario, config, escpos = true).also { it.writeEnabled = true; fixtures += it }

    private val storageInfo =
        """{"storage":{"type":"COMMON","vendor":""},"function":"GetStorageInfo","input":{},"compress":{"required":false,"type":""}}"""

    // --- Protokoll

    @Test fun multiBlockAnswerBetweenStatusBytes() = runBlocking<Unit> {
        val f = fixture()
        f.escServer.noise = true
        val t = f.escPosTransport()
        val answer = t.operate(storageInfo)
        assertTrue(answer.length > 256) // mehrere Blöcke à 256 Bytes
        assertTrue(answer.contains("\"function\":\"GetStorageInfo\""))
        assertTrue(answer.contains("EXECUTION_OK"))
        t.close()
        assertFalse(f.escServer.isTseEnabled)
        assertEquals(0L, f.escServer.unownedRequests.get())
        assertEquals(0L, f.escServer.printed.get())
    }

    @Test fun holdsPrinterLineFromOpenToClose() = runBlocking<Unit> {
        val f = fixture()
        val t = f.escPosTransport()
        t.open()
        assertTrue(f.printerLine.isLocked)
        assertTrue(f.escServer.isTseEnabled)
        t.operate(storageInfo)
        assertTrue(f.printerLine.isLocked)
        t.close()
        assertFalse(f.printerLine.isLocked)
        assertFalse(t.isConnected)
    }

    @Test fun waitsWhilePrinting() = runBlocking<Unit> {
        val f = fixture()
        val t = f.escPosTransport()
        f.printerLine.lock() // Druckauftrag läuft
        val call = async { t.operate(storageInfo) }
        delay(300)
        assertEquals(0, f.count("GetStorageInfo"))
        f.printerLine.unlock()
        assertTrue(call.await().contains("EXECUTION_OK"))
        t.close()
    }

    @Test fun tooLargeIsRejectedBeforeSending() = runBlocking<Unit> {
        val f = fixture()
        val t = f.escPosTransport()
        val big = """{"function":"UpdateTransaction","input":{"processData":"${"A".repeat(70_000)}"}}"""
        try {
            t.operate(big)
            fail("zu große Anfrage gesendet")
        } catch (e: TseResultUnknownException) {
            fail("darf nicht unbekannt sein: ${e.message}")
        } catch (e: TseTransportException) {
            assertTrue(e.message!!.contains("zu groß"))
        }
        assertEquals(0, f.count("UpdateTransaction"))
        assertFalse(f.printerLine.isLocked)
    }

    @Test fun dropAfterExecutionIsUnknownAndTseStaysBlocked() = runBlocking<Unit> {
        val f = fixture()
        val t = f.escPosTransport()
        f.escServer.injectFault(Fault.DROP_AFTER_EXECUTE, "GetStorageInfo")
        try {
            t.operate(storageInfo)
            fail("Abbruch nach dem Senden muss unbekannt sein")
        } catch (e: TseResultUnknownException) {
            assertEquals("GetStorageInfo", e.function)
        }
        assertEquals(1, f.count("GetStorageInfo")) // nie erneut gesendet
        assertFalse(f.printerLine.isLocked)        // Druck bleibt möglich

        // Drucker hält die TSE nach dem Abbruch fest → belegt, aber sicher nicht ausgeführt
        try {
            t.operate(storageInfo)
            fail("TSE sollte noch belegt sein")
        } catch (e: TseResultUnknownException) {
            fail("belegt ist kein unbekanntes Ergebnis")
        } catch (e: TseTransportException) {
            assertTrue(e.message!!.contains("belegt"))
        }
        assertTrue(f.escServer.rejectedEnables.get() > 0)
        assertFalse(f.printerLine.isLocked)

        f.advance(61_000)
        assertTrue(t.operate(storageInfo).contains("EXECUTION_OK"))
        t.close()
    }

    @Test fun noAnswerIsUnknown() = runBlocking<Unit> {
        val f = fixture()
        val t = f.escPosTransport()
        f.escServer.injectFault(Fault.NO_ANSWER, "GetStorageInfo")
        try {
            t.operate(storageInfo, timeoutMs = 100)
            fail("ohne Antwort muss das Ergebnis unbekannt sein")
        } catch (e: TseResultUnknownException) {
            assertEquals("GetStorageInfo", e.function)
        }
        assertFalse(f.printerLine.isLocked)
    }

    @Test fun noTseAnswersEnableIsReportedAsNotExecuted() = runBlocking<Unit> {
        val f = fixture()
        val t = f.escPosTransport()
        f.escServer.close() // Drucker weg
        try {
            t.operate(storageInfo)
            fail()
        } catch (e: TseResultUnknownException) {
            fail("nichts gesendet – darf nicht unbekannt sein")
        } catch (e: TseTransportException) {
            // erwartet
        }
        assertFalse(f.printerLine.isLocked)
    }

    // --- Gesamter Ablauf über ESC/POS

    @Test fun saleOverEscPos() = runBlocking<Unit> {
        val f = fixture()
        val epson = f.epson()
        assertTrue(epson.status().available)
        val start = epson.startTransaction("KASSE-01")
        val fin = epson.finishTransaction("KASSE-01", start.txNumber, "Kassenbeleg-V1", "Beleg^5.00_0.00_0.00_0.00_0.00^5.00:Bar")
        assertEquals(start.txNumber, fin.txNumber)
        assertTrue(EpsonTse.isMockSerial(fin.serial))
        assertTrue(f.mock.startedTransactions.isEmpty())
        assertFalse(f.printerLine.isLocked) // Gerät nach jeder Aktion freigegeben
        assertFalse(f.escServer.isTseEnabled)
        assertEquals(0L, f.escServer.unownedRequests.get())
    }

    @Test fun lostFinishAnswerIsRecoveredWithoutResending() = runBlocking<Unit> {
        // Sperrfenster 0: der Drucker gibt die TSE nach dem Abbruch sofort wieder her
        val f = fixture(config = MockConfig(reconnectWindowMs = 0))
        val epson = f.epson()
        val start = epson.startTransaction("KASSE-01")
        f.escServer.injectFault(Fault.DROP_AFTER_EXECUTE, "FinishTransaction")
        val fin = epson.finishTransaction("KASSE-01", start.txNumber, "Kassenbeleg-V1", "Beleg^5.00_0.00_0.00_0.00_0.00^5.00:Bar")
        assertEquals(start.txNumber, fin.txNumber)
        assertEquals(1, f.count("FinishTransaction"))
        assertEquals(1, f.count("GetLastTransactionResponse"))
    }

    @Test fun readOnlyGuardAppliesToEscPos() = runBlocking<Unit> {
        val f = fixture()
        f.writeEnabled = false
        val epson = f.epson()
        try {
            epson.startTransaction("KASSE-01")
            fail("Schreibschutz greift nicht")
        } catch (e: Exception) {
            // erwartet
        }
        assertTrue(f.blocked.isNotEmpty())
        assertEquals(0, f.count("StartTransaction"))
    }

    @Test fun tarExportOverEscPos() = runBlocking<Unit> {
        val f = fixture(config = MockConfig(exportChunkSize = 700))
        val out = ByteArrayOutputStream()
        val r = f.epson().exportTar("11111", out)
        assertEquals("ustar", String(out.toByteArray(), 257, 5))
        assertEquals(out.size().toLong(), r.bytes)
        assertEquals(1, f.count("FinalizeExport"))
        assertFalse(f.printerLine.isLocked)
    }
}
