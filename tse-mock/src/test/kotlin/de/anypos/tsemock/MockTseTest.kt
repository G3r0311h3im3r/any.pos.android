package de.anypos.tsemock

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.time.Instant
import java.util.Base64

class MockTseTest {

    private var now = 1_760_000_000_000L
    private fun tse(scenario: Scenario = Scenario.OPERATIONAL, config: MockConfig = MockConfig()) =
        MockTse(config.copy(scenario = scenario, clock = { now }))

    private class Answer(val json: JsonObject) {
        val result get() = json["result"]!!.jsonPrimitive.content
        val output get() = json["output"]!!.jsonObject
        fun str(key: String) = output[key]!!.jsonPrimitive.content
    }

    private fun MockTse.call(fn: String, input: String = "", storage: String = "TSE"): Answer {
        val request = """{"storage":{"type":"$storage","vendor":"TSE1"},"function":"$fn","input":{$input},"compress":{"required":false,"type":""}}"""
        return Answer(Json.parseToJsonElement(handle(request)).jsonObject)
    }

    private fun MockTse.ok(fn: String, input: String = "", storage: String = "TSE"): Answer =
        call(fn, input, storage).also { assertEquals("$fn: ${it.json}", MockTse.EXECUTION_OK, it.result) }

    private fun MockTse.info() = ok("GetStorageInfo", storage = "COMMON").output["tseInformation"]!!.jsonObject

    private fun MockTse.loginTimeAdmin(clientId: String = MockConfig.POS_CLIENT, pin: String = "22222", secret: String = MockConfig.DEFAULT_SECRET): Answer {
        val challenge = ok("GetChallenge", """"userId":"$clientId"""").str("challenge")
        return call("AuthenticateUserForTimeAdmin", """"clientId":"$clientId","pin":"$pin","hash":"${MockTse.hash(challenge, secret)}"""")
    }

    private fun MockTse.loginAdmin(pin: String = "11111", secret: String = MockConfig.DEFAULT_SECRET): Answer {
        val challenge = ok("GetChallenge", """"userId":"Administrator"""").str("challenge")
        return call("AuthenticateUserForAdmin", """"userId":"Administrator","pin":"$pin","hash":"${MockTse.hash(challenge, secret)}"""")
    }

    private fun MockTse.updateTime(clientId: String = MockConfig.POS_CLIENT) =
        call("UpdateTime", """"userId":"$clientId","newDateTime":"${Instant.ofEpochMilli(now)}","useTimeSync":false""")

    private val processData = Base64.getEncoder().encodeToString("Beleg^10.00_0.00_0.00_0.00_0.00^10.00:Bar".toByteArray())
    private fun MockTse.start(clientId: String = MockConfig.POS_CLIENT) =
        call("StartTransaction", """"clientId":"$clientId","processData":"","processType":"Kassenbeleg-V1","additionalData":""""")

    private fun MockTse.finish(number: Long, clientId: String = MockConfig.POS_CLIENT) =
        call("FinishTransaction", """"transactionNumber":$number,"clientId":"$clientId","processData":"$processData","processType":"Kassenbeleg-V1","additionalData":""""")

    @Test fun storageInfoIsMarkedAsMock() {
        val t = tse()
        val info = t.info()
        val serial = Base64.getDecoder().decode(info["serialNumber"]!!.jsonPrimitive.content)
        assertEquals(32, serial.size)
        assertTrue(serial.joinToString("") { "%02X".format(it) }.startsWith("4D4F434B")) // "MOCK"
        assertEquals("TSE1", info["vendorType"]!!.jsonPrimitive.content)
        assertTrue(info["tseDescription"]!!.jsonPrimitive.content.contains("MOCK"))
        assertEquals(65, Base64.getDecoder().decode(info["tsePublicKey"]!!.jsonPrimitive.content).size)
        assertEquals("INITIALIZED", info["tseInitializationState"]!!.jsonPrimitive.content)
        // GetStorageInfo nur mit Storage COMMON
        assertEquals("JSON_ERROR_UNEXPECTED_STORAGE_TYPE", t.call("GetStorageInfo").result)
        assertEquals("UNINITIALIZED", tse(Scenario.FRESH).info()["tseInitializationState"]!!.jsonPrimitive.content)
    }

    @Test fun happyPathStartFinish() {
        val t = tse()
        assertEquals(MockTse.EXECUTION_OK, t.loginTimeAdmin().result)
        val start = t.start()
        assertEquals(MockTse.EXECUTION_OK, start.result)
        val number = start.output["transactionNumber"]!!.jsonPrimitive.long
        assertEquals(listOf(number), t.startedTransactions)
        Instant.parse(start.str("logTime")) // ISO-Zeit
        assertEquals(64, Base64.getDecoder().decode(start.str("signature")).size) // ecdsa-plain P-256

        val fin = t.finish(number)
        assertEquals(MockTse.EXECUTION_OK, fin.result)
        assertEquals(2L, fin.output["signatureCounter"]!!.jsonPrimitive.long)
        assertTrue(t.startedTransactions.isEmpty())
        assertEquals("TSE1_ERROR_TRANSACTION_NOT_STARTED", t.finish(number).result)
        assertEquals(fin.json["output"], t.ok("GetLastTransactionResponse").json["output"])
    }

    @Test fun authenticationErrors() {
        val t = tse()
        assertEquals("OTHER_ERROR_HOST_AUTHENTICATION_FAILED", t.loginTimeAdmin(secret = "FALSCH").result)
        // ohne vorherige Challenge
        assertEquals("OTHER_ERROR_HOST_AUTHENTICATION_FAILED",
            t.call("AuthenticateUserForTimeAdmin", """"clientId":"KASSE-01","pin":"22222","hash":"x"""").result)
        assertEquals("TSE1_ERROR_AUTHENTICATION_FAILED", t.loginTimeAdmin(pin = "0").result)
        assertEquals("TSE1_ERROR_AUTHENTICATION_FAILED", t.loginTimeAdmin(pin = "0").result)
        assertEquals("TSE1_ERROR_AUTHENTICATION_PIN_BLOCKED", t.loginTimeAdmin(pin = "0").result)
        assertEquals("TSE1_ERROR_AUTHENTICATION_PIN_BLOCKED", t.loginTimeAdmin().result) // auch mit richtiger PIN
        assertEquals("OTHER_ERROR_INVALID_ADMIN_USER_ID",
            t.call("AuthenticateUserForAdmin", """"userId":"admin","pin":"11111","hash":"x"""").result)
        assertEquals("OTHER_ERROR_UNAUTHENTICATED_TIME_ADMIN_USER", t.start().result)
    }

    @Test fun takeoverFromJtl() {
        val t = tse(Scenario.JTL_TAKEOVER, MockConfig(sharedSecret = "JTLSECRET"))
        val info = t.info()
        assertFalse(info["hasPassedSelfTest"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(info["hasValidTime"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(listOf("JTL-POS-1"), t.ok("GetRegisteredClientList").output["registeredClientIdList"]!!.jsonArray.map { it.jsonPrimitive.content })

        assertEquals("TSE1_ERROR_WRONG_STATE_NEEDS_SELF_TEST_PASSED", t.start().result)
        t.ok("RunTSESelfTest")
        assertEquals("TSE1_ERROR_CLIENT_NOT_REGISTERED", t.start().result)
        assertEquals("TSE1_ERROR_CLIENT_NOT_REGISTERED", t.loginTimeAdmin(secret = "JTLSECRET").result)
        assertEquals("OTHER_ERROR_UNAUTHENTICATED_ADMIN_USER", t.call("RegisterClient", """"clientId":"KASSE-01"""").result)
        // Standard-Secret passt nach Änderung durch JTL nicht mehr
        assertEquals("OTHER_ERROR_HOST_AUTHENTICATION_FAILED", t.loginAdmin().result)
        assertEquals(MockTse.EXECUTION_OK, t.loginAdmin(secret = "JTLSECRET").result)
        t.ok("RegisterClient", """"clientId":"KASSE-01"""")
        t.ok("LogOutForAdmin")
        assertEquals("OTHER_ERROR_TSE_ALREADY_SET_UP", t.call("SetUp", """"puk":"1","adminPin":"1","timeAdminPin":"1"""").result)

        assertEquals(MockTse.EXECUTION_OK, t.loginTimeAdmin(secret = "JTLSECRET").result)
        assertEquals("TSE1_ERROR_NO_TIME_SET", t.start().result)
        assertEquals(MockTse.EXECUTION_OK, t.updateTime().result)
        assertEquals(MockTse.EXECUTION_OK, t.start().result)
        // Auch abgelehnte Aufrufe zählen: Der Schreibschutz muss sie vor der Leitung abfangen
        assertEquals(
            setOf("StartTransaction", "RunTSESelfTest", "AuthenticateUserForTimeAdmin", "RegisterClient",
                "AuthenticateUserForAdmin", "LogOutForAdmin", "SetUp", "UpdateTime"),
            t.writeCalls.toSet(),
        )
        assertFalse("GetRegisteredClientList" in t.writeCalls)
    }

    @Test fun boundToOtherHostNeedsSetUpForPrinter() {
        val t = tse(Scenario.JTL_TAKEOVER, MockConfig(boundToOtherHost = true))
        assertEquals("TSE1_ERROR_CLIENT_NOT_REGISTERED", t.call("RunTSESelfTest").result)
        assertEquals("OTHER_ERROR_UNAUTHENTICATED_ADMIN_USER", t.call("SetUpForPrinter").result)
        t.loginAdmin()
        t.ok("SetUpForPrinter")
        t.ok("RunTSESelfTest")
    }

    @Test fun timeAdminAndSelfTestExpire() {
        val t = tse()
        t.loginTimeAdmin()
        assertEquals(MockTse.EXECUTION_OK, t.start().result)
        now += 8 * MockConfig.HOUR + 1
        assertEquals("OTHER_ERROR_UNAUTHENTICATED_TIME_ADMIN_USER", t.start().result)
        t.loginTimeAdmin()
        assertEquals(MockTse.EXECUTION_OK, t.start().result)
        now += 17 * MockConfig.HOUR
        assertEquals("TSE1_ERROR_WRONG_STATE_NEEDS_SELF_TEST_PASSED", t.start().result)
        t.ok("RunTSESelfTest")
        // Anmeldung von vor 17 h ist inzwischen ebenfalls abgelaufen
        assertEquals("OTHER_ERROR_UNAUTHENTICATED_TIME_ADMIN_USER", t.start().result)
        t.loginTimeAdmin()
        assertEquals(MockTse.EXECUTION_OK, t.start().result)
    }

    @Test fun powerCycleKeepsOpenTransactions() {
        val t = tse()
        t.loginTimeAdmin()
        val n = t.start().output["transactionNumber"]!!.jsonPrimitive.long
        t.powerCycle()
        assertEquals(listOf(n), t.ok("GetStartedTransactionList", """"clientId":"KASSE-01"""").output["startedTransactionNumberList"]!!.jsonArray.map { it.jsonPrimitive.long })
        assertEquals("TSE1_ERROR_WRONG_STATE_NEEDS_SELF_TEST_PASSED", t.finish(n).result)
        t.ok("RunTSESelfTest")
        assertEquals("TSE1_ERROR_NO_TIME_SET", t.finish(n).result)
        t.loginTimeAdmin()
        t.updateTime()
        assertEquals(MockTse.EXECUTION_OK, t.finish(n).result)
    }

    @Test fun maxParallelTransactions() {
        val t = tse(config = MockConfig(maxStartedTransactions = 3))
        t.loginTimeAdmin()
        repeat(3) { assertEquals(MockTse.EXECUTION_OK, t.start().result) }
        assertEquals("TSE1_ERROR_MAX_PARALLEL_TRANSACTIONS", t.start().result)
    }

    @Test fun exportInChunks() {
        val t = tse(config = MockConfig(exportChunkSize = 1000))
        t.loginTimeAdmin()
        t.finish(t.start().output["transactionNumber"]!!.jsonPrimitive.long)
        assertEquals("OTHER_ERROR_NO_EXPORT_STARTED", t.call("GetExportData").result)
        t.ok("ArchiveExport", """"clientId":"KASSE-01"""")
        assertEquals("OTHER_ERROR_CURRENTLY_EXPORTING", t.call("ArchiveExport").result)
        val tar = ByteArrayOutputStream()
        val first = t.ok("GetExportData")
        assertEquals("EXPORT_INCOMPLETE", first.str("exportStatus"))
        tar.write(Base64.getDecoder().decode(first.str("exportData")))
        assertEquals("OTHER_ERROR_ALL_DATA_NOT_EXPORTED_YET", t.call("FinalizeExport", """"deleteData":false""").result)
        while (true) {
            val chunk = t.ok("GetExportData")
            tar.write(Base64.getDecoder().decode(chunk.str("exportData")))
            if (chunk.str("exportStatus") == "EXPORT_COMPLETE") break
        }
        val bytes = tar.toByteArray()
        assertEquals(0, bytes.size % 512)
        assertEquals("ustar", String(bytes, 257, 5))
        assertTrue(String(bytes).contains("MOCK_KEIN_GUELTIGER_EXPORT.txt"))
        t.ok("FinalizeExport", """"deleteData":false""")
        assertTrue(t.writeCalls.none { it.contains("Export") }) // Export-Lesefunktionen gelten als lesend
        assertTrue(t.ok("GetLogMessageCertificate").str("logMessageCertificate").isNotEmpty())
    }

    @Test fun injectionAndUnknownFunction() {
        val t = tse()
        t.inject("TSE1_ERROR_STORE_FULL", "StartTransaction")
        t.loginTimeAdmin()
        assertEquals("TSE1_ERROR_STORE_FULL", t.start().result)
        assertTrue(t.startedTransactions.isEmpty())
        assertEquals(MockTse.EXECUTION_OK, t.start().result)
        assertEquals("JSON_ERROR_UNEXPECTED_FUNCTION", t.call("FactoryReset").result)
        assertEquals("JSON_ERROR_WRONG_JSON_FORMAT", Json.parseToJsonElement(t.handle("kein json")).jsonObject["result"]!!.jsonPrimitive.content)
    }

    @Test fun readOnlyClassification() {
        assertTrue(MockTse.isReadOnly("FinalizeExport", Json.parseToJsonElement("""{"deleteData":false}""").jsonObject))
        assertFalse(MockTse.isReadOnly("FinalizeExport", Json.parseToJsonElement("""{"deleteData":true}""").jsonObject))
        assertFalse(MockTse.isReadOnly("FinalizeExport", JsonObject(emptyMap())))
        assertFalse(MockTse.isReadOnly("SetUpForPrinter", JsonObject(emptyMap())))
        assertFalse(MockTse.isReadOnly("StartTransaction", JsonObject(emptyMap())))
    }

    /** Rohes Protokoll wie in der Praxis (ohne <sequence>, Anführungszeichen als &quot;). */
    @Test fun serverAcceptsRequestsWithoutSequence() {
        MockTseServer(tse()).start().use { server ->
            Socket("127.0.0.1", server.port).use { s ->
                val inp = s.getInputStream()
                val out = s.getOutputStream()
                fun read(): String {
                    val buf = ByteArrayOutputStream()
                    while (true) {
                        val b = inp.read()
                        require(b != -1) { "Verbindung geschlossen" }
                        if (b == 0) return buf.toString(Charsets.UTF_8.name())
                        buf.write(b)
                    }
                }
                fun send(xml: String) { out.write(xml.toByteArray()); out.write(0); out.flush() }
                assertTrue(read().contains("<client_id>sock"))
                send("<open_device><device_id>local_TSE</device_id><data><type>type_storage</type></data></open_device>")
                assertTrue(read().contains("<code>OK</code>"))
                val json = """{"storage":{"type":"COMMON","vendor":""},"function":"GetStorageInfo","input":{},"compress":{"required":false,"type":""}}"""
                send("<device_data><device_id>local_TSE</device_id><data><type>operate</type><timeout>10000</timeout>" +
                    "<requestdata>${json.replace("\"", "&quot;")}</requestdata></data></device_data>")
                val answer = read()
                assertFalse(answer.contains("<sequence>"))
                assertTrue(answer, answer.contains("EXECUTION_OK"))
            }
        }
    }
}
