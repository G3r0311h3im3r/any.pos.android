package de.anypos

import de.anypos.tse.EposXmlSocketTransport
import de.anypos.tse.MemoryClientIdStore
import de.anypos.tse.TseResultUnknownException
import de.anypos.tse.TseTransportException
import de.anypos.tsemock.Fault
import de.anypos.tsemock.MockConfig
import de.anypos.tsemock.MockTse
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/** Rauchtest: der echte Transport der App gegen den Mock-Server aus `:tse-mock` (keine echte TSE, ohne Schreibschutz). */
class TransportAgainstMockTest {

    private val f = MockTseFixture()
    private val mock get() = f.mock

    @After fun tearDown() = f.close()

    private fun request(fn: String, input: String = "", storage: String = "TSE") =
        """{"storage":{"type":"$storage","vendor":"${if (storage == "TSE") "TSE1" else ""}"},"function":"$fn","input":{$input},"compress":{"required":false,"type":""}}"""

    private fun field(json: String, key: String) = Regex("\"$key\"\\s*:\\s*\"?([^\",}]*)").find(json)!!.groupValues[1]

    private suspend fun EposXmlSocketTransport.call(fn: String, input: String = "", storage: String = "TSE"): String =
        operate(request(fn, input, storage)).also { assertEquals("$fn: $it", "EXECUTION_OK", field(it, "result")) }

    private suspend fun EposXmlSocketTransport.login() {
        val challenge = call("GetChallenge", """"userId":"KASSE-01"""").let { field(it, "challenge") }
        call("AuthenticateUserForTimeAdmin", """"clientId":"KASSE-01","pin":"22222","hash":"${MockTse.hash(challenge, MockConfig.DEFAULT_SECRET)}"""")
    }

    private val processData = Base64.getEncoder().encodeToString("Beleg^5.00_0.00_0.00_0.00_0.00^5.00:Bar".toByteArray())
    private fun startInput() = """"clientId":"KASSE-01","processData":"","processType":"Kassenbeleg-V1","additionalData":"""""

    @Test fun saleRoundTrip() = runBlocking<Unit> {
        val t = f.rawTransport()
        val info = t.call("GetStorageInfo", storage = "COMMON")
        assertEquals("TSE1", field(info, "vendorType"))
        t.login()
        val number = field(t.call("StartTransaction", startInput()), "transactionNumber")
        t.call("FinishTransaction", """"transactionNumber":$number,"clientId":"KASSE-01","processData":"$processData","processType":"Kassenbeleg-V1","additionalData":""""")
        assertEquals(2L, mock.signatures)
        assertTrue(mock.startedTransactions.isEmpty())
        t.close()
        t.disconnect()
    }

    @Test fun tseLockedForOthersAfterDropUntilReconnect() = runBlocking<Unit> {
        val a = f.rawTransport()
        a.call("GetStorageInfo", storage = "COMMON")
        f.server.dropAllConnections()
        Thread.sleep(100)

        // Eine andere Anwendung bekommt die TSE während des Sperrfensters nicht – sicher nicht ausgeführt
        try { f.rawTransport(store = MemoryClientIdStore()).open(); fail() } catch (e: TseTransportException) {
            assertTrue(e !is TseResultUnknownException)
            assertTrue(e.message!!, e.message!!.contains("DEVICE_IN_USE"))
        }
        // Der ursprüngliche Client erkennt den Abbruch beim nächsten Kommando: Wiedereinstieg, aber keine
        // Nachlieferung (das Kommando kam nie an) → unbekannt ...
        try { a.call("GetStorageInfo", storage = "COMMON"); fail() } catch (_: TseResultUnknownException) {}
        assertTrue(f.log.toString(), f.log.any { it.contains("Wiedereinstieg erfolgreich") })
        // ... danach neue Sitzung, alte per <disconnect> beendet
        a.call("GetStorageInfo", storage = "COMMON")
    }

    @Test fun dropAfterExecuteAnswerIsRedelivered() = runBlocking<Unit> {
        val t = f.rawTransport()
        t.login()
        f.server.injectFault(Fault.DROP_AFTER_EXECUTE, "StartTransaction")
        // Antwort ging verloren, kommt aber nach dem <reconnect> nach (Handbuch S. 36) – Ergebnis bekannt
        val answer = t.operate(request("StartTransaction", startInput()))
        assertEquals("1", field(answer, "transactionNumber"))
        assertEquals(listOf(1L), mock.startedTransactions)
        assertEquals(1, mock.calls.count { it == "StartTransaction" }) // nie erneut gesendet
    }

    @Test fun dropBeforeExecuteIsAlsoUnknownButNothingStarted() = runBlocking<Unit> {
        val t = f.rawTransport()
        t.login()
        f.server.injectFault(Fault.DROP_BEFORE_EXECUTE, "StartTransaction")
        try { t.operate(request("StartTransaction", startInput())); fail() } catch (_: TseResultUnknownException) {}
        // Aus Sicht der Kasse nicht unterscheidbar – deshalb Abgleich über die Liste statt Wiederholung
        assertTrue(t.call("GetStartedTransactionList", """"clientId":"KASSE-01"""").contains("[]"))
    }
}
