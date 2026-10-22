package de.anypos

import de.anypos.tse.EpsonTse
import de.anypos.tse.MemoryClientIdStore
import de.anypos.tse.TseCapacityException
import de.anypos.tse.TseLoginException
import de.anypos.tse.TseResultUnknownException
import de.anypos.tsemock.Fault
import de.anypos.tsemock.MockConfig
import de.anypos.tsemock.Scenario
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/** Schritt 6: Transaktionen und Abgleich nach unbekanntem Ergebnis gegen die Mock-TSE. */
class EpsonTseTransactionTest {

    private val fixtures = mutableListOf<MockTseFixture>()
    @After fun tearDown() = fixtures.forEach { it.close() }

    private fun fixture(scenario: Scenario = Scenario.OPERATIONAL, config: MockConfig = MockConfig()) =
        MockTseFixture(scenario, config).also { it.writeEnabled = true; fixtures += it }

    private val processData = "Beleg^5.00_14.90_0.00_0.00_0.00^19.90:Bar"

    @Test fun startAndFinish() = runBlocking<Unit> {
        val f = fixture()
        val epson = f.epson()
        val start = epson.startTransaction("KASSE-01")
        assertEquals(1L, start.txNumber)
        assertTrue(EpsonTse.isMockSerial(start.serial))
        assertTrue(start.startTime > 0)

        val fin = epson.finishTransaction("KASSE-01", start.txNumber, "Kassenbeleg-V1", processData)
        assertEquals(start.txNumber, fin.txNumber)
        assertEquals(start.startTime, fin.startTime)
        assertTrue(fin.endTime >= fin.startTime)
        assertEquals(2L, fin.signatureCounter)
        assertEquals(64, Base64.getDecoder().decode(fin.signature).size)
        assertEquals(start.serial, fin.serial)
        assertEquals("ecdsa-plain-SHA256", fin.signatureAlgorithm)
        assertEquals("unixTime", fin.timeFormat)
        assertEquals(65, Base64.getDecoder().decode(fin.publicKey).size)
        assertTrue(String(Base64.getDecoder().decode(fin.certificate)).startsWith("-----BEGIN CERTIFICATE-----"))
        assertTrue(f.mock.startedTransactions.isEmpty())
    }

    @Test fun finishAnswerRedeliveredAfterReconnect() = runBlocking<Unit> {
        val f = fixture()
        val epson = f.epson()
        val start = epson.startTransaction("KASSE-01")
        f.server.injectFault(Fault.DROP_AFTER_EXECUTE, "FinishTransaction")
        val fin = epson.finishTransaction("KASSE-01", start.txNumber, "Kassenbeleg-V1", processData)
        assertEquals(2L, fin.signatureCounter)
        assertEquals(1, f.count("FinishTransaction"))
    }

    @Test fun finishLostBeforeExecutionIsResentAfterCheck() = runBlocking<Unit> {
        val f = fixture()
        val epson = f.epson()
        val start = epson.startTransaction("KASSE-01")
        f.server.injectFault(Fault.DROP_BEFORE_EXECUTE, "FinishTransaction")
        // Unbekannt → Transaktion laut TSE noch offen → Abschluss kam nachweislich nicht an → erneut senden
        val fin = epson.finishTransaction("KASSE-01", start.txNumber, "Kassenbeleg-V1", processData)
        assertEquals(start.txNumber, fin.txNumber)
        assertEquals(1, f.count("FinishTransaction")) // die TSE hat genau einen Abschluss ausgeführt
        assertTrue(f.mock.startedTransactions.isEmpty())
        val sequence = f.mock.calls.dropWhile { it != "GetStartedTransactionList" }
        assertEquals("FinishTransaction", sequence[1])
    }

    @Test fun finishExecutedButAnswerLostIsTakenFromLastResponse() = runBlocking<Unit> {
        val f = fixture()
        val epson = f.epson(commandTimeoutMs = 100)
        val start = epson.startTransaction("KASSE-01")
        f.server.injectFault(Fault.NO_ANSWER, "FinishTransaction")
        // Unbekannt → nicht mehr offen → GetLastTransactionResponse gehört zu dieser Transaktion → übernehmen
        val fin = epson.finishTransaction("KASSE-01", start.txNumber, "Kassenbeleg-V1", processData)
        assertEquals(2L, fin.signatureCounter)
        assertEquals(1, f.count("FinishTransaction"))
        assertEquals(1, f.count("GetLastTransactionResponse"))
    }

    @Test fun unknownStartLeavesOrphanThatReconcileCloses() = runBlocking<Unit> {
        val f = fixture()
        // JTL-POS hat am selben Drucker eine eigene offene Transaktion – die darf nie angefasst werden
        val jtl = f.epson(clientId = "JTL-POS-1", store = MemoryClientIdStore())
        val foreign = jtl.startTransaction("JTL-POS-1")

        val epson = f.epson(commandTimeoutMs = 100)
        val known = epson.startTransaction("KASSE-01")
        f.server.injectFault(Fault.NO_ANSWER, "StartTransaction")
        try { epson.startTransaction("KASSE-01"); fail() } catch (e: TseResultUnknownException) {
            assertEquals("StartTransaction", e.function)
        }
        assertEquals(3, f.mock.startedTransactions.size)

        val closed = epson.reconcile("KASSE-01", setOf(known.txNumber))
        assertEquals(listOf(3L), closed.map { it.txNumber })
        assertEquals(0L, closed.single().startTime)            // Startzeit unbekannt
        assertTrue(closed.single().signature.isNotEmpty())     // signiert, für den Abbruch-Vorgang in der Kasse
        assertEquals(setOf(foreign.txNumber, known.txNumber), f.mock.startedTransactions.toSet())
    }

    @Test fun capacityReachedIsReported() = runBlocking<Unit> {
        val f = fixture(config = MockConfig(maxStartedTransactions = 2))
        val epson = f.epson()
        repeat(2) { epson.startTransaction("KASSE-01") }
        try { epson.startTransaction("KASSE-01"); fail() } catch (_: TseCapacityException) {}
        // Abgleich gegen leere Liste bekannter Vorgänge schließt beide → wieder Platz
        assertEquals(2, epson.reconcile("KASSE-01", emptySet()).size)
        epson.startTransaction("KASSE-01")
    }

    @Test fun notRegisteredShowsAdminHint() = runBlocking<Unit> {
        val f = fixture(Scenario.JTL_TAKEOVER)
        try { f.epson().startTransaction("KASSE-01"); fail() } catch (e: TseLoginException) {
            assertTrue(e.message!!, e.message!!.contains("nicht registriert"))
        }
        assertEquals(0, f.count("SetUpForPrinter") + f.count("RegisterClient"))
    }

    @Test fun overnightStartRecovers() = runBlocking<Unit> {
        val f = fixture()
        val epson = f.epson()
        val first = epson.startTransaction("KASSE-01")
        epson.finishTransaction("KASSE-01", first.txNumber, "Kassenbeleg-V1", processData)
        f.advance(26 * MockConfig.HOUR)
        val second = epson.startTransaction("KASSE-01") // Anmeldung abgelaufen + Self-Test fällig
        assertEquals(2L, second.txNumber)
        assertTrue(f.mock.calls.contains("RunTSESelfTest"))
    }

    @Test fun finishAfterRestartHasNoStartTime() = runBlocking<Unit> {
        val f = fixture()
        val start = f.epson().startTransaction("KASSE-01")
        // neue Instanz (App-Neustart) kennt die Startzeit nicht – PosService nimmt dann die gespeicherte
        val fin = f.epson().finishTransaction("KASSE-01", start.txNumber, "Kassenbeleg-V1", processData)
        assertEquals(0L, fin.startTime)
        assertTrue(fin.endTime > 0)
    }
}
