package de.anypos

import de.anypos.tse.EpsonJson
import de.anypos.tse.MemoryClientIdStore
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
 * Schritt 4 (+ Korrektur nach Epson-Unterlagen): lesende EpsonTse unter Störungen gegen die Mock-TSE.
 * Szenariotests für Anmeldung/Zeit (Schritt 5) und Transaktionen (Schritt 6) liegen bei den jeweiligen Schritten.
 */
class EpsonTseRobustnessTest {

    private val fixtures = mutableListOf<MockTseFixture>()
    @After fun tearDown() = fixtures.forEach { it.close() }

    private fun fixture(scenario: Scenario = Scenario.OPERATIONAL) = MockTseFixture(scenario).also { fixtures += it }

    private fun storageInfoCalls(f: MockTseFixture) = f.count("GetStorageInfo")

    @Test fun deviceIsReleasedAfterEachAction() = runBlocking<Unit> {
        val f = fixture()
        val epson = f.epson()
        assertTrue(epson.status().available)
        // Eine andere Anwendung am selben Drucker (eigene Sitzung) bekommt die TSE sofort
        val other = f.epson(store = MemoryClientIdStore())
        assertTrue(other.status().available)
        assertFalse(f.server.blockedAfterDisconnect)
    }

    @Test fun dropBetweenActionsIsDetectedBeforeSending() = runBlocking<Unit> {
        val f = fixture()
        val epson = f.epson()
        assertTrue(epson.status().available)
        f.server.dropAllConnections() // z. B. App im Hintergrund eingefroren, WLAN weg
        Thread.sleep(100)

        // Abbruch fällt beim Öffnen des Geräts auf – vor dem Senden, also nicht „unbekannt“
        val lost = epson.status()
        assertFalse(lost.available)
        assertFalse(lost.message, lost.message.contains("unbekannt"))
        assertEquals(1, storageInfoCalls(f))

        // Danach neue Sitzung; die alte wird per <disconnect> beendet
        assertTrue(epson.status().available)
        assertEquals(2, storageInfoCalls(f))
        assertTrue(f.log.toString(), f.log.any { it.contains("Alte Sitzung beendet") })
    }

    @Test fun lockedByOtherApplicationUntilWindowExpires() = runBlocking<Unit> {
        val f = fixture()
        val holder = f.rawTransport(store = MemoryClientIdStore()) // andere Anwendung hält das Gerät offen …
        holder.open()
        f.server.dropAllConnections()                              // … und bricht ab
        Thread.sleep(100)

        val status = f.epson(store = MemoryClientIdStore()).status()
        assertFalse(status.available)
        assertTrue(status.message, status.message.contains("DEVICE_IN_USE"))
        assertTrue(f.server.blockedAfterDisconnect)

        // Nach Ablauf des Sperrfensters bekommt eine neue Verbindung die TSE
        f.advance(61_000)
        assertTrue(f.epson(store = MemoryClientIdStore()).status().available)
    }

    @Test fun ownSessionAfterRestartIsFreedImmediately() = runBlocking<Unit> {
        val f = fixture()
        val before = f.rawTransport()     // dieselbe App (gleiche gespeicherte client_id) …
        before.open()
        f.server.dropAllConnections()      // … stürzt ab, während das Gerät offen ist
        Thread.sleep(100)
        assertTrue(f.server.blockedAfterDisconnect)

        // Nach dem Neustart beendet die App ihre alte Sitzung per <disconnect> – keine Minute Sperre
        assertTrue(f.epson().status().available)
        assertEquals(1, f.server.disconnects.size)
    }

    @Test fun errorCodesMeanUnavailableAndNotExecuted() = runBlocking<Unit> {
        val f = fixture()
        val epson = f.epson()
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
        f.server.injectFault(Fault.NO_ANSWER, "GetStartedTransactionList")
        try { epson.startedTransactions(); fail() } catch (e: TseResultUnknownException) {
            assertEquals("GetStartedTransactionList", e.function)
        }
        assertEquals(1, f.count("GetStartedTransactionList"))
        // Danach neue Sitzung (alte per <disconnect> beendet); eine späte Antwort kann nicht mehr zugeordnet werden
        assertEquals(emptyList<Long>(), epson.startedTransactions())
        assertEquals(2, f.count("GetStartedTransactionList"))
    }

    @Test fun storageInfoWhileExporting() = runBlocking<Unit> {
        val f = fixture()
        // Export läuft (z. B. von JTL gestartet): GetStorageInfo meldet CURRENTLY_EXPORTING, liefert aber Daten (Epson-Demo)
        f.writeEnabled = true
        val t = f.guardedTransport()
        val challenge = EpsonJson.output("GetChallenge", t.operate(EpsonJson.request("GetChallenge", mapOf("userId" to "Administrator"), vendor = "TSE1")))["challenge"].toString().trim('"')
        EpsonJson.output("AuthenticateUserForAdmin", t.operate(EpsonJson.request("AuthenticateUserForAdmin",
            mapOf("userId" to "Administrator", "pin" to "11111", "hash" to de.anypos.tse.EpsonTse.hash(challenge, "EPSONKEY")), vendor = "TSE1")))
        EpsonJson.output("ArchiveExport", t.operate(EpsonJson.request("ArchiveExport", mapOf("clientId" to ""), vendor = "TSE1")))
        t.close()
        t.disconnect()

        val status = f.epson().status()
        assertTrue(status.message, status.available)
    }

    @Test fun vendorFromStorageInfoIsSent() = runBlocking<Unit> {
        val f = fixture()
        // Der Mock lehnt TSE-Aufrufe ohne passenden vendor ab ...
        val raw = f.guardedTransport()
        val wrong = raw.operate(EpsonJson.request("GetStartedTransactionList", mapOf("clientId" to "KASSE-01"), vendor = ""))
        assertEquals("JSON_ERROR_UNEXPECTED_STORAGE_VENDOR", EpsonJson.parseObject(wrong)!!["result"].toString().trim('"'))
        raw.close() // Gerät freigeben
        raw.disconnect()
        // ... EpsonTse holt vendorType vorher selbst und kommt durch
        assertEquals(emptyList<Long>(), f.epson().startedTransactions())
        assertEquals(listOf("GetStartedTransactionList", "GetStorageInfo", "GetStartedTransactionList"), f.mock.calls)
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
        repeat(3) { epson.status(); epson.startedTransactions() }
        assertTrue(f.mock.writeCalls.isEmpty())
        assertTrue(f.blocked.isEmpty())
    }
}
