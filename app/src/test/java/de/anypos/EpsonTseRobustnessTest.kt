package de.anypos

import de.anypos.tse.EpsonJson
import de.anypos.tse.TseResultUnknownException
import de.anypos.tsemock.Fault
import de.anypos.tsemock.Scenario
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Schritt 4: lesende EpsonTse unter Störungen gegen die Mock-TSE.
 * Szenariotests für Anmeldung/Zeit (Schritt 5) und Transaktionen (Schritt 6) kommen mit dem jeweiligen Schritt.
 */
class EpsonTseRobustnessTest {

    private val fixtures = mutableListOf<MockTseFixture>()
    @After fun tearDown() = fixtures.forEach { it.close() }

    private fun fixture(scenario: Scenario = Scenario.OPERATIONAL) = MockTseFixture(scenario).also { fixtures += it }

    private fun storageInfoCalls(f: MockTseFixture) = f.mock.calls.count { it == "GetStorageInfo" }

    @Test fun reconnectAfterDropWithoutResending() = runBlocking<Unit> {
        val f = fixture()
        val epson = f.epson()
        assertTrue(epson.status().available)
        f.server.dropAllConnections()
        Thread.sleep(100)

        // Abbruch wird erst beim nächsten Kommando bemerkt: nicht verfügbar, nichts ausgeführt
        val lost = epson.status()
        assertFalse(lost.available)
        assertTrue(lost.message, lost.message.startsWith("TSE nicht erreichbar"))
        assertEquals(1, storageInfoCalls(f))

        // Danach Wiedereinstieg per <reconnect> innerhalb des Sperrfensters
        assertTrue(epson.status().available)
        assertEquals(2, storageInfoCalls(f))
        assertTrue(f.log.toString(), f.log.any { it.contains("Wiedereinstieg erfolgreich") })
    }

    @Test fun lockedByOtherConnectionReportsDeviceInUse() = runBlocking<Unit> {
        val f = fixture()
        f.epson().status()
        f.server.dropAllConnections()
        Thread.sleep(100)

        val other = f.epson().status()
        assertFalse(other.available)
        assertTrue(other.message, other.message.contains("DEVICE_IN_USE"))
        assertTrue(f.server.blockedAfterDisconnect)

        // Nach Ablauf des Sperrfensters bekommt auch eine neue Verbindung die TSE
        f.advance(61_000)
        assertTrue(f.epson().status().available)
    }

    @Test fun errorCodesMeanUnavailableAndNotExecuted() = runBlocking<Unit> {
        val f = fixture()
        val epson = f.epson() // die TSE gehört immer genau einer Verbindung
        for (code in listOf("TSE1_ERROR_NO_TSE", "OTHER_ERROR_NO_STORAGE_FOUND", "TSE1_ERROR_IO")) {
            f.mock.inject(code, "GetStorageInfo")
            val status = epson.status()
            assertFalse(code, status.available)
            assertTrue(status.message, status.message.contains(code))
        }
        // Nach den Fehlern liefert dieselbe TSE wieder normal
        assertTrue(epson.status().available)
    }

    @Test fun noAnswerIsUnknownResult() = runBlocking<Unit> {
        val f = fixture()
        val epson = f.epson(commandTimeoutMs = 100)
        assertTrue(epson.status().available)
        f.server.injectFault(Fault.NO_ANSWER, "GetRegisteredClientList")
        try { epson.registeredClients(); fail() } catch (e: TseResultUnknownException) {
            assertEquals("GetRegisteredClientList", e.function)
        }
        assertEquals(1, f.mock.calls.count { it == "GetRegisteredClientList" })
        // Danach Wiedereinstieg; die verspätete Antwort ist längst verworfen
        assertEquals(listOf("JTL-POS-1", "KASSE-01"), epson.registeredClients())
    }

    @Test fun vendorFromStorageInfoIsSent() = runBlocking<Unit> {
        val f = fixture()
        // Der Mock lehnt TSE-Aufrufe ohne passenden vendor ab ...
        val raw = f.guardedTransport()
        val wrong = raw.operate(EpsonJson.request("GetRegisteredClientList", vendor = ""))
        assertEquals("JSON_ERROR_UNEXPECTED_STORAGE_VENDOR", EpsonJson.parseObject(wrong)!!["result"].toString().trim('"'))
        raw.close() // Gerät freigeben, sonst DEVICE_IN_USE für die nächste Verbindung
        raw.disconnect()
        // ... EpsonTse holt vendorType vorher selbst und kommt durch
        val epson = f.epson()
        assertEquals(listOf("JTL-POS-1", "KASSE-01"), epson.registeredClients())
        assertEquals(listOf("GetRegisteredClientList", "GetStorageInfo", "GetRegisteredClientList"), f.mock.calls)
    }

    @Test fun scenariosInStatus() = runBlocking<Unit> {
        assertTrue(fixture(Scenario.FRESH).epson().status().message.contains("nicht initialisiert"))

        val takeover = fixture(Scenario.JTL_TAKEOVER).epson().status().message
        assertTrue(takeover, takeover.contains("Self-Test fällig") && takeover.contains("Zeit fehlt"))

        val f = fixture()
        val epson = f.epson()
        assertTrue(epson.status().message.contains("Self-Test ok"))
        f.mock.powerCycle()
        val after = epson.status().message
        assertTrue(after, after.contains("Self-Test fällig") && after.contains("Zeit fehlt"))

        // Self-Test verfällt nach 25 h, ohne Stromausfall
        val g = fixture()
        g.advance(25 * 3_600_000L + 1)
        assertTrue(g.epson().status().message.contains("Self-Test fällig"))
    }

    @Test fun readPathNeverWrites() = runBlocking<Unit> {
        val f = fixture(Scenario.JTL_TAKEOVER)
        val epson = f.epson()
        repeat(3) { epson.status(); epson.registeredClients() }
        assertTrue(f.mock.writeCalls.isEmpty())
        assertTrue(f.blocked.isEmpty())
    }
}
