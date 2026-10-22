package de.anypos

import de.anypos.tse.EpsonException
import de.anypos.tse.EpsonJson
import de.anypos.tse.EpsonStorageInfo
import de.anypos.tse.EpsonTse
import de.anypos.tse.ReadOnlyGuardTransport
import de.anypos.tse.TseException
import de.anypos.tse.TseWriteBlockedException
import de.anypos.tsemock.Scenario
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket

/** Schritt 3: Schreibschutz und lesende EpsonTse gegen die Mock-TSE (keine echte TSE). */
class EpsonTseReadOnlyTest {

    private val fixtures = mutableListOf<MockTseFixture>()
    @After fun tearDown() = fixtures.forEach { it.close() }

    private fun fixture(scenario: Scenario = Scenario.OPERATIONAL) = MockTseFixture(scenario).also { fixtures += it }

    @Test fun readOnlyClassification() {
        fun ro(fn: String, input: Map<String, Any?> = emptyMap()) = ReadOnlyGuardTransport.isReadOnly(EpsonJson.request(fn, input))
        listOf("GetStorageInfo", "GetChallenge", "GetStartedTransactionList", "GetLastTransactionResponse", "GetLogMessageCertificate")
            .forEach { assertTrue(it, ro(it)) }
        // Admin-Rolle laut Spezifikation; ArchiveExport sperrt die TSE in den Exportmodus
        listOf("GetRegisteredClientList", "GetTimeOutInterval", "ArchiveExport", "ExportFilteredByPeriodOfTime", "GetExportData", "CancelExport")
            .forEach { assertFalse(it, ro(it)) }
        assertFalse(ro("FinalizeExport", mapOf("deleteData" to false)))
        listOf("StartTransaction", "FinishTransaction", "UpdateTime", "UpdateTimeForFirst", "RegisterClient", "SetUp",
            "SetUpForPrinter", "RegisterSecretKey", "RunTSESelfTest", "AuthenticateUserForTimeAdmin",
            "AuthenticateUserForAdmin", "DeregisterClient", "DisableSecureElement", "LockTSE")
            .forEach { assertFalse(it, ro(it)) }
        assertFalse(ReadOnlyGuardTransport.isReadOnly("kein json"))
        assertFalse(ReadOnlyGuardTransport.isReadOnly("""{"input":{}}"""))
        // "function" nur in den Eingaben zählt nicht
        assertFalse(ReadOnlyGuardTransport.isReadOnly("""{"function":"SetUp","input":{"function":"GetStorageInfo"}}"""))
    }

    @Test fun blockedCallsNeverReachTheTse() = runBlocking<Unit> {
        val f = fixture()
        val t = f.guardedTransport()
        for (fn in listOf("StartTransaction", "RegisterClient", "SetUpForPrinter", "AuthenticateUserForTimeAdmin")) {
            try { t.operate(EpsonJson.request(fn, mapOf("clientId" to "KASSE-01"), vendor = "TSE1")); fail(fn) }
            catch (e: TseWriteBlockedException) { assertEquals(fn, e.function) }
        }
        try { t.operate(EpsonJson.request("FinalizeExport", mapOf("deleteData" to true), vendor = "TSE1")); fail() }
        catch (_: TseWriteBlockedException) {}
        assertEquals(listOf("StartTransaction", "RegisterClient", "SetUpForPrinter", "AuthenticateUserForTimeAdmin", "FinalizeExport"), f.blocked)
        assertTrue(f.mock.calls.toString(), f.mock.calls.isEmpty())
        // Lesende Aufrufe gehen durch
        t.operate(EpsonJson.request("GetStorageInfo", common = true))
        assertEquals(listOf("GetStorageInfo"), f.mock.calls)
        assertTrue(f.mock.writeCalls.isEmpty())
    }

    @Test fun writeEnabledPassesThrough() = runBlocking<Unit> {
        val f = fixture()
        f.writeEnabled = true
        val answer = f.guardedTransport().operate(EpsonJson.request("RunTSESelfTest", vendor = "TSE1"))
        EpsonJson.output("RunTSESelfTest", answer)
        assertEquals(listOf("RunTSESelfTest"), f.mock.writeCalls)
    }

    @Test fun statusOfOperationalMockIsMarked() = runBlocking<Unit> {
        val f = fixture()
        val epson = f.epson()
        val status = epson.status()
        assertTrue(status.message, status.available)
        assertTrue(EpsonTse.isMockSerial(status.serial))
        assertEquals(64, status.serial!!.length)
        assertTrue(status.message, status.message.startsWith(EpsonTse.MOCK_NOTICE))
        assertTrue(status.message.contains("Self-Test ok") && status.message.contains("Zeit gesetzt"))
        assertTrue(epson.name.contains("MOCK"))
        assertFalse(epson.certified)
        assertEquals(emptyList<Long>(), epson.startedTransactions())
        assertTrue(f.mock.writeCalls.isEmpty())
    }

    @Test fun takeoverScenarioShowsWhatIsMissing() = runBlocking<Unit> {
        val epson = fixture(Scenario.JTL_TAKEOVER).epson()
        val info = epson.storageInfo()
        assertTrue(info.initialized)
        assertFalse(info.hasPassedSelfTest)
        assertTrue(epson.status().message.contains("Self-Test fällig"))
        // Client-Liste verlangt Admin – der Schreibschutz lässt sie nicht durch
        try { epson.registeredClients(); fail() } catch (_: TseWriteBlockedException) {}

        val status = fixture(Scenario.FRESH).epson().status()
        assertFalse(status.available)
        assertTrue(status.message.contains("nicht initialisiert"))
    }

    @Test fun transactionsNeedWriteAccess() = runBlocking<Unit> {
        val f = fixture()
        // Schreibschutz aktiv: Anmeldung (vor dem Start) wird abgewiesen, bevor etwas gesendet wird
        try { f.epson().startTransaction("KASSE-01"); fail() } catch (e: TseException) {
            assertTrue(e.message!!, e is TseWriteBlockedException)
        }
        assertTrue(f.mock.writeCalls.isEmpty())
        assertEquals(0, f.count("StartTransaction"))
    }

    @Test fun unreachableHostIsUnavailable() = runBlocking<Unit> {
        val port = ServerSocket(0).use { it.localPort }
        val transport = de.anypos.tse.EposXmlSocketTransport("127.0.0.1", port, connectTimeoutMs = 1_000, log = {})
        val status = EpsonTse(ReadOnlyGuardTransport(transport, { false }), { "KASSE-01" }).status()
        assertFalse(status.available)
        assertTrue(status.message, status.message.startsWith("TSE nicht erreichbar"))
    }

    @Test fun errorCodeBecomesEpsonException() = runBlocking<Unit> {
        val f = fixture()
        f.mock.inject("TSE1_ERROR_NO_TSE", "GetStartedTransactionList")
        try { f.epson().startedTransactions(); fail() } catch (e: EpsonException) {
            assertEquals("TSE1_ERROR_NO_TSE", e.code)
        }
    }

    @Test fun serialBase64ToHex() {
        assertEquals("4D4F434B", EpsonStorageInfo.base64ToHex("TU9DSw=="))
    }
}
