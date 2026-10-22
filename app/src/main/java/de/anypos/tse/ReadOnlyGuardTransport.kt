package de.anypos.tse

import de.anypos.tse.EpsonJson.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Ein schreibender Aufruf wurde vom Schreibschutz abgefangen, bevor etwas gesendet wurde. */
class TseWriteBlockedException(val function: String) : TseException("Schreibender TSE-Aufruf gesperrt: $function")

/**
 * Schreibschutz direkt vor der Leitung (TSE-Entwicklung.md, Schutzschicht 1).
 *
 * Solange [writeEnabled] false liefert, passieren nur die lesenden Funktionen aus [READ_ONLY]; alles andere –
 * auch nicht lesbares JSON – wird mit [TseWriteBlockedException] abgewiesen, bevor der Transport es sieht.
 */
class ReadOnlyGuardTransport(
    private val inner: TseTransport,
    private val writeEnabled: () -> Boolean,
    /** Meldung für das Protokoll (AuditLog); erhält nur den Funktionsnamen. */
    private val onBlocked: suspend (String) -> Unit = {},
) : TseTransport {

    override val isConnected: Boolean get() = inner.isConnected
    override suspend fun connect() = inner.connect()
    override suspend fun open() = inner.open()
    override suspend fun close() = inner.close()
    override suspend fun disconnect() = inner.disconnect()

    override suspend fun operate(requestJson: String, timeoutMs: Int): String {
        if (!writeEnabled() && !isReadOnly(requestJson)) {
            val function = EpsonJson.parseObject(requestJson)?.string("function") ?: "?"
            onBlocked(function)
            throw TseWriteBlockedException(function)
        }
        return inner.operate(requestJson, timeoutMs)
    }

    companion object {
        /** Lesende Funktionen; `FinalizeExport` zusätzlich nur mit `deleteData:false`. */
        val READ_ONLY = setOf(
            "GetStorageInfo", "GetChallenge", "GetTimeOutInterval", "GetStartedTransactionList",
            "GetRegisteredClientList", "GetLastTransactionResponse", "GetLogMessageCertificate",
            "ArchiveExport", "GetExportData", "FinalizeExport", "CancelExport",
        )

        fun isReadOnly(requestJson: String): Boolean {
            val request = EpsonJson.parseObject(requestJson) ?: return false
            val function = request.string("function") ?: return false
            if (function !in READ_ONLY) return false
            if (function == "FinalizeExport") {
                val input = request["input"] as? JsonObject ?: return false
                // Nur ein echtes JSON-Boolean, kein Text "false"
                val delete = input["deleteData"] as? JsonPrimitive ?: return false
                return !delete.isString && delete.booleanOrNull == false
            }
            return true
        }
    }
}
