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

    /** Gerät local_TSE öffnen. Wirft bei Antwortcode != OK (z. B. DEVICE_IN_USE nach Wartezeit). */
    suspend fun open()

    /**
     * Ein JSON-Kommando senden und das Ergebnis-JSON (resultdata) zurückgeben.
     * Bricht die Verbindung nach dem Senden ab oder kommt keine Antwort, ist offen, ob die TSE
     * das Kommando ausgeführt hat: dann [TseResultUnknownException], ohne erneutes Senden.
     *
     * [timeoutMs] ist die Wartezeit der TSE (ePOS `<timeout>`, 100–120000 ms).
     */
    suspend fun operate(requestJson: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): String

    /** Gerät schließen (<close_device>) und damit für andere freigeben; die Verbindung bleibt bestehen. */
    suspend fun close()

    /** Verbindung bewusst beenden (<disconnect>) und Zustand verwerfen (kein Wiedereinstieg). */
    suspend fun disconnect()

    companion object {
        /** Wie in der Epson-Demo; Self-Test/Setup brauchen [LONG_TIMEOUT_MS]. */
        const val DEFAULT_TIMEOUT_MS = 60_000

        /** JSON-Spezifikation: SetUp, SetUpForPrinter, RunTSESelfTest bis 60 s, Timeout 120 s. */
        const val LONG_TIMEOUT_MS = 120_000
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

/**
 * Dauerhafte Ablage der `client_id` der letzten Sitzung (ePOS-Device-Handbuch S. 36): Nach einem Neustart
 * oder Absturz wird die alte Sitzung damit per `<disconnect>` beendet, statt die TSE 1 Minute zu blockieren.
 */
interface ClientIdStore {
    fun load(): String?
    fun save(clientId: String?)
}

/** Nur im Speicher (Tests, Mock). */
class MemoryClientIdStore : ClientIdStore {
    @Volatile private var value: String? = null
    override fun load() = value
    override fun save(clientId: String?) { value = clientId }
}
