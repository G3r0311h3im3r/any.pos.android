package de.anypos.tse

/**
 * Übertragungsweg zur Epson-TSE (ePOS Device XML). Enthält keine Fachlogik: Login, Zeit,
 * Fehlercodes und der Schreibschutz liegen in [EpsonTse] bzw. in einer Schutzhülle um den Transport.
 *
 * Grundregel (TSE-Plan.md, Regel 4): Ein Kommando wird nie automatisch wiederholt.
 */
interface TseTransport {
    val isConnected: Boolean

    /** TCP-Verbindung aufbauen und Handshake (<connect> des Druckers lesen). Idempotent. */
    suspend fun connect()

    /** Gerät local_TSE öffnen. Wirft bei Antwortcode != OK (z. B. DEVICE_IN_USE). */
    suspend fun open()

    /**
     * Ein JSON-Kommando senden und das Ergebnis-JSON (resultdata) zurückgeben.
     * Bricht die Verbindung nach dem Senden ab oder kommt keine Antwort, ist offen, ob die TSE
     * das Kommando ausgeführt hat: dann [TseResultUnknownException], ohne erneutes Senden.
     */
    suspend fun operate(requestJson: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): String

    /** Gerät schließen (<close_device>), Verbindung bleibt bestehen. */
    suspend fun close()

    /** Verbindung bewusst trennen und Zustand verwerfen (kein Wiedereinstieg). */
    suspend fun disconnect()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000
    }
}

/** Verbindung, Protokoll oder Gerät gestört; das Kommando wurde sicher NICHT ausgeführt. */
open class TseTransportException(message: String, cause: Throwable? = null) : TseException(message, cause)

/**
 * Kommando wurde gesendet, die Antwort fehlt (Abbruch, Timeout, Gerätefehler).
 * Ob die TSE es ausgeführt hat, ist unbekannt: nicht wiederholen, sondern den TSE-Zustand prüfen
 * (z. B. GetStartedTransactionList / GetLastTransactionResponse).
 */
class TseResultUnknownException(val function: String, message: String, cause: Throwable? = null) :
    TseTransportException(message, cause)
