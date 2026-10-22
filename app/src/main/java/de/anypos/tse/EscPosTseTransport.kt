package de.anypos.tse

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * TSE-Zugang per ESC/POS über eine Byte-Leitung zum Drucker – der Weg für Drucker am USB-Kabel
 * (Developer's Guide Kap. 5: Drucker als TSE-Host unterstützt ESC/POS; Ablauf nach EpsonTseDemo 2.4.1,
 * `TSEAccessByEscPosNetwork`/`TSEAccessByEscPosSerial`):
 *
 * - [open]: TSE freischalten `10 14 06 01 03 01 03 14 01 06 02 08` → Antwort `37 5C <code> ..`, `30` = OK.
 * - [operate]: `1C 28 50 pL pH 49 00 00 00 <json>` (pL/pH = Länge + 4) → Antwort in Blöcken
 *   `53 27 <status> .. sL sH <daten>`, jeder Block wird mit `06` quittiert, bis `status 40`.
 * - [close]: TSE freigeben `10 14 06 01 01 01 03 14 01 06 02 08`.
 *
 * Andere Bytes vom Drucker (Statusmeldungen) werden beim Suchen der Kennungen übersprungen.
 *
 * Bondruck und TSE teilen sich die Leitung: [deviceLock] wird von [open] bis [close] gehalten, der Druck nimmt
 * dieselbe Sperre ([de.anypos.hardware.PrinterService]). Die Leitung ist nur in dieser Zeit geöffnet.
 *
 * Abbruch oder keine Antwort nach dem Senden → [TseResultUnknownException], nie erneutes Senden. Danach hält der
 * Drucker die TSE noch bis zu [lockedAfterLossMs] fest (Epson-Demo wartet 60 s); [open] wartet so lange.
 */
class EscPosTseTransport(
    private val link: ByteLink,
    private val deviceLock: Mutex = Mutex(),
    /** Wartezeit auf die Leitung, solange gedruckt wird. */
    private val lockTimeoutMs: Long = 60_000,
    /** Wartezeit auf die Antwort zum Freischalten/Freigeben (Epson-Demo: 20 s). */
    private val enableTimeoutMs: Int = 20_000,
    /** Versuche, solange die TSE belegt ist (Epson-Demo: 25 × 1 s). */
    private val openAttempts: Int = 25,
    private val openRetryDelayMs: Long = 1_000,
    private val lockedAfterLossMs: Long = 60_000,
    /** Zuschlag auf die Wartezeit der TSE für die Übertragung. */
    private val responseGraceMs: Long = 5_000,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Protokoll ohne Nutzdaten: nur Funktion, Dauer, Ergebnis – nie JSON (PINs, Hashes). */
    private val log: (String) -> Unit = { Log.d(TAG, it) },
) : TseTransport {

    private val calls = Mutex()
    private var holdsLock = false
    private var deviceOpen = false
    private var lostAt: Long? = null

    /** Lesepuffer: [read] liefert Blöcke, ausgewertet wird byteweise. */
    private val buffer = ByteArray(16 * 1024)
    private var bufPos = 0
    private var bufLen = 0

    override val isConnected: Boolean get() = link.isOpen

    val description: String get() = link.description

    override suspend fun connect() = io { attach() }

    override suspend fun open() = io {
        attach()
        if (!deviceOpen) enable()
    }

    override suspend fun operate(requestJson: String, timeoutMs: Int): String = io {
        val function = EposXmlSocketTransport.functionOf(requestJson)
        val json = requestJson.toByteArray(Charsets.UTF_8)
        if (json.size + 4 > MAX_PARAM) throw TseTransportException("Anfrage $function zu groß (max. 64 KB) – nicht gesendet")
        // Bis hierher ist nichts gesendet: Fehler bedeuten „sicher nicht ausgeführt“
        attach()
        if (!deviceOpen) enable()

        val frame = ByteArrayOutputStream(json.size + 9).apply {
            write(byteArrayOf(0x1C, 0x28, 0x50, ((json.size + 4) and 0xFF).toByte(), ((json.size + 4) shr 8).toByte(), 0x49, 0, 0, 0))
            write(json)
        }.toByteArray()
        val started = clock()
        val deadline = started + timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS) + responseGraceMs
        val result = try {
            link.write(frame)
            readResponse(deadline) ?: run {
                lose()
                throw TseResultUnknownException(function, "Keine Antwort auf $function – Ausführung unbekannt")
            }
        } catch (e: IOException) {
            lose()
            throw TseResultUnknownException(function, "Verbindung während $function abgebrochen – Ausführung unbekannt", e)
        }
        log("$function: ${clock() - started} ms, ${resultCode(result)}")
        result
    }

    override suspend fun close() = io { release() }

    override suspend fun disconnect() = io { release() }

    // --- Ablauf

    /** Leitung übernehmen: Sperre gegen den Bondruck holen und Leitung öffnen. */
    private suspend fun attach() {
        if (!holdsLock) {
            val got = withTimeoutOrNull(lockTimeoutMs) { deviceLock.lock() } != null
            if (!got) throw TseTransportException("Drucker ist belegt (Druckauftrag) – TSE nicht erreichbar")
            holdsLock = true
        }
        if (!link.isOpen) {
            try {
                link.open()
            } catch (e: Exception) {
                unlock()
                throw e as? TseTransportException ?: TseTransportException("Drucker nicht erreichbar: ${e.message}", e)
            }
            bufPos = 0
            bufLen = 0
        }
    }

    /** TSE freischalten; nach einem Abbruch erst warten, bis der Drucker sie wieder hergibt. */
    private suspend fun enable() {
        lostAt?.let { at ->
            val wait = at + lockedAfterLossMs - clock()
            if (wait > 0) {
                log("TSE nach Abbruch noch belegt – warte ${wait / 1000} s")
                delay(wait)
            }
            lostAt = null
        }
        for (attempt in 1..openAttempts) {
            val code = try {
                drain()
                link.write(ENABLE)
                readStatus()
            } catch (e: IOException) {
                closeLink()
                throw TseTransportException("TSE-Freischaltung fehlgeschlagen: ${e.message}", e)
            } ?: run {
                closeLink()
                throw TseTransportException("Drucker antwortet nicht auf die TSE-Freischaltung – ist eine TSE im Drucker gesteckt?")
            }
            if (code == STATUS_OK) {
                deviceOpen = true
                return
            }
            if (attempt == openAttempts) break
            log("TSE belegt (${"%02X".format(code)}), Versuch $attempt/$openAttempts")
            delay(openRetryDelayMs)
        }
        closeLink()
        throw TseTransportException("TSE konnte nicht freigeschaltet werden (belegt)")
    }

    /** TSE freigeben, Leitung schließen, Sperre zurückgeben. Fehler beim Freigeben sind unkritisch. */
    private fun release() {
        if (deviceOpen && link.isOpen) {
            try {
                link.write(DISABLE)
                if (readStatus() != STATUS_OK) log("TSE-Freigabe ohne Bestätigung")
            } catch (e: IOException) {
                log("TSE-Freigabe abgebrochen: ${e.message}")
            }
        }
        deviceOpen = false
        closeLink()
    }

    /** Ergebnis unbekannt: Zustand verwerfen; der Drucker hält die TSE noch eine Weile ([lockedAfterLossMs]). */
    private fun lose() {
        lostAt = clock()
        deviceOpen = false
        closeLink()
    }

    private fun closeLink() {
        runCatching { link.close() }
        bufPos = 0
        bufLen = 0
        unlock()
    }

    private fun unlock() {
        if (holdsLock) {
            holdsLock = false
            deviceLock.unlock()
        }
    }

    // --- Antworten lesen

    /** Antwort auf Freischalten/Freigeben: `37 5C <code> ..`; null ohne Antwort. */
    private fun readStatus(): Int? {
        val deadline = clock() + enableTimeoutMs
        if (!seek(0x37, 0x5C, deadline)) return null
        val tail = readBytes(2, deadline) ?: return null
        return tail[0].toInt() and 0xFF
    }

    /** Antwortblöcke bis `status 40` einsammeln, jeden quittieren; null bei Ablauf von [deadline]. */
    private fun readResponse(deadline: Long): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            if (!seek(0x53, 0x27, deadline)) return null
            val head = readBytes(4, deadline) ?: return null
            val size = (head[2].toInt() and 0xFF) or ((head[3].toInt() and 0xFF) shl 8)
            val data = readBytes(size, deadline) ?: return null
            out.write(data)
            if (out.size() > MAX_RESPONSE_BYTES) throw IOException("Antwort zu groß")
            if ((head[0].toInt() and 0xFF) == STATUS_LAST) {
                // Antwort ist vollständig da – eine verlorene letzte Quittung ändert das Ergebnis nicht
                try { link.write(ACK) } catch (e: IOException) { log("Quittung des letzten Blocks nicht gesendet: ${e.message}") }
                return out.toString(Charsets.UTF_8.name())
            }
            link.write(ACK)
        }
    }

    /** Überspringt Bytes bis zur Kennung [a] [b]; false bei Ablauf. */
    private fun seek(a: Int, b: Int, deadline: Long): Boolean {
        var previous = -1
        while (true) {
            val c = nextByte(deadline) ?: return false
            if (previous == a && c == b) return true
            previous = c
        }
    }

    private fun readBytes(n: Int, deadline: Long): ByteArray? {
        val result = ByteArray(n)
        for (i in 0 until n) result[i] = (nextByte(deadline) ?: return null).toByte()
        return result
    }

    private fun nextByte(deadline: Long): Int? {
        while (bufPos >= bufLen) {
            val remaining = deadline - clock()
            if (remaining <= 0) return null
            bufLen = link.read(buffer, minOf(remaining, POLL_MS).toInt())
            bufPos = 0
        }
        return buffer[bufPos++].toInt() and 0xFF
    }

    /** Liegengebliebene Bytes (z. B. eine verspätete Antwort nach einem Abbruch) verwerfen. */
    private fun drain() {
        bufPos = 0
        bufLen = 0
        var total = 0
        while (total < MAX_DRAIN_BYTES) {
            val n = link.read(buffer, DRAIN_MS)
            if (n <= 0) break
            total += n
        }
        if (total > 0) log("$total alte Bytes vom Drucker verworfen")
    }

    private suspend fun <T> io(block: suspend () -> T): T = calls.withLock { withContext(Dispatchers.IO) { block() } }

    companion object {
        private const val TAG = "EscPosTse"
        private val ENABLE = byteArrayOf(0x10, 0x14, 0x06, 0x01, 0x03, 0x01, 0x03, 0x14, 0x01, 0x06, 0x02, 0x08)
        private val DISABLE = byteArrayOf(0x10, 0x14, 0x06, 0x01, 0x01, 0x01, 0x03, 0x14, 0x01, 0x06, 0x02, 0x08)
        private val ACK = byteArrayOf(0x06)
        private const val STATUS_OK = 0x30
        private const val STATUS_LAST = 0x40
        /** pL/pH: 16 Bit. */
        private const val MAX_PARAM = 0xFFFF
        private const val MAX_RESPONSE_BYTES = 1 shl 20
        private const val MIN_TIMEOUT_MS = 100
        private const val MAX_TIMEOUT_MS = 120_000
        private const val POLL_MS = 500L
        private const val DRAIN_MS = 100
        private const val MAX_DRAIN_BYTES = 1 shl 20

        private fun resultCode(json: String): String =
            Regex("\"result\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: "ohne result"
    }
}
