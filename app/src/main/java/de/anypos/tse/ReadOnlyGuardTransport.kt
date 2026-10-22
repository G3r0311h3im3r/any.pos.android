package de.anypos.tse

import de.anypos.tse.EpsonJson.string

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
        /**
         * Funktionen, die ohne Anmeldung nur lesen (JSON-Spezifikation Rev. C).
         *
         * Bewusst NICHT enthalten: `GetRegisteredClientList`, `GetTimeOutInterval` und alle Export-Funktionen –
         * sie verlangen eine Admin-Anmeldung (Spez. S. 18, 22, 45–53); `ArchiveExport` versetzt die TSE zudem in
         * den Exportmodus, in dem sie nichts anderes annimmt (auch nicht von JTL-POS).
         */
        val READ_ONLY = setOf(
            "GetStorageInfo", "GetChallenge", "GetStartedTransactionList",
            "GetLastTransactionResponse", "GetLogMessageCertificate",
        )

        fun isReadOnly(requestJson: String): Boolean {
            val request = EpsonJson.parseObject(requestJson) ?: return false
            return request.string("function") in READ_ONLY
        }
    }
}
