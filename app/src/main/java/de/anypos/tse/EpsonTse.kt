package de.anypos.tse

import de.anypos.tse.EpsonJson.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/**
 * Epson-TSE im Bondrucker, angesprochen über [TseTransport] (ePOS Device XML, JSON-Kommandos an `local_TSE`).
 *
 * Stand Schritt 3: nur lesend (`GetStorageInfo`, `GetRegisteredClientList`). Transaktionen folgen in Schritt 6;
 * bis dahin wird jeder Vorgang wie bisher als TSE-Ausfall erfasst. Der Schreibschutz liegt im Transport
 * ([ReadOnlyGuardTransport]), nicht hier.
 */
class EpsonTse(
    private val transport: TseTransport,
    @Suppress("unused") private val clientId: () -> String,
) : Tse {

    @Volatile private var info: EpsonStorageInfo? = null

    override val name: String get() = if (info?.isMock == true) "MOCK-TSE (Epson-Protokoll)" else "Epson-TSE"

    /** Erst mit Transaktionen (Schritt 6) und Abnahme an der echten TSE; eine Mock-TSE nie. */
    override val certified = false

    override suspend fun status(): TseStatus = try {
        val i = storageInfo()
        TseStatus(
            available = i.initialized,
            serial = i.serialHex,
            message = i.summary(),
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

    override suspend fun startTransaction(clientId: String): TseStart = throw TseException(NOT_YET)

    override suspend fun finishTransaction(clientId: String, txNumber: Long, processType: String, processData: String): TseFinish =
        throw TseException(NOT_YET)

    /** Gerät schließen und Verbindung trennen (beim Wechsel von Host/Port oder Modus). */
    suspend fun shutdown() {
        runCatching { transport.close() }
        runCatching { transport.disconnect() }
    }

    private suspend fun call(function: String, input: Map<String, Any?> = emptyMap(), common: Boolean = false): JsonObject {
        val vendor = if (common) "" else (info ?: storageInfo()).vendorType
        val answer = transport.operate(EpsonJson.request(function, input, common, vendor))
        return EpsonJson.output(function, answer)
    }

    companion object {
        /** Seriennummer der Mock-TSE beginnt mit den Bytes „MOCK“. */
        const val MOCK_SERIAL_PREFIX = "4D4F434B"
        const val MOCK_NOTICE = "MOCK – KEIN GÜLTIGER BELEG"
        private const val NOT_YET = "Epson-TSE: Transaktionen noch nicht freigeschaltet – Vorgang wird als TSE-Ausfall erfasst"

        fun isMockSerial(serialHex: String?): Boolean = serialHex?.uppercase()?.startsWith(MOCK_SERIAL_PREFIX) == true
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
