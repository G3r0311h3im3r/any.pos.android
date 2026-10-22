package de.anypos.tse

import de.anypos.tse.EpsonJson.string
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
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
    /** Verbindung per TLS? Ohne TLS ginge die TimeAdmin-PIN im Klartext über das Netz. */
    val encrypted: Boolean = true,
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

    /** `GetStorageInfo` (Storage COMMON); merkt sich `vendorType` für alle weiteren Aufrufe. */
    suspend fun storageInfo(): EpsonStorageInfo {
        val out = call("GetStorageInfo", common = true)
        val tse = out["tseInformation"] as? JsonObject
            ?: throw TseResultUnknownException("GetStorageInfo", "GetStorageInfo ohne tseInformation")
        return EpsonStorageInfo.from(tse).also { info = it }
    }

    suspend fun registeredClients(): List<String> =
        (call("GetRegisteredClientList")["registeredClientIdList"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    /** Bringt die TSE in Betriebsbereitschaft: Self-Test, TimeAdmin-Anmeldung, Zeit – jeweils nur wenn nötig. */
    suspend fun ensureReady() = session.withLock { ensureReadyLocked() }

    /**
     * Führt [block] aus; liefert die TSE einen Fehlercode, der Anmeldung, Zeit oder Self-Test betrifft, wird dieser
     * Zustand einmal hergestellt und [block] einmal wiederholt. Alle anderen Fehler gehen unverändert durch.
     */
    suspend fun <T> withRecovery(block: suspend () -> T): T = session.withLock {
        try {
            block()
        } catch (e: EpsonException) {
            when (e.code) {
                in RELOGIN -> { loggedIn = false; login() }
                in SELF_TEST -> { selfTest(); loggedIn = false; login(); updateTime() }
                NO_TIME_SET -> { if (!loggedIn) login(); updateTime() }
                else -> throw e
            }
            block()
        }
    }

    /** Zeit der TSE nachführen (z. B. regelmäßig im Betrieb); stellt Anmeldung/Self-Test bei Bedarf wieder her. */
    suspend fun syncTime() = withRecovery { updateTime() }

    override suspend fun startTransaction(clientId: String): TseStart = throw TseException(NOT_YET)

    override suspend fun finishTransaction(clientId: String, txNumber: Long, processType: String, processData: String): TseFinish =
        throw TseException(NOT_YET)

    /** Abmelden (falls angemeldet), Gerät schließen und Verbindung trennen. */
    suspend fun shutdown() {
        if (loggedIn) runCatching { call("LogOutForTimeAdmin", mapOf("clientId" to clientId())) }
        loggedIn = false
        runCatching { transport.close() }
        runCatching { transport.disconnect() }
    }

    // --- Anmeldung, Zeit, Self-Test (Aufrufer hält [session])

    private suspend fun ensureReadyLocked() {
        val i = storageInfo()
        when (i.initializationState) {
            "INITIALIZED" -> Unit
            "UNINITIALIZED" -> throw TseLoginException("TSE ist nicht initialisiert – Einrichtung nur im Admin-Ablauf")
            else -> throw TseLoginException("TSE ist außer Betrieb (${i.initializationState})")
        }
        var needTime = !i.hasValidTime
        if (!i.hasPassedSelfTest) {
            selfTest()
            loggedIn = false
            needTime = true
        }
        if (!loggedIn) login()
        if (needTime) updateTime()
    }

    private suspend fun selfTest() {
        try {
            call("RunTSESelfTest")
        } catch (e: EpsonException) {
            if (e.code == CLIENT_NOT_REGISTERED) {
                throw TseLoginException("TSE war zuletzt an einem anderen Drucker/Host – Admin-Ablauf „TSE übernehmen“ nötig", e.code)
            }
            throw e
        }
    }

    private suspend fun login() {
        val c = credentials() ?: throw TseLoginException("TimeAdmin-PIN ist nicht eingestellt")
        if (c.id == rejected) throw TseLoginException("Zugangsdaten wurden von der TSE abgelehnt – erst PIN/Secret prüfen und neu speichern")
        val user = clientId()
        val challenge = call("GetChallenge", mapOf("userId" to user)).string("challenge")
            ?: throw TseResultUnknownException("GetChallenge", "GetChallenge ohne challenge")
        try {
            call("AuthenticateUserForTimeAdmin", mapOf("clientId" to user, "pin" to c.timeAdminPin, "hash" to hash(challenge, c.sharedSecret)))
        } catch (e: EpsonException) {
            loggedIn = false
            throw when (e.code) {
                AUTH_FAILED -> { rejected = c.id; TseLoginException("TimeAdmin-PIN falsch – keine weiteren Versuche, damit die PIN nicht gesperrt wird", e.code) }
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

    private suspend fun call(function: String, input: Map<String, Any?> = emptyMap(), common: Boolean = false): JsonObject {
        val vendor = if (common) "" else (info ?: storageInfo()).vendorType
        val answer = transport.operate(EpsonJson.request(function, input, common, vendor), commandTimeoutMs)
        return EpsonJson.output(function, answer)
    }

    companion object {
        /** Seriennummer der Mock-TSE beginnt mit den Bytes „MOCK“. */
        const val MOCK_SERIAL_PREFIX = "4D4F434B"
        const val MOCK_NOTICE = "MOCK – KEIN GÜLTIGER BELEG"
        private const val NOT_YET = "Epson-TSE: Transaktionen noch nicht freigeschaltet – Vorgang wird als TSE-Ausfall erfasst"

        private val RELOGIN = setOf("OTHER_ERROR_UNAUTHENTICATED_TIME_ADMIN_USER", "TSE1_ERROR_AUTHENTICATION_USER_NOT_LOGGED_IN")
        private val SELF_TEST = setOf("TSE1_ERROR_WRONG_STATE_NEEDS_SELF_TEST", "TSE1_ERROR_WRONG_STATE_NEEDS_SELF_TEST_PASSED")
        private const val NO_TIME_SET = "TSE1_ERROR_NO_TIME_SET"
        private const val CLIENT_NOT_REGISTERED = "TSE1_ERROR_CLIENT_NOT_REGISTERED"
        private const val AUTH_FAILED = "TSE1_ERROR_AUTHENTICATION_FAILED"
        private const val PIN_BLOCKED = "TSE1_ERROR_AUTHENTICATION_PIN_BLOCKED"
        private const val HOST_AUTH_FAILED = "OTHER_ERROR_HOST_AUTHENTICATION_FAILED"

        fun isMockSerial(serialHex: String?): Boolean = serialHex?.uppercase()?.startsWith(MOCK_SERIAL_PREFIX) == true

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
        add("Transaktionen noch nicht freigeschaltet")
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
            )
        }

        /** Epson liefert die Seriennummer Base64-codiert; Beleg und DSFinV-K erwarten Hex. */
        fun base64ToHex(value: String): String = runCatching {
            Base64.getDecoder().decode(value).joinToString("") { "%02X".format(it) }
        }.getOrDefault(value)
    }
}
