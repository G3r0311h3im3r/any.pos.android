package de.anypos

import de.anypos.tse.EposXmlSocketTransport
import de.anypos.tse.EpsonException
import de.anypos.tse.EpsonJson
import de.anypos.tse.EpsonStorageInfo
import de.anypos.tse.EpsonTse
import de.anypos.tse.ReadOnlyGuardTransport
import de.anypos.tse.TseException
import de.anypos.tse.TseWriteBlockedException
import de.anypos.tsemock.MockConfig
import de.anypos.tsemock.MockTse
import de.anypos.tsemock.MockTseServer
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

    private val servers = mutableListOf<MockTseServer>()
    @After fun tearDown() = servers.forEach { it.close() }

    private fun mock(scenario: Scenario = Scenario.OPERATIONAL, config: MockConfig = MockConfig()): Pair<MockTse, MockTseServer> {
        val tse = MockTse(config.copy(scenario = scenario))
        return tse to MockTseServer(tse).start().also { servers += it }
    }

    private fun transport(port: Int) = EposXmlSocketTransport("127.0.0.1", port, connectTimeoutMs = 1_000, handshakeTimeoutMs = 1_000, log = {})

    private fun guarded(port: Int, write: Boolean = false, blocked: MutableList<String> = mutableListOf()) =
        ReadOnlyGuardTransport(transport(port), writeEnabled = { write }, onBlocked = { blocked += it })

    @Test fun readOnlyClassification() {
        fun ro(fn: String, input: Map<String, Any?> = emptyMap()) = ReadOnlyGuardTransport.isReadOnly(EpsonJson.request(fn, input))
        assertTrue(ro("GetStorageInfo"))
        assertTrue(ro("GetLogMessageCertificate"))
        assertTrue(ro("FinalizeExport", mapOf("deleteData" to false)))
        assertFalse(ro("FinalizeExport", mapOf("deleteData" to true)))
        assertFalse(ro("FinalizeExport", mapOf("deleteData" to "false"))) // nur echtes Boolean
        assertFalse(ro("FinalizeExport"))
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
        val (tse, server) = mock()
        val blocked = mutableListOf<String>()
        val t = guarded(server.port, blocked = blocked)
        for (fn in listOf("StartTransaction", "RegisterClient", "SetUpForPrinter", "AuthenticateUserForTimeAdmin")) {
            try { t.operate(EpsonJson.request(fn, mapOf("clientId" to "KASSE-01"), vendor = "TSE1")); fail(fn) }
            catch (e: TseWriteBlockedException) { assertEquals(fn, e.function) }
        }
        try { t.operate(EpsonJson.request("FinalizeExport", mapOf("deleteData" to true), vendor = "TSE1")); fail() }
        catch (_: TseWriteBlockedException) {}
        assertEquals(listOf("StartTransaction", "RegisterClient", "SetUpForPrinter", "AuthenticateUserForTimeAdmin", "FinalizeExport"), blocked)
        assertTrue(tse.calls.toString(), tse.calls.isEmpty())
        // Lesende Aufrufe gehen durch
        t.operate(EpsonJson.request("GetStorageInfo", common = true))
        assertEquals(listOf("GetStorageInfo"), tse.calls)
        assertTrue(tse.writeCalls.isEmpty())
    }

    @Test fun writeEnabledPassesThrough() = runBlocking<Unit> {
        val (tse, server) = mock()
        val t = guarded(server.port, write = true)
        val answer = t.operate(EpsonJson.request("RunTSESelfTest", vendor = "TSE1"))
        assertEquals(EpsonJson.EXECUTION_OK, EpsonJson.parseObject(answer)!!["result"].toString().trim('"'))
        assertEquals(listOf("RunTSESelfTest"), tse.writeCalls)
    }

    @Test fun statusOfOperationalMockIsMarked() = runBlocking<Unit> {
        val (tse, server) = mock()
        val epson = EpsonTse(guarded(server.port)) { "KASSE-01" }
        val status = epson.status()
        assertTrue(status.message, status.available)
        assertTrue(EpsonTse.isMockSerial(status.serial))
        assertEquals(64, status.serial!!.length)
        assertTrue(status.message, status.message.startsWith(EpsonTse.MOCK_NOTICE))
        assertTrue(status.message.contains("Self-Test ok") && status.message.contains("Zeit gesetzt"))
        assertTrue(epson.name.contains("MOCK"))
        assertFalse(epson.certified)
        assertEquals(listOf("JTL-POS-1", "KASSE-01"), epson.registeredClients())
        assertTrue(tse.writeCalls.isEmpty())
    }

    @Test fun takeoverScenarioShowsWhatIsMissing() = runBlocking<Unit> {
        val (_, server) = mock(Scenario.JTL_TAKEOVER)
        val epson = EpsonTse(guarded(server.port)) { "KASSE-01" }
        val info = epson.storageInfo()
        assertTrue(info.initialized)
        assertFalse(info.hasPassedSelfTest)
        assertTrue(epson.status().message.contains("Self-Test fällig"))
        assertEquals(listOf("JTL-POS-1"), epson.registeredClients())

        val (_, fresh) = mock(Scenario.FRESH)
        val status = EpsonTse(guarded(fresh.port)) { "KASSE-01" }.status()
        assertFalse(status.available)
        assertTrue(status.message.contains("nicht initialisiert"))
    }

    @Test fun transactionsStayDisabled() = runBlocking<Unit> {
        val (tse, server) = mock()
        val epson = EpsonTse(guarded(server.port, write = true)) { "KASSE-01" }
        try { epson.startTransaction("KASSE-01"); fail() } catch (e: TseException) { assertTrue(e.message!!.contains("TSE-Ausfall")) }
        assertTrue(tse.calls.isEmpty())
    }

    @Test fun unreachableHostIsUnavailable() = runBlocking<Unit> {
        val port = ServerSocket(0).use { it.localPort }
        val status = EpsonTse(guarded(port)) { "KASSE-01" }.status()
        assertFalse(status.available)
        assertTrue(status.message, status.message.startsWith("TSE nicht erreichbar"))
    }

    @Test fun errorCodeBecomesEpsonException() = runBlocking<Unit> {
        val (tse, server) = mock()
        tse.inject("TSE1_ERROR_NO_TSE", "GetRegisteredClientList")
        val epson = EpsonTse(guarded(server.port)) { "KASSE-01" }
        try { epson.registeredClients(); fail() } catch (e: EpsonException) {
            assertEquals("TSE1_ERROR_NO_TSE", e.code)
        }
    }

    @Test fun serialBase64ToHex() {
        assertEquals("4D4F434B", EpsonStorageInfo.base64ToHex("TU9DSw=="))
    }
}
