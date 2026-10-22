package de.anypos.tse

import de.anypos.tse.EpsonJson.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

/** Zugangsdaten der Kasse an der TSE. Werden nur zum Anmelden gelesen und nie protokolliert. */
class TseCredentials(val timeAdminPin: String, sharedSecret: String) {
    /** Leer = Epson-Werkseinstellung. */
    val sharedSecret: String = sharedSecret.ifEmpty { DEFAULT_SECRET }

    /** Kennung zum Wiedererkennen abgelehnter Zugangsdaten, ohne sie im Klartext zu halten. */
    internal val id: String = MessageDigest.getInstance("SHA-256")
        .digest("$timeAdminPin\u0000${this.sharedSecret}".toByteArray()).joinToString("") { "%02x".format(it) }

    override fun toString() = "TseCredentials(***)"

    companion object { const val DEFAULT_SECRET = "EPSONKEY" }
}

/** Anmeldung an der TSE nicht möglich (PIN/Secret falsch, gesperrt, Kasse nicht registriert …); nicht automatisch wiederholen. */
class TseLoginException(message: String, val code: String? = null) : TseException(message)

/**
 * Epson-TSE im Bondrucker, angesprochen über [TseTransport] (ePOS Device XML, JSON-Kommandos an `local_TSE`).
 *
 * Stand Schritt 5: lesend, dazu Self-Test, TimeAdmin-Anmeldung und Zeit setzen ([ensureReady]) sowie die
 * Wiederherstellung nach Fehlercodes ([withRecovery]). Transaktionen folgen in Schritt 6; bis dahin wird jeder
 * Vorgang als TSE-Ausfall erfasst. Der Schreibschutz liegt im Transport ([ReadOnlyGuardTransport]).
 *
 * Grundsätze:
 * - Wiederholt wird nur nach einem **Fehlercode** (sicher nicht ausgeführt), höchstens einmal je Aufruf –
 *   nie nach [TseResultUnknownException].
 * - Falsche PIN/Secret: genau ein Versuch, danach keine weiteren, bis die Zugangsdaten geändert werden
 *   (sonst sperrt die TSE die PIN nach drei Fehlversuchen).
 * - `CLIENT_NOT_REGISTERED`: kein automatisches `SetUpForPrinter`/`RegisterClient` (Admin-Ablauf, Schritt 7).
 */
class EpsonTse(
    private val transport: TseTransport,
    private val clientId: () -> String,
    private val credentials: suspend () -> TseCredentials? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    /** Wartezeit der TSE je Kommando (ePOS `<timeout>`). */
    private val commandTimeoutMs: Int = TseTransport.DEFAULT_TIMEOUT_MS,
    /** Verbindung abhörsicher (TLS oder USB-Kabel)? Sonst ginge die TimeAdmin-PIN im Klartext über das Netz. */
    val encrypted: Boolean = true,
    /** Gerät nach jeder Aktion freigeben (empfohlen, solange andere Anwendungen denselben Drucker nutzen). */
    private val releaseAfterUse: Boolean = true,
) : Tse {

    @Volatile private var info: EpsonStorageInfo? = null
    private val session = Mutex()
    @Volatile private var loggedIn = false
    @Volatile private var rejected: String? = null

    override val name: String get() = if (info?.isMock == true) "MOCK-TSE (Epson-Protokoll)" else "Epson-TSE"

    /** Erst mit Transaktionen (Schritt 6) und Abnahme an der echten TSE; eine Mock-TSE nie. */
    override val certified = false

    override suspend fun status(): TseStatus = try {
        val i = storageInfo()
        TseStatus(
            available = i.initialized,
            serial = i.serialHex,
            message = i.summary() + if (!encrypted) " · UNVERSCHLÜSSELT (ohne TLS)" else "",
            signatureCounter = i.createdSignatures,
            certificateExpiry = i.certificateExpiration,
        )
    } catch (e: TseException) {
        TseStatus(available = false, message = "TSE nicht erreichbar: ${e.message}")
    }

    /** `GetStorageInfo` (Storage COMMON); merkt sich `vendorType` für alle weiteren Aufrufe. Gibt das Gerät danach frei. */
    suspend fun storageInfo(): EpsonStorageInfo = exclusive { readStorageInfo() }

    /** Offene Transaktionen der Kasse (lesend, ohne Anmeldung). */
    suspend fun startedTransactions(): List<Long> = exclusive {
        (call("GetStartedTransactionList", mapOf("clientId" to clientId()))["startedTransactionNumberList"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.longOrNull } ?: emptyList()
    }

    /** Registrierte Clients. Verlangt eine Admin-Anmeldung (Spez. S. 18) – im Admin-Ablauf über [asAdmin]. */
    suspend fun registeredClients(): List<String> = exclusive { readClients() }

    private suspend fun readClients(): List<String> =
        (call("GetRegisteredClientList")["registeredClientIdList"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    /** Aktionen, die eine Admin-Anmeldung voraussetzen (Admin-Ablauf „TSE übernehmen“). */
    interface AdminSession {
        suspend fun registeredClients(): List<String>

        /** Registriert [clientId], falls noch nicht vorhanden. @return true, wenn tatsächlich registriert wurde. */
        suspend fun registerClient(clientId: String): Boolean

        /** Self-Test; false = TSE war zuletzt an einem anderen Drucker/Host (dann [setUpForPrinter] nötig). */
        suspend fun selfTestOnThisHost(): Boolean

        /** Bindet die TSE an diesen Drucker (Guide S. 30) – nur nach Umzug der TSE aus einem anderen Drucker. */
        suspend fun setUpForPrinter()
    }

    @Volatile private var adminRejected: String? = null

    /**
     * Admin-Sitzung: anmelden (Challenge-Hash), [block] ausführen, **immer** abmelden. Die Admin-PIN wird nur hier
     * verwendet, nie gespeichert und nie protokolliert. Eine abgelehnte PIN wird kein zweites Mal gesendet
     * (die TSE sperrt sie nach drei Fehlversuchen).
     */
    suspend fun <T> asAdmin(adminPin: String, block: suspend AdminSession.() -> T): T = session.withLock {
        exclusive {
            val secret = credentials()?.sharedSecret ?: TseCredentials.DEFAULT_SECRET
            val attempt = TseCredentials(adminPin, secret).id
            if (attempt == adminRejected) throw TseLoginException("Diese Admin-PIN wurde bereits abgelehnt – keine weiteren Versuche")
            val challenge = call("GetChallenge", mapOf("userId" to ADMIN_USER)).string("challenge")
                ?: throw TseResultUnknownException("GetChallenge", "GetChallenge ohne challenge")
            try {
                call("AuthenticateUserForAdmin", mapOf("userId" to ADMIN_USER, "pin" to adminPin, "hash" to hash(challenge, secret)))
            } catch (e: EpsonException) {
                val left = e.output?.get("remainingRetries")?.let { (it as? JsonPrimitive)?.longOrNull }
                throw when (e.code) {
                    AUTH_FAILED -> {
                        adminRejected = attempt
                        TseLoginException("Admin-PIN falsch${left?.let { " (noch $it Versuche)" } ?: ""} – bitte prüfen, nicht raten", e.code)
                    }
                    PIN_BLOCKED -> { adminRejected = attempt; TseLoginException("Admin-PIN ist gesperrt – Entsperren mit PUK nötig", e.code) }
                    HOST_AUTH_FAILED -> TseLoginException("Shared Secret passt nicht – unter „Shared Secret setzen“ korrigieren", e.code)
                    else -> e
                }
            }
            try {
                adminActions.block()
            } finally {
                runCatching { call("LogOutForAdmin") }
            }
        }
    }

    /** Ergebnis eines TSE-Exports: Größe und SHA-256 der geschriebenen TAR-Datei (für Protokoll und Nachweis). */
    data class TarExportResult(val bytes: Long, val sha256: String, val function: String)

    /**
     * TSE-Export (TAR nach BSI TR-03151) direkt in [out] – Daten **aller** Clients, also inkl. früherer Kassen,
     * wie für eine Prüfung nötig. [from]/[to] = Zeitraum (`ExportFilteredByPeriodOfTime`), sonst gesamter Speicher
     * (`ArchiveExport`).
     *
     * - Verlangt Admin-Anmeldung und eine gesetzte Zeit (Spez. S. 45) – die Zeit wird vorher sichergestellt.
     * - Während des Exports nimmt die TSE nichts anderes an, auch nicht von anderen Kassen am Drucker.
     * - Es werden **nie** Daten gelöscht (`FinalizeExport{deleteData:false}`).
     * - Bei jedem Fehler wird der Exportmodus mit `CancelExport` beendet, damit die TSE nicht blockiert bleibt.
     */
    suspend fun exportTar(
        adminPin: String,
        out: OutputStream,
        from: Instant? = null,
        to: Instant? = null,
        progress: (Long) -> Unit = {},
    ): TarExportResult {
        ensureReady()
        return asAdmin(adminPin) { runExport(out, from, to, progress) }
    }

    private suspend fun runExport(out: OutputStream, from: Instant?, to: Instant?, progress: (Long) -> Unit): TarExportResult {
        val function = if (from == null) "ArchiveExport" else "ExportFilteredByPeriodOfTime"
        val input = buildMap<String, Any?> {
            put("clientId", "")
            if (from != null) {
                put("startDate", from.truncatedTo(ChronoUnit.SECONDS).toString())
                put("endDate", (to ?: Instant.ofEpochMilli(clock())).truncatedTo(ChronoUnit.SECONDS).toString())
            }
        }
        call(function, input)
        var finalized = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            var searching = 0
            while (true) {
                val chunk = call("GetExportData", timeoutMs = TseTransport.LONG_TIMEOUT_MS)
                val data = chunk.string("exportData").orEmpty()
                if (data.isNotEmpty()) {
                    val bytes = Base64.getDecoder().decode(data)
                    withContext(Dispatchers.IO) { out.write(bytes) }
                    digest.update(bytes)
                    total += bytes.size
                    progress(total)
                }
                when (val status = chunk.string("exportStatus")) {
                    "EXPORT_COMPLETE" -> break
                    "EXPORT_INCOMPLETE" -> searching = 0
                    "EXPORT_INCOMPLETE_SEARCHING" -> {
                        // TSE sucht die Daten noch zusammen (Spez. S. 51) – kurz warten, aber nicht endlos
                        if (++searching > MAX_SEARCH_POLLS) throw TseException("TSE-Export: Suche dauert zu lange")
                        delay(SEARCH_POLL_MS)
                    }
                    else -> throw TseException("TSE-Export: unbekannter Status „$status“")
                }
            }
            withContext(Dispatchers.IO) { out.flush() }
            call("FinalizeExport", mapOf("deleteData" to false))
            finalized = true
            return TarExportResult(total, digest.digest().joinToString("") { "%02x".format(it) }, function)
        } finally {
            if (!finalized) runCatching { call("CancelExport") }
        }
    }

    private val adminActions = object : AdminSession {
        override suspend fun registeredClients() = readClients()

        override suspend fun registerClient(clientId: String): Boolean {
            validateClientId(clientId)
            if (clientId in readClients()) return false
            call("RegisterClient", mapOf("clientId" to clientId))
            return true
        }

        override suspend fun selfTestOnThisHost(): Boolean = try {
            selfTest()
            true
        } catch (e: TseLoginException) {
            if (e.code != CLIENT_NOT_REGISTERED) throw e
            false
        }

        override suspend fun setUpForPrinter() {
            call("SetUpForPrinter", timeoutMs = TseTransport.LONG_TIMEOUT_MS)
            loggedIn = false
        }
    }

    /** Bringt die TSE in Betriebsbereitschaft: Self-Test, TimeAdmin-Anmeldung, Zeit – jeweils nur wenn nötig. */
    suspend fun ensureReady() = session.withLock { exclusive { ensureReadyLocked() } }

    /**
     * Führt [block] aus; liefert die TSE einen Fehlercode, der Anmeldung, Zeit oder Self-Test betrifft, wird dieser
     * Zustand hergestellt und [block] **einmal** wiederholt. Ein Fehlercode heißt: sicher nicht ausgeführt – nach
     * „Ergebnis unbekannt“ wird nie wiederholt. Alle anderen Fehler gehen unverändert durch.
     */
    suspend fun <T> withRecovery(block: suspend () -> T): T = session.withLock { exclusive { recovering(block) } }

    /** Wie [withRecovery], Aufrufer hält [session]. */
    private suspend fun <T> recovering(block: suspend () -> T): T = try {
        block()
    } catch (e: EpsonException) {
        if (e.code !in RECOVERABLE) throw e
        restore(e.code)
        block()
    }

    /** Zeit der TSE nachführen (z. B. regelmäßig im Betrieb); stellt Anmeldung/Self-Test bei Bedarf wieder her. */
    suspend fun syncTime() = withRecovery { updateTime() }

    /** Startzeiten laufender Transaktionen (aus `logTime`), für [TseFinish.startTime]. */
    private val startTimes = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    override suspend fun startTransaction(clientId: String): TseStart = session.withLock {
        exclusive {
            prepare()
            val out = try {
                recovering {
                    call("StartTransaction", mapOf("clientId" to clientId, "processData" to "", "processType" to ProcessData.PROCESS_TYPE, "additionalData" to ""))
                }
            } catch (e: EpsonException) {
                throw when (e.code) {
                    CLIENT_NOT_REGISTERED -> TseLoginException("Kasse „$clientId“ ist an der TSE nicht registriert – Admin-Ablauf „TSE übernehmen“ nötig", e.code)
                    MAX_PARALLEL -> TseCapacityException("TSE: zu viele offene Transaktionen (${e.code})")
                    else -> e
                }
            }
            val number = out.long("transactionNumber") ?: throw TseResultUnknownException("StartTransaction", "StartTransaction ohne transactionNumber")
            val start = parseLogTime(out.string("logTime"))
            startTimes[number] = start
            TseStart(number, start, info?.serialHex.orEmpty())
        }
    }

    /**
     * Abschluss mit processData (Base64 von UTF-8). Bei unbekanntem Ergebnis wird **nicht** blind wiederholt, sondern
     * der Zustand der TSE geprüft: Ist die Transaktion noch offen, kam der Abschluss nachweislich nicht an und darf
     * erneut gesendet werden; sonst werden die Signaturdaten aus `GetLastTransactionResponse` übernommen, sofern sie
     * zu dieser Transaktion gehören.
     */
    override suspend fun finishTransaction(clientId: String, txNumber: Long, processType: String, processData: String): TseFinish =
        session.withLock {
            exclusive {
                prepare()
                val encoded = Base64.getEncoder().encodeToString(processData.toByteArray(Charsets.UTF_8))
                val finish = suspend {
                    call("FinishTransaction", mapOf(
                        "transactionNumber" to txNumber, "clientId" to clientId, "processData" to encoded,
                        "processType" to processType, "additionalData" to "",
                    ))
                }
                val out = try {
                    recovering(finish)
                } catch (e: TseResultUnknownException) {
                    resolveUnknownFinish(clientId, txNumber, e) ?: recovering(finish)
                }
                buildFinish(txNumber, out)
            }
        }

    /** @return Signaturdaten, wenn der Abschluss erfolgt ist; null, wenn er nachweislich nicht ankam (erneut senden). */
    private suspend fun resolveUnknownFinish(clientId: String, txNumber: Long, unknown: TseResultUnknownException): JsonObject? {
        val open = try { startedList(clientId) } catch (e: TseException) { throw unknown }
        if (txNumber in open) return null
        val last = try { call("GetLastTransactionResponse", mapOf("clientId" to clientId)) } catch (e: TseException) { throw unknown }
        if (last.long("transactionNumber") == txNumber) return last
        throw unknown
    }

    override suspend fun reconcile(clientId: String, knownOpen: Set<Long>): List<TseFinish> = session.withLock {
        exclusive {
            val orphans = startedList(clientId).filter { it !in knownOpen }
            if (orphans.isEmpty()) return@exclusive emptyList()
            prepare()
            val abort = Base64.getEncoder().encodeToString(ORPHAN_PROCESS_DATA.toByteArray(Charsets.UTF_8))
            orphans.map { number ->
                val out = recovering {
                    call("FinishTransaction", mapOf(
                        "transactionNumber" to number, "clientId" to clientId, "processData" to abort,
                        "processType" to ProcessData.PROCESS_TYPE, "additionalData" to "",
                    ))
                }
                startTimes.remove(number)
                buildFinish(number, out).copy(startTime = 0)
            }
        }
    }

    /** Erst bei Bedarf: Self-Test, Anmeldung, Zeit (danach übernimmt [recovering] Ablauf und Störungen). */
    private suspend fun prepare() { if (!loggedIn) ensureReadyLocked() }

    private suspend fun startedList(clientId: String): List<Long> =
        (call("GetStartedTransactionList", mapOf("clientId" to clientId))["startedTransactionNumberList"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.longOrNull } ?: emptyList()

    private suspend fun buildFinish(txNumber: Long, out: JsonObject): TseFinish {
        val i = info ?: readStorageInfo()
        return TseFinish(
            txNumber = txNumber,
            startTime = startTimes.remove(txNumber) ?: 0, // nach Neustart unbekannt → PosService nimmt die gespeicherte
            endTime = parseLogTime(out.string("logTime")),
            signatureCounter = out.long("signatureCounter") ?: throw TseResultUnknownException("FinishTransaction", "Antwort ohne signatureCounter"),
            signature = out.string("signature") ?: throw TseResultUnknownException("FinishTransaction", "Antwort ohne signature"),
            serial = i.serialHex.orEmpty(),
            signatureAlgorithm = SIGNATURE_ALGORITHM,
            timeFormat = TIME_FORMAT,
            publicKey = i.publicKeyBase64.orEmpty(),
            certificate = certificate(),
        )
    }

    @Volatile private var certificateCache: String? = null

    /** `GetLogMessageCertificate` (lesend), einmal je Sitzung; Base64 der PEM-Kette, wie für die DSFinV-K abgelegt. */
    private suspend fun certificate(): String = certificateCache ?: try {
        call("GetLogMessageCertificate").string("logMessageCertificate").orEmpty().also { certificateCache = it }
    } catch (e: TseException) {
        "" // nicht kritisch für den Beleg; wird beim nächsten Abschluss erneut versucht
    }

    /** Abmelden (falls angemeldet), Gerät schließen und Sitzung beenden (`<disconnect>`). */
    suspend fun shutdown() {
        if (loggedIn) runCatching { call("LogOutForTimeAdmin", mapOf("clientId" to clientId())) }
        loggedIn = false
        runCatching { transport.disconnect() }
    }

    // --- Anmeldung, Zeit, Self-Test (Aufrufer hält [session])

    /**
     * Das Gerät gehört exklusiv der Verbindung, die es geöffnet hat (Handbuch S. 11, Guide S. 6). Nach jeder Aktion
     * wird es wieder freigegeben, damit andere Anwendungen am selben Drucker (z. B. JTL-POS) nicht blockiert werden;
     * die Anmeldung an der TSE bleibt dabei bestehen.
     */
    private suspend fun <T> exclusive(block: suspend () -> T): T = try {
        block()
    } finally {
        if (releaseAfterUse) runCatching { transport.close() }
    }

    private suspend fun readStorageInfo(): EpsonStorageInfo {
        // Laut Epson-Demo liefert GetStorageInfo auch in diesen Zuständen gültige Daten
        val out = call("GetStorageInfo", common = true, alsoAccept = setOf(CURRENTLY_EXPORTING, ALREADY_SET_UP))
        val tse = out["tseInformation"] as? JsonObject
            ?: throw TseResultUnknownException("GetStorageInfo", "GetStorageInfo ohne tseInformation")
        return EpsonStorageInfo.from(tse).also { info = it }
    }

    private suspend fun ensureReadyLocked() {
        val i = readStorageInfo()
        when (i.initializationState) {
            "INITIALIZED" -> Unit
            "UNINITIALIZED" -> throw TseLoginException("TSE ist nicht initialisiert – Einrichtung nur im Admin-Ablauf")
            else -> throw TseLoginException("TSE ist außer Betrieb (${i.initializationState})")
        }
        if (i.isTseUnlocked == false) throw TseLoginException("TSE ist gesperrt (LockTSE) – Entsperren im Admin-Ablauf")
        if (!i.hasPassedSelfTest) {
            selfTest()
            login()
            updateTime() // der Self-Test setzt die TSE-Uhr zurück (Guide S. 21, 29)
            return
        }
        if (!loggedIn) login()
        if (!i.hasValidTime) updateTime()
    }

    /** Stellt nach einem Fehlercode den nötigen Zustand her; auch verschachtelt (Anmeldung → Self-Test, Guide S. 25). */
    private suspend fun restore(code: String) {
        when (code) {
            in SELF_TEST -> { selfTest(); login(); updateTime() }
            NO_TIME_SET -> { if (!loggedIn) relogin(); updateTime() }
            else -> relogin() // Anmeldung abgelaufen
        }
    }

    /** Neu anmelden; verlangt die TSE dabei einen Self-Test, wird er ausgeführt und danach die Zeit gesetzt. */
    private suspend fun relogin() {
        try {
            login()
        } catch (e: EpsonException) {
            if (e.code !in SELF_TEST) throw e
            selfTest()
            login()
            updateTime()
        }
    }

    private suspend fun selfTest() {
        loggedIn = false
        try {
            call("RunTSESelfTest", timeoutMs = TseTransport.LONG_TIMEOUT_MS)
        } catch (e: EpsonException) {
            if (e.code == CLIENT_NOT_REGISTERED) {
                throw TseLoginException("TSE war zuletzt an einem anderen Drucker/Host – Admin-Ablauf „TSE übernehmen“ nötig", e.code)
            }
            throw e
        }
    }

    /**
     * TimeAdmin-Anmeldung (userId = Client-ID). Achtung: Die TSE prüft hier nicht, ob die Kasse registriert ist
     * (Guide S. 30) – das zeigt sich erst bei StartTransaction.
     */
    private suspend fun login() {
        loggedIn = false
        val c = credentials() ?: throw TseLoginException("TimeAdmin-PIN ist nicht eingestellt")
        if (c.id == rejected) throw TseLoginException("Zugangsdaten wurden von der TSE abgelehnt – erst PIN/Secret prüfen und neu speichern")
        val user = clientId()
        val challenge = call("GetChallenge", mapOf("userId" to user)).string("challenge")
            ?: throw TseResultUnknownException("GetChallenge", "GetChallenge ohne challenge")
        try {
            call("AuthenticateUserForTimeAdmin", mapOf("clientId" to user, "pin" to c.timeAdminPin, "hash" to hash(challenge, c.sharedSecret)))
        } catch (e: EpsonException) {
            val left = e.output?.get("remainingRetries")?.let { (it as? JsonPrimitive)?.longOrNull }
            throw when (e.code) {
                AUTH_FAILED -> {
                    rejected = c.id
                    TseLoginException("TimeAdmin-PIN falsch${left?.let { " (noch $it Versuche)" } ?: ""} – keine weiteren automatischen Versuche", e.code)
                }
                PIN_BLOCKED -> { rejected = c.id; TseLoginException("TimeAdmin-PIN ist gesperrt – Entsperren mit PUK im Admin-Ablauf nötig", e.code) }
                HOST_AUTH_FAILED -> { rejected = c.id; TseLoginException("Shared Secret passt nicht (von JTL geändert?) – Secret prüfen", e.code) }
                CLIENT_NOT_REGISTERED -> TseLoginException("Kasse „$user“ ist an der TSE nicht registriert – Admin-Ablauf „TSE übernehmen“ nötig", e.code)
                else -> e
            }
        }
        loggedIn = true
    }

    /** Zeit der Kasse in UTC an die TSE (`yyyy-MM-ddTHH:mm:ssZ`). */
    private suspend fun updateTime() {
        val now = Instant.ofEpochMilli(clock()).truncatedTo(ChronoUnit.SECONDS).toString()
        call("UpdateTime", mapOf("userId" to clientId(), "newDateTime" to now, "useTimeSync" to false))
    }

    private suspend fun call(
        function: String,
        input: Map<String, Any?> = emptyMap(),
        common: Boolean = false,
        timeoutMs: Int = commandTimeoutMs,
        alsoAccept: Set<String> = emptySet(),
    ): JsonObject {
        val vendor = if (common) "" else (info ?: readStorageInfo()).vendorType
        val answer = transport.operate(EpsonJson.request(function, input, common, vendor), timeoutMs)
        return EpsonJson.output(function, answer, alsoAccept)
    }

    companion object {
        /** Seriennummer der Mock-TSE beginnt mit den Bytes „MOCK“. */
        const val MOCK_SERIAL_PREFIX = "4D4F434B"
        const val MOCK_NOTICE = "MOCK – KEIN GÜLTIGER BELEG"
        const val SIGNATURE_ALGORITHM = "ecdsa-plain-SHA256"
        /** Epson liefert logTime als ISO-Zeit; gespeichert wird Unixzeit (wie fiskaltrust für Epson). */
        const val TIME_FORMAT = "unixTime"
        private const val MAX_PARALLEL = "TSE1_ERROR_MAX_PARALLEL_TRANSACTIONS"

        /** processData für verwaiste Transaktionen: Belegabbruch ohne Beträge (DSFinV-K Kassenbeleg-V1). */
        val ORPHAN_PROCESS_DATA = "AVBelegabbruch^0.00_0.00_0.00_0.00_0.00^"

        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

        /** `logTime` (ISO, UTC) → ms. */
        fun parseLogTime(value: String?): Long = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: throw TseResultUnknownException("logTime", "Antwort ohne gültige logTime")

        private val RELOGIN = setOf("OTHER_ERROR_UNAUTHENTICATED_TIME_ADMIN_USER", "TSE1_ERROR_AUTHENTICATION_USER_NOT_LOGGED_IN")
        private val SELF_TEST = setOf("TSE1_ERROR_WRONG_STATE_NEEDS_SELF_TEST", "TSE1_ERROR_WRONG_STATE_NEEDS_SELF_TEST_PASSED")
        private const val NO_TIME_SET = "TSE1_ERROR_NO_TIME_SET"
        private val RECOVERABLE = RELOGIN + SELF_TEST + NO_TIME_SET
        private const val CURRENTLY_EXPORTING = "OTHER_ERROR_CURRENTLY_EXPORTING"
        private const val ALREADY_SET_UP = "OTHER_ERROR_TSE_ALREADY_SET_UP"
        private const val CLIENT_NOT_REGISTERED = "TSE1_ERROR_CLIENT_NOT_REGISTERED"
        private const val AUTH_FAILED = "TSE1_ERROR_AUTHENTICATION_FAILED"
        private const val PIN_BLOCKED = "TSE1_ERROR_AUTHENTICATION_PIN_BLOCKED"
        private const val HOST_AUTH_FAILED = "OTHER_ERROR_HOST_AUTHENTICATION_FAILED"

        fun isMockSerial(serialHex: String?): Boolean = serialHex?.uppercase()?.startsWith(MOCK_SERIAL_PREFIX) == true

        const val ADMIN_USER = "Administrator"
        private const val SEARCH_POLL_MS = 500L
        private const val MAX_SEARCH_POLLS = 600 // 5 Minuten

        /**
         * Client-ID nach JSON-Spezifikation (S. 16): ASN.1-PrintableString (A–Z, a–z, 0–9, Leerzeichen und ' ( ) + , - . / : = ?),
         * also u. a. kein Unterstrich; „EPSON“ ist nicht erlaubt. Längengrenze vorsichtshalber 30 Zeichen.
         */
        fun validateClientId(clientId: String) {
            val ok = clientId.isNotBlank() && clientId.length <= 30 &&
                clientId.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in " '()+,-./:=?" } &&
                !clientId.contains("EPSON", ignoreCase = true)
            if (!ok) throw TseException("Kassen-ID „$clientId“ ist für die TSE nicht zulässig (erlaubt: Buchstaben, Ziffern, Leerzeichen und ' ( ) + , - . / : = ?; kein „_“, kein „EPSON“, max. 30 Zeichen)")
        }

        /** Anmelde-Hash wie bei Epson: Base64(SHA-256(UTF-8(challenge + secret))). */
        fun hash(challenge: String, secret: String): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest((challenge + secret).toByteArray(Charsets.UTF_8)))
    }
}


/** Auszug aus `GetStorageInfo.tseInformation`, Feldnamen wie bei Epson. */
data class EpsonStorageInfo(
    val vendorType: String,
    val initializationState: String,
    val hasValidTime: Boolean,
    val hasPassedSelfTest: Boolean,
    val createdSignatures: Long?,
    val remainingSignatures: Long?,
    val startedTransactions: Long?,
    val maxStartedTransactions: Long?,
    val registeredClients: Long?,
    val maxRegisteredClients: Long?,
    val certificateExpiration: String?,
    val serialHex: String?,
    val publicKeyBase64: String?,
    val signatureAlgorithm: String?,
    val description: String?,
    val softwareVersion: String?,
    val lastExport: String?,
    /** null = Feld fehlt (Spez.: `isTSEUnlocked`). */
    val isTseUnlocked: Boolean? = null,
    /** Sekunden bis zum nächsten Pflicht-Self-Test (Spez. S. 12). */
    val timeUntilNextSelfTest: Long? = null,
) {
    val initialized get() = initializationState == "INITIALIZED"
    val isMock get() = EpsonTse.isMockSerial(serialHex)

    fun summary(): String = buildList {
        if (isMock) add(EpsonTse.MOCK_NOTICE)
        add(when (initializationState) {
            "INITIALIZED" -> "initialisiert"
            "UNINITIALIZED" -> "nicht initialisiert"
            "DECOMMISSIONED" -> "außer Betrieb genommen"
            else -> initializationState
        })
        add(if (hasPassedSelfTest) "Self-Test ok" else "Self-Test fällig")
        add(if (hasValidTime) "Zeit gesetzt" else "Zeit fehlt")
        createdSignatures?.let { add("$it Signaturen") }
        certificateExpiration?.takeIf { it.isNotEmpty() }?.let { add("Zertifikat bis ${it.take(10)}") }
    }.joinToString(" · ")

    /** Rohwerte für die Anzeige „Verbindung prüfen“ (Dokumentation Phase 2a). */
    fun details(): List<Pair<String, String>> = listOf(
        "vendorType" to vendorType,
        "tseInitializationState" to initializationState,
        "serialNumber (hex)" to serialHex.orEmpty(),
        "hasPassedSelfTest" to hasPassedSelfTest.toString(),
        "hasValidTime" to hasValidTime.toString(),
        "createdSignatures" to createdSignatures?.toString().orEmpty(),
        "remainingSignatures" to remainingSignatures?.toString().orEmpty(),
        "startedTransactions" to "${startedTransactions ?: "?"} / ${maxStartedTransactions ?: "?"}",
        "registeredClients" to "${registeredClients ?: "?"} / ${maxRegisteredClients ?: "?"}",
        "certificateExpirationDate" to certificateExpiration.orEmpty(),
        "signatureAlgorithm" to signatureAlgorithm.orEmpty(),
        "tseDescription" to description.orEmpty(),
        "softwareVersion" to softwareVersion.orEmpty(),
        "lastExportExecutedDate" to lastExport.orEmpty(),
        "isTSEUnlocked" to isTseUnlocked?.toString().orEmpty(),
        "timeUntilNextSelfTest (s)" to timeUntilNextSelfTest?.toString().orEmpty(),
    )

    companion object {
        fun from(tse: JsonObject): EpsonStorageInfo {
            fun bool(key: String) = (tse[key] as? JsonPrimitive)?.booleanOrNull ?: (tse.string(key) == "true")
            fun long(key: String) = (tse[key] as? JsonPrimitive)?.longOrNull
            return EpsonStorageInfo(
                vendorType = tse.string("vendorType").orEmpty(),
                initializationState = tse.string("tseInitializationState").orEmpty(),
                hasValidTime = bool("hasValidTime"),
                hasPassedSelfTest = bool("hasPassedSelfTest"),
                createdSignatures = long("createdSignatures"),
                remainingSignatures = long("remainingSignatures"),
                startedTransactions = long("startedTransactions"),
                maxStartedTransactions = long("maxStartedTransactions"),
                registeredClients = long("registeredClients"),
                maxRegisteredClients = long("maxRegisteredClients"),
                certificateExpiration = tse.string("certificateExpirationDate"),
                serialHex = tse.string("serialNumber")?.let(::base64ToHex),
                publicKeyBase64 = tse.string("tsePublicKey"),
                signatureAlgorithm = tse.string("signatureAlgorithm"),
                description = tse.string("tseDescription"),
                softwareVersion = tse.string("softwareVersion"),
                lastExport = tse.string("lastExportExecutedDate"),
                isTseUnlocked = (tse["isTSEUnlocked"] ?: tse["isTseUnlocked"])?.let { (it as? JsonPrimitive)?.booleanOrNull },
                timeUntilNextSelfTest = long("timeUntilNextSelfTest"),
            )
        }

        /** Epson liefert die Seriennummer Base64-codiert; Beleg und DSFinV-K erwarten Hex. */
        fun base64ToHex(value: String): String = runCatching {
            Base64.getDecoder().decode(value).joinToString("") { "%02X".format(it) }
        }.getOrDefault(value)
    }
}
