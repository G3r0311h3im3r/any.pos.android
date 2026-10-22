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
        // GetLastTransactionResponse je Client, vollständig (Spez. S. 44)
        val last = t.ok("GetLastTransactionResponse", """"clientId":"KASSE-01"""")
        assertEquals(number, last.output["transactionNumber"]!!.jsonPrimitive.long)
        assertEquals(fin.str("signature"), last.str("signature"))
        assertTrue(last.str("serialNumber").isNotEmpty())
        assertEquals("TSE1_ERROR_NO_LAST_TRANSACTION", t.call("GetLastTransactionResponse", """"clientId":"JTL-POS-1"""").result)
    }

    @Test fun authenticationErrors() {
        val t = tse()
        assertEquals("OTHER_ERROR_HOST_AUTHENTICATION_FAILED", t.loginTimeAdmin(secret = "FALSCH").result)
        // ohne vorherige Challenge
        assertEquals("OTHER_ERROR_HOST_AUTHENTICATION_FAILED",
            t.call("AuthenticateUserForTimeAdmin", """"clientId":"KASSE-01","pin":"22222","hash":"x"""").result)
        val first = t.loginTimeAdmin(pin = "0")
        assertEquals("TSE1_ERROR_AUTHENTICATION_FAILED", first.result)
        assertEquals(2L, first.output["remainingRetries"]!!.jsonPrimitive.long) // nur bei Fehlschlag (Spez. S. 26)
        assertEquals("TSE1_ERROR_AUTHENTICATION_FAILED", t.loginTimeAdmin(pin = "0").result)
        assertEquals("TSE1_ERROR_AUTHENTICATION_PIN_BLOCKED", t.loginTimeAdmin(pin = "0").result)
        assertEquals("TSE1_ERROR_AUTHENTICATION_PIN_BLOCKED", t.loginTimeAdmin().result) // auch mit richtiger PIN
        assertEquals("OTHER_ERROR_INVALID_ADMIN_USER_ID",
            t.call("AuthenticateUserForAdmin", """"userId":"admin","pin":"11111","hash":"x"""").result)
        assertEquals("OTHER_ERROR_UNAUTHENTICATED_TIME_ADMIN_USER", t.start().result)
        assertTrue(first.json["error"] != null) // error{fact, errorinfo} wie bei Epson
    }

    @Test fun successfulLoginHasNoRetriesField() {
        val t = tse()
        val ok = t.loginTimeAdmin()
        assertEquals(MockTse.EXECUTION_OK, ok.result)
        assertTrue(ok.output.isEmpty())
    }

    @Test fun takeoverFromJtl() {
        val t = tse(Scenario.JTL_TAKEOVER, MockConfig(sharedSecret = "JTLSECRET"))
        val info = t.info()
        assertFalse(info["hasPassedSelfTest"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(info["hasValidTime"]!!.jsonPrimitive.content.toBoolean())
        // Client-Liste nur mit Admin (Spez. S. 18)
        assertEquals("OTHER_ERROR_UNAUTHENTICATED_ADMIN_USER", t.call("GetRegisteredClientList").result)

        // Reihenfolge nach Guide S. 25: Anmeldung zuerst; die Anmeldung verlangt den Self-Test
        assertEquals("OTHER_ERROR_UNAUTHENTICATED_TIME_ADMIN_USER", t.start().result)
        assertEquals("TSE1_ERROR_WRONG_STATE_NEEDS_SELF_TEST_PASSED", t.loginTimeAdmin(secret = "JTLSECRET").result)
        t.ok("RunTSESelfTest")
        // Anmeldung prüft die Registrierung NICHT (Guide S. 30)
        assertEquals(MockTse.EXECUTION_OK, t.loginTimeAdmin(secret = "JTLSECRET").result)
        assertEquals("TSE1_ERROR_NO_TIME_SET", t.start().result)
        assertEquals(MockTse.EXECUTION_OK, t.updateTime().result)
        assertEquals("TSE1_ERROR_CLIENT_NOT_REGISTERED", t.start().result)

        assertEquals("OTHER_ERROR_UNAUTHENTICATED_ADMIN_USER", t.call("RegisterClient", """"clientId":"KASSE-01"""").result)
        // Standard-Secret passt nach Änderung durch JTL nicht mehr
        assertEquals("OTHER_ERROR_HOST_AUTHENTICATION_FAILED", t.loginAdmin().result)
        assertEquals(MockTse.EXECUTION_OK, t.loginAdmin(secret = "JTLSECRET").result)
        assertEquals(listOf("JTL-POS-1"), t.ok("GetRegisteredClientList").output["registeredClientIdList"]!!.jsonArray.map { it.jsonPrimitive.content })
        t.ok("RegisterClient", """"clientId":"KASSE-01"""")
        t.ok("LogOutForAdmin")
        assertEquals("OTHER_ERROR_TSE_ALREADY_SET_UP", t.call("SetUp", """"puk":"1","adminPin":"1","timeAdminPin":"1"""").result)
        assertEquals(MockTse.EXECUTION_OK, t.start().result)

        // Auch abgelehnte Aufrufe zählen: Der Schreibschutz muss sie vor der Leitung abfangen
        assertEquals(
            setOf("StartTransaction", "AuthenticateUserForTimeAdmin", "RunTSESelfTest", "UpdateTime", "GetRegisteredClientList",
                "RegisterClient", "AuthenticateUserForAdmin", "LogOutForAdmin", "SetUp"),
            t.writeCalls.toSet(),
        )
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
        // „Kasse über Nacht an“ (Guide S. 25): Anmeldung abgelaufen UND Self-Test fällig
        now += 17 * MockConfig.HOUR
        assertEquals("OTHER_ERROR_UNAUTHENTICATED_TIME_ADMIN_USER", t.start().result)
        assertEquals("TSE1_ERROR_WRONG_STATE_NEEDS_SELF_TEST_PASSED", t.loginTimeAdmin().result)
        t.ok("RunTSESelfTest")
        t.loginTimeAdmin()
        // Der Self-Test hat die Uhr zurückgesetzt
        assertEquals("TSE1_ERROR_NO_TIME_SET", t.start().result)
        t.updateTime()
        assertEquals(MockTse.EXECUTION_OK, t.start().result)
    }

    @Test fun powerCycleKeepsOpenTransactions() {
        val t = tse()
        t.loginTimeAdmin()
        val n = t.start().output["transactionNumber"]!!.jsonPrimitive.long
        t.powerCycle()
        assertEquals(listOf(n), t.ok("GetStartedTransactionList", """"clientId":"KASSE-01"""").output["startedTransactionNumberList"]!!.jsonArray.map { it.jsonPrimitive.long })
        assertEquals("OTHER_ERROR_UNAUTHENTICATED_TIME_ADMIN_USER", t.finish(n).result)
        t.ok("RunTSESelfTest")
        t.loginTimeAdmin()
        assertEquals("TSE1_ERROR_NO_TIME_SET", t.finish(n).result)
        t.updateTime()
        assertEquals(MockTse.EXECUTION_OK, t.finish(n).result)
    }

    @Test fun maxParallelTransactions() {
        val t = tse(config = MockConfig(maxStartedTransactions = 3))
        t.loginTimeAdmin()
        repeat(3) { assertEquals(MockTse.EXECUTION_OK, t.start().result) }
        assertEquals("TSE1_ERROR_MAX_PARALLEL_TRANSACTIONS", t.start().result)
    }

    @Test fun exportNeedsAdminAndBlocksTse() {
        val t = tse(config = MockConfig(exportChunkSize = 1000))
        t.loginTimeAdmin()
        t.finish(t.start().output["transactionNumber"]!!.jsonPrimitive.long)
        // Export verlangt Admin (Spez. S. 45)
        assertEquals("OTHER_ERROR_UNAUTHENTICATED_ADMIN_USER", t.call("ArchiveExport", """"clientId":"KASSE-01"""").result)
        t.loginAdmin()
        assertEquals("OTHER_ERROR_NO_EXPORT_STARTED", t.call("GetExportData").result)
        t.ok("ArchiveExport", """"clientId":"KASSE-01"""")

        // Exportmodus: alles andere wird abgewiesen, GetStorageInfo meldet den Zustand mit Daten
        assertEquals("OTHER_ERROR_CURRENTLY_EXPORTING", t.call("ArchiveExport").result)
        assertEquals("OTHER_ERROR_CURRENTLY_EXPORTING", t.start().result)
        val during = t.call("GetStorageInfo", storage = "COMMON")
        assertEquals("OTHER_ERROR_CURRENTLY_EXPORTING", during.result)
        assertTrue(during.output["tseInformation"] != null)

        val searching = t.ok("GetExportData")
        assertEquals("EXPORT_INCOMPLETE_SEARCHING", searching.str("exportStatus"))
        assertEquals("", searching.str("exportData"))
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
        assertEquals(MockTse.EXECUTION_OK, t.start().result) // Exportmodus beendet
        assertTrue("ArchiveExport" in t.writeCalls && "GetExportData" in t.writeCalls)
    }

    @Test fun certificateIsPemChain() {
        val pem = String(Base64.getDecoder().decode(tse().ok("GetLogMessageCertificate").str("logMessageCertificate")))
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----"))
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
        listOf("GetStorageInfo", "GetChallenge", "GetStartedTransactionList", "GetLastTransactionResponse", "GetLogMessageCertificate")
            .forEach { assertTrue(it, MockTse.isReadOnly(it)) }
        listOf("GetRegisteredClientList", "GetTimeOutInterval", "ArchiveExport", "GetExportData", "FinalizeExport", "CancelExport",
            "SetUpForPrinter", "StartTransaction").forEach { assertFalse(it, MockTse.isReadOnly(it)) }
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
                // Handbuch S. 78: sequence in der Antwort fest "0"; Typ laut Handbuch "operateresult"
                assertTrue(answer, answer.contains("<sequence>0</sequence>") && answer.contains("<type>operateresult</type>"))
                assertTrue(answer, answer.contains("EXECUTION_OK"))
            }
        }
    }
}
