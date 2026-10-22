package de.anypos.tsemock

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64

/** Fehler mit Epson-Ergebniscode; wird als `result` zurückgegeben. */
class MockTseError(val code: String) : Exception(code)

/**
 * Nachbildung der Epson-TSE auf JSON-Ebene (`requestdata` → `resultdata`), ohne Netzwerk.
 *
 * Statt einer linearen Zustandskette hält der Mock unabhängige Merkmale (initialisiert, Clients, Anmeldungen,
 * Self-Test, Zeit, offene Transaktionen, Export) – so wie die echte TSE sie in `GetStorageInfo` meldet.
 * Prüfreihenfolge und Fehlercodes folgen der Epson-Fehlerliste.
 *
 * Kennzeichnung: Seriennummer beginnt mit den Bytes „MOCK“ (hex `4D4F434B`), `tseDescription` und Zertifikat
 * tragen „MOCK“. Die Signaturen sind echte ECDSA-P-256-Signaturen eines festen Beispielschlüssels, aber
 * ausdrücklich **keine** gültigen TSE-Signaturen.
 */
class MockTse(val config: MockConfig = MockConfig()) {

    private data class StartedTx(val number: Long, val clientId: String, val startedAt: Long)
    private class Export(val data: ByteArray, var offset: Int = 0)
    private class Injection(val code: String, val function: String?)

    private val privateKey: PrivateKey
    private val certificate: X509Certificate
    /** Öffentlicher Schlüssel als unkomprimierter Punkt (04 ‖ X ‖ Y), wie in DSFinV-K erwartet. */
    val publicKey: ByteArray
    /** 32 Byte: „MOCK“ + 28 Byte SHA-256 des öffentlichen Schlüssels. */
    val serialNumber: ByteArray

    init {
        val ks = KeyStore.getInstance("PKCS12")
        MockTse::class.java.getResourceAsStream("/mock-tse.p12")!!.use { ks.load(it, KEY_PASSWORD) }
        privateKey = ks.getKey(KEY_ALIAS, KEY_PASSWORD) as PrivateKey
        certificate = ks.getCertificate(KEY_ALIAS) as X509Certificate
        val pub = certificate.publicKey as ECPublicKey
        publicKey = byteArrayOf(4) + pub.w.affineX.fixed32() + pub.w.affineY.fixed32()
        serialNumber = "MOCK".toByteArray() + sha256(publicKey).copyOf(28)
    }

    // --- Merkmale

    private var initState = if (config.scenario == Scenario.FRESH) UNINITIALIZED else INITIALIZED
    private var secret = config.sharedSecret
    private val clients = linkedSetOf<String>().apply {
        if (config.scenario != Scenario.FRESH) add(MockConfig.JTL_CLIENT)
        if (config.scenario == Scenario.OPERATIONAL) add(MockConfig.POS_CLIENT)
    }
    private var adminLoggedIn = false
    private val timeAdmins = mutableMapOf<String, Long>()           // clientId → Anmeldezeitpunkt
    private val challenges = mutableMapOf<String, String>()         // userId → offene Challenge
    private val pinFailures = mutableMapOf<String, Int>()           // "admin"/"timeAdmin" → Fehlversuche
    private var selfTestAt: Long? = if (config.scenario == Scenario.OPERATIONAL) config.clock() else null
    private var timeSetAt: Long? = if (config.scenario == Scenario.OPERATIONAL) config.clock() else null
    private var timeOffset = 0L
    private var boundToOtherHost = config.boundToOtherHost
    private val started = sortedMapOf<Long, StartedTx>()
    private var lastTxNumber = 0L
    private var signatureCounter = 0L
    private var lastTransactionResponse: JsonObject? = null
    private var export: Export? = null
    private var lastExportAt: Long? = null
    private val journal = mutableListOf<String>()
    private val injected = ArrayDeque<Injection>()
    private val callLog = mutableListOf<String>()
    private val writeLog = mutableListOf<String>()
    private val random = SecureRandom()

    // --- Steuerung für Tests und Konsole

    /** Nächster Aufruf (optional nur von [function]) liefert [code], ohne etwas zu verändern. */
    @Synchronized fun inject(code: String, function: String? = null) { injected.addLast(Injection(code, function)) }

    /** Stromausfall/Neustart des Druckers: Anmeldungen, Zeit und Self-Test verfallen; offene Transaktionen bleiben. */
    @Synchronized fun powerCycle() {
        adminLoggedIn = false
        timeAdmins.clear()
        challenges.clear()
        selfTestAt = null
        timeSetAt = null
        export = null
    }

    /** Alle empfangenen Funktionen in Reihenfolge. */
    val calls: List<String> @Synchronized get() = callLog.toList()

    /** Empfangene Funktionen, die nicht zur lesenden Liste gehören (Prüfung des Schreibschutzes). */
    val writeCalls: List<String> @Synchronized get() = writeLog.toList()

    val startedTransactions: List<Long> @Synchronized get() = started.keys.toList()
    val signatures: Long @Synchronized get() = signatureCounter
    val registeredClients: Set<String> @Synchronized get() = clients.toSet()

    // --- Verarbeitung

    @Synchronized
    fun handle(requestJson: String): String {
        val request = try {
            Json.parseToJsonElement(requestJson).jsonObject
        } catch (e: Exception) {
            return result("JSON_ERROR_WRONG_JSON_FORMAT", "")
        }
        val function = request.str("function") ?: return result("JSON_ERROR_NOT_ENOUGH_CONTENTS", "")
        val input = (request["input"] as? JsonObject) ?: JsonObject(emptyMap())
        callLog += function
        if (!isReadOnly(function, input)) writeLog += function

        injected.firstOrNull { it.function == null || it.function == function }?.let {
            injected.remove(it)
            return result(it.code, function)
        }
        return try {
            checkStorage(function, request)
            result(EXECUTION_OK, function, execute(function, input))
        } catch (e: MockTseError) {
            result(e.code, function)
        }
    }

    private fun checkStorage(function: String, request: JsonObject) {
        val storage = request["storage"] as? JsonObject ?: throw MockTseError("JSON_ERROR_NOT_ENOUGH_CONTENTS")
        val type = storage.str("type")
        if (function == "GetStorageInfo") {
            if (type != "COMMON") throw MockTseError("JSON_ERROR_UNEXPECTED_STORAGE_TYPE")
            return
        }
        if (type != "TSE") throw MockTseError("JSON_ERROR_UNEXPECTED_STORAGE_TYPE")
        // Streng: Clients müssen vendor = vendorType aus GetStorageInfo mitsenden (wie die praxiserprobte Anbindung)
        if (storage.str("vendor") != VENDOR) throw MockTseError("JSON_ERROR_UNEXPECTED_STORAGE_VENDOR")
    }

    private fun execute(fn: String, input: JsonObject): JsonObject? = when (fn) {
        "GetStorageInfo" -> storageInfo()
        "GetChallenge" -> {
            val userId = input.required("userId")
            val challenge = randomToken()
            challenges[userId] = challenge
            buildJsonObject { put("challenge", challenge) }
        }
        "AuthenticateUserForAdmin" -> {
            val userId = input.required("userId")
            if (userId != MockConfig.ADMIN_USER) throw MockTseError("OTHER_ERROR_INVALID_ADMIN_USER_ID")
            requireInitialized()
            authenticate(userId, input, "admin", config.adminPin)
            adminLoggedIn = true
            buildJsonObject { put("authenticationResult", "AUTHENTICATION_OK"); put("remainingRetries", config.maxPinAttempts) }
        }
        "AuthenticateUserForTimeAdmin" -> {
            val clientId = input.required("clientId")
            requireInitialized()
            if (clientId !in clients) throw MockTseError("TSE1_ERROR_CLIENT_NOT_REGISTERED")
            authenticate(clientId, input, "timeAdmin", config.timeAdminPin)
            timeAdmins[clientId] = config.clock()
            buildJsonObject { put("authenticationResult", "AUTHENTICATION_OK"); put("remainingRetries", config.maxPinAttempts) }
        }
        "LogOutForAdmin" -> {
            if (!adminLoggedIn) throw MockTseError("TSE1_ERROR_AUTHENTICATION_USER_NOT_LOGGED_IN")
            adminLoggedIn = false
            null
        }
        "LogOutForTimeAdmin" -> {
            val clientId = input.required("clientId")
            if (timeAdmins.remove(clientId) == null) throw MockTseError("TSE1_ERROR_AUTHENTICATION_USER_NOT_LOGGED_IN")
            null
        }
        "SetUp" -> {
            if (initState != UNINITIALIZED) throw MockTseError("OTHER_ERROR_TSE_ALREADY_SET_UP")
            input.required("puk"); input.required("adminPin"); input.required("timeAdminPin")
            initState = INITIALIZED
            null
        }
        "SetUpForPrinter" -> {
            requireAdmin()
            boundToOtherHost = false
            null
        }
        "RegisterSecretKey" -> {
            requireAdmin()
            secret = input.required("secretKey")
            null
        }
        "RunTSESelfTest" -> {
            if (boundToOtherHost) throw MockTseError("TSE1_ERROR_CLIENT_NOT_REGISTERED")
            selfTestAt = config.clock()
            null
        }
        "UpdateTime", "UpdateTimeForFirst" -> {
            val userId = input.required("userId")
            requireInitialized()
            val loggedIn = (userId == MockConfig.ADMIN_USER && adminLoggedIn) || timeAdminActive(userId)
            if (!loggedIn) throw MockTseError("OTHER_ERROR_UNAUTHENTICATED_TIME_ADMIN_USER")
            val time = parseTime(input.required("newDateTime"))
            timeOffset = time - config.clock()
            timeSetAt = config.clock()
            null
        }
        "RegisterClient" -> {
            requireAdmin()
            val clientId = input.required("clientId")
            if (clients.size >= config.maxRegisteredClients && clientId !in clients) {
                throw MockTseError("TSE1_ERROR_MAX_REGISTERED_CLIENTS_REACHED")
            }
            clients += clientId
            null
        }
        "DeregisterClient" -> {
            requireAdmin()
            val clientId = input.required("clientId")
            if (clientId !in clients) throw MockTseError("TSE1_ERROR_CLIENT_NOT_REGISTERED")
            if (started.values.any { it.clientId == clientId }) throw MockTseError("TSE1_ERROR_CLIENT_HAS_UNFINISHED_TRANSACTIONS")
            clients -= clientId
            timeAdmins -= clientId
            null
        }
        "GetRegisteredClientList" -> buildJsonObject { putJsonArray("registeredClientIdList") { clients.forEach { add(JsonPrimitive(it)) } } }
        "StartTransaction" -> {
            val clientId = input.required("clientId")
            requireOperational(clientId)
            if (started.size >= config.maxStartedTransactions) throw MockTseError("TSE1_ERROR_MAX_PARALLEL_TRANSACTIONS")
            val processData = decodeProcessData(input)
            val number = ++lastTxNumber
            started[number] = StartedTx(number, clientId, config.clock())
            val (logTime, sig) = sign(number, "StartTransaction", clientId, input, processData)
            buildJsonObject {
                put("transactionNumber", number)
                put("logTime", logTime)
                put("serialNumber", b64(serialNumber))
                put("signatureCounter", signatureCounter)
                put("signature", sig)
            }.also { lastTransactionResponse = it }
        }
        "UpdateTransaction", "FinishTransaction" -> {
            val clientId = input.required("clientId")
            val number = input.long("transactionNumber") ?: throw MockTseError("JSON_ERROR_NOT_ENOUGH_CONTENTS")
            requireOperational(clientId)
            val tx = started[number]
            if (tx == null || tx.clientId != clientId) throw MockTseError("TSE1_ERROR_TRANSACTION_NOT_STARTED")
            val processData = decodeProcessData(input)
            if (fn == "FinishTransaction") started.remove(number)
            val (logTime, sig) = sign(number, fn, clientId, input, processData)
            buildJsonObject {
                put("logTime", logTime)
                put("signatureCounter", signatureCounter)
                put("signature", sig)
            }.also { lastTransactionResponse = it }
        }
        "GetStartedTransactionList" -> {
            val clientId = input.str("clientId").orEmpty()
            buildJsonObject {
                putJsonArray("startedTransactionNumberList") {
                    started.values.filter { clientId.isEmpty() || it.clientId == clientId }.forEach { add(JsonPrimitive(it.number)) }
                }
            }
        }
        "GetLastTransactionResponse" -> lastTransactionResponse ?: throw MockTseError("TSE1_ERROR_NO_LAST_TRANSACTION")
        "GetLogMessageCertificate" -> buildJsonObject { put("logMessageCertificate", b64(certificate.encoded)) }
        "ArchiveExport", "ExportFilteredByTransactionNumber", "ExportFilteredByTransactionNumberInterval",
        "ExportFilteredByPeriodOfTime" -> {
            if (export != null) throw MockTseError("OTHER_ERROR_CURRENTLY_EXPORTING")
            if (initState == UNINITIALIZED) throw MockTseError("TSE1_ERROR_TSE_NOT_INITIALIZED")
            export = Export(exportTar(fn))
            null
        }
        "GetExportData" -> {
            val e = export ?: throw MockTseError("OTHER_ERROR_NO_EXPORT_STARTED")
            val end = minOf(e.offset + config.exportChunkSize, e.data.size)
            val chunk = e.data.copyOfRange(e.offset, end)
            e.offset = end
            buildJsonObject {
                put("exportData", b64(chunk))
                put("exportStatus", if (end == e.data.size) "EXPORT_COMPLETE" else "EXPORT_INCOMPLETE")
            }
        }
        "FinalizeExport" -> {
            val e = export ?: throw MockTseError("OTHER_ERROR_NO_EXPORT_STARTED")
            if (e.offset < e.data.size) throw MockTseError("OTHER_ERROR_ALL_DATA_NOT_EXPORTED_YET")
            val delete = (input["deleteData"] as? JsonPrimitive)?.booleanOrNull ?: false
            if (delete) {
                requireAdmin()
                journal.clear()
            }
            export = null
            lastExportAt = config.clock()
            null
        }
        "CancelExport" -> {
            if (export == null) throw MockTseError("OTHER_ERROR_NO_EXPORT_STARTED")
            export = null
            null
        }
        else -> throw MockTseError("JSON_ERROR_UNEXPECTED_FUNCTION")
    }

    // --- Prüfungen

    private fun authenticate(userId: String, input: JsonObject, role: String, pin: String) {
        val challenge = challenges.remove(userId) ?: throw MockTseError("OTHER_ERROR_HOST_AUTHENTICATION_FAILED")
        if (input.required("hash") != hash(challenge, secret)) throw MockTseError("OTHER_ERROR_HOST_AUTHENTICATION_FAILED")
        val failures = pinFailures[role] ?: 0
        if (failures >= config.maxPinAttempts) throw MockTseError("TSE1_ERROR_AUTHENTICATION_PIN_BLOCKED")
        if (input.required("pin") != pin) {
            pinFailures[role] = failures + 1
            throw MockTseError(if (failures + 1 >= config.maxPinAttempts) "TSE1_ERROR_AUTHENTICATION_PIN_BLOCKED" else "TSE1_ERROR_AUTHENTICATION_FAILED")
        }
        pinFailures[role] = 0
    }

    private fun requireInitialized() {
        when (initState) {
            UNINITIALIZED -> throw MockTseError("TSE1_ERROR_TSE_NOT_INITIALIZED")
            DECOMMISSIONED -> throw MockTseError("TSE1_ERROR_TSE_DECOMMISSIONED")
        }
    }

    private fun requireAdmin() {
        requireInitialized()
        if (!adminLoggedIn) throw MockTseError("OTHER_ERROR_UNAUTHENTICATED_ADMIN_USER")
    }

    private fun timeAdminActive(clientId: String): Boolean {
        val since = timeAdmins[clientId] ?: return false
        if (config.clock() - since > config.timeAdminTimeoutMs) {
            timeAdmins -= clientId
            return false
        }
        return true
    }

    private fun selfTestPassed(): Boolean = selfTestAt?.let { config.clock() - it <= config.selfTestIntervalMs } == true

    private fun hasValidTime(): Boolean {
        val set = timeSetAt ?: return false
        return config.timeValidityMs?.let { config.clock() - set <= it } ?: true
    }

    /** Reihenfolge wie die echte TSE: Initialisierung → Self-Test → Client → Zeit → Anmeldung. */
    private fun requireOperational(clientId: String) {
        requireInitialized()
        if (!selfTestPassed()) throw MockTseError("TSE1_ERROR_WRONG_STATE_NEEDS_SELF_TEST_PASSED")
        if (clientId !in clients) throw MockTseError("TSE1_ERROR_CLIENT_NOT_REGISTERED")
        if (!hasValidTime()) throw MockTseError("TSE1_ERROR_NO_TIME_SET")
        if (!timeAdminActive(clientId)) throw MockTseError("OTHER_ERROR_UNAUTHENTICATED_TIME_ADMIN_USER")
    }

    private fun decodeProcessData(input: JsonObject): ByteArray {
        input.required("processType")
        val raw = input.str("processData").orEmpty()
        return try { Base64.getDecoder().decode(raw) } catch (e: IllegalArgumentException) {
            throw MockTseError("JSON_ERROR_INVALID_PARAMETER")
        }
    }

    // --- Signatur, Zeit, Export

    private fun tseNow(): Long = config.clock() + timeOffset

    /** Signiert die Log-Nachricht (vereinfacht) und erhöht den Signaturzähler. */
    private fun sign(number: Long, operation: String, clientId: String, input: JsonObject, processData: ByteArray): Pair<String, String> {
        if (config.signatureLatencyMs > 0) Thread.sleep(config.signatureLatencyMs)
        signatureCounter++
        val logTime = ISO.format(Instant.ofEpochMilli(tseNow()).truncatedTo(ChronoUnit.SECONDS))
        val message = "$operation|$clientId|$number|$signatureCounter|$logTime|${input.str("processType")}|${b64(processData)}"
        val signature = Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initSign(privateKey)
            update(message.toByteArray())
            sign()
        }
        journal += "$message|${b64(signature)}"
        return logTime to b64(signature)
    }

    /** Kleines, gültiges TAR mit Hinweis und Journal – Inhalt ist kein TR-03151-Export. */
    private fun exportTar(function: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun entry(name: String, content: ByteArray) {
            val header = ByteArray(512)
            fun field(offset: Int, value: String) = value.toByteArray().copyInto(header, offset)
            field(0, name)
            field(100, "0000644\u0000")
            field(108, "0000000\u0000")
            field(116, "0000000\u0000")
            field(124, "%011o\u0000".format(content.size))
            field(136, "%011o\u0000".format(tseNow() / 1000))
            field(148, "        ")
            header[156] = '0'.code.toByte()
            field(257, "ustar\u0000")
            field(263, "00")
            field(148, "%06o\u0000 ".format(header.sumOf { it.toInt() and 0xFF }))
            out.write(header)
            out.write(content)
            out.write(ByteArray((512 - content.size % 512) % 512))
        }
        entry("MOCK_KEIN_GUELTIGER_EXPORT.txt", "Mock-TSE ($function) - keine gueltige TSE, keine gueltigen Signaturen.\n".toByteArray())
        entry("journal.log", journal.joinToString("\n", postfix = "\n").toByteArray())
        entry("certificate.der", certificate.encoded)
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun storageInfo(): JsonObject = buildJsonObject {
        putJsonObject("smartInformation") {
            putJsonObject("dataIntegrity") { put("healthStatus", "PASS"); put("uncorrectableECCErrors", 0) }
            putJsonObject("eraseLifetimeStatus") { put("healthStatus", "PASS"); put("remainingEraseCounts", 100) }
            putJsonObject("spareBlockStatus") { put("healthStatus", "PASS"); put("remainingSpareBlocks", 100) }
            put("remainingTenYearsDataRetention", 100)
            put("isReplacementNeeded", false)
            put("tseHealth", "PASS")
        }
        putJsonObject("tseInformation") {
            put("vendorType", VENDOR)
            put("tseCapacity", 8_000_000)
            put("tseCurrentSize", journal.sumOf { it.length })
            put("tseInitializationState", initState)
            put("hasValidTime", hasValidTime())
            put("hasPassedSelfTest", selfTestPassed())
            put("timeUntilNextSelfTest", selfTestAt?.let { maxOf(0, (it + config.selfTestIntervalMs - config.clock()) / 1000) } ?: 0)
            put("isExportEnabledIfCspTestFails", true)
            put("maxUpdateDelay", 45)
            put("startedTransactions", started.size)
            put("maxStartedTransactions", config.maxStartedTransactions)
            put("createdSignatures", signatureCounter)
            put("remainingSignatures", MAX_SIGNATURES - signatureCounter)
            put("maxSignatures", MAX_SIGNATURES)
            put("registeredClients", clients.size)
            put("maxRegisteredClients", config.maxRegisteredClients)
            put("certificateExpirationDate", ISO.format(certificate.notAfter.toInstant().truncatedTo(ChronoUnit.SECONDS)))
            put("tarExportSize", exportTar("GetStorageInfo").size)
            put("isTransactionInProgress", started.isNotEmpty())
            put("isTseUnlocked", true)
            put("serialNumber", b64(serialNumber))
            put("tsePublicKey", b64(publicKey))
            put("signatureAlgorithm", "ecdsa-plain-SHA256")
            put("tseDescription", "MOCK TSE - KEIN GUELTIGER BELEG")
            put("softwareVersion", 0)
            put("hardwareVersion", 0)
            put("cdcId", "MOCK")
            put("cdcHash", "")
            put("lastExportExecutedDate", lastExportAt?.let { ISO.format(Instant.ofEpochMilli(it).truncatedTo(ChronoUnit.SECONDS)) } ?: "")
        }
    }

    private fun randomToken(): String = ByteArray(12).also { random.nextBytes(it) }.let { Base64.getEncoder().encodeToString(it) }

    private fun result(code: String, function: String, output: JsonObject? = null): String = buildJsonObject {
        put("result", code)
        put("function", function)
        put("output", output ?: JsonObject(emptyMap()))
    }.toString()

    companion object {
        const val EXECUTION_OK = "EXECUTION_OK"
        const val VENDOR = "TSE1"
        const val UNINITIALIZED = "UNINITIALIZED"
        const val INITIALIZED = "INITIALIZED"
        const val DECOMMISSIONED = "DECOMMISSIONED"
        private const val MAX_SIGNATURES = 20_000_000L
        private const val KEY_ALIAS = "mock-tse"
        private val KEY_PASSWORD = "mocktse".toCharArray()
        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

        /** Funktionen, die laut TSE-Entwicklung.md bei `TSE_WRITE_ENABLED = false` passieren dürfen. */
        val READ_ONLY = setOf(
            "GetStorageInfo", "GetChallenge", "GetTimeOutInterval", "GetStartedTransactionList",
            "GetRegisteredClientList", "GetLastTransactionResponse", "GetLogMessageCertificate",
            "ArchiveExport", "GetExportData", "FinalizeExport", "CancelExport",
        )

        fun isReadOnly(function: String, input: JsonObject): Boolean =
            function in READ_ONLY &&
                (function != "FinalizeExport" || (input["deleteData"] as? JsonPrimitive)?.booleanOrNull == false)

        /** Anmelde-Hash wie bei Epson: Base64(SHA-256(UTF-8(challenge + secret))). */
        fun hash(challenge: String, secret: String): String = b64(sha256((challenge + secret).toByteArray(Charsets.UTF_8)))

        /** Zeitformat der Epson-Eingaben: `yyyy-MM-ddTHH:mm:ssZ` (UTC). */
        fun parseTime(value: String): Long = try {
            Instant.parse(value).toEpochMilli()
        } catch (e: Exception) {
            throw MockTseError("JSON_ERROR_INVALID_TIME_FORMAT")
        }

        private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
        private fun b64(data: ByteArray) = Base64.getEncoder().encodeToString(data)

        private fun BigInteger.fixed32(): ByteArray {
            val bytes = toByteArray()
            return when {
                bytes.size == 32 -> bytes
                bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
                else -> ByteArray(32 - bytes.size) + bytes
            }
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
        private fun JsonObject.required(key: String): String =
            str(key)?.takeIf { it.isNotEmpty() } ?: throw MockTseError("JSON_ERROR_NOT_ENOUGH_CONTENTS")
    }
}
