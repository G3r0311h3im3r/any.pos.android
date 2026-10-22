package de.anypos.tse

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.SocketFactory
import javax.xml.parsers.DocumentBuilderFactory

/**
 * ePOS Device XML über TCP (ePOS-Device XML User's Manual Rev. AC, Epson TSE Developer's Guide):
 * Port 8009 (bzw. 8143 mit TLS über [socketFactory]), jede Nachricht ist UTF-8-XML mit abschließendem '\0'.
 *
 * Ablauf: TCP verbinden → Drucker sendet <connect> mit client_id → <open_device> local_TSE → je Kommando
 * <device_data> (ohne <sequence>) mit JSON in <requestdata> → Antwort <device_data> mit <resultdata>.
 * Fehler zu einem Kommando kommen als eigene <error>-Nachricht (Handbuch S. 82).
 *
 * Abbruch nach dem Senden: Der Transport steigt sofort per <reconnect> wieder ein; der Drucker liefert dann
 * die zwischengespeicherte Antwort nach (Handbuch S. 36, Guide S. 6). Kommt sie, ist das Ergebnis bekannt;
 * sonst [TseResultUnknownException]. Das Kommando wird nie erneut gesendet.
 *
 * Beim Beenden und nach einem Neustart wird die alte Sitzung per <disconnect> beendet ([clientIdStore]),
 * damit die TSE nicht eine Minute für alle gesperrt bleibt.
 */
class EposXmlSocketTransport(
    private val host: String,
    private val port: Int = 8009,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val connectTimeoutMs: Int = 5_000,
    /** Wartezeit auf <connect>, <reconnect>, <disconnect> (Epson-Demo: 20 s). */
    private val handshakeTimeoutMs: Int = 20_000,
    /** Wartezeit auf die Antwort zu <open_device>/<close_device>. */
    private val openTimeoutMs: Int = 30_000,
    /** Versuche bei DEVICE_IN_USE/DEVICE_OPEN_ERROR (Epson-Demo: 25 × 1 s). */
    private val openAttempts: Int = 25,
    private val openRetryDelayMs: Long = 1_000,
    private val reconnectWindowMs: Long = 60_000,
    /** So lange wird nach einem Wiedereinstieg auf die nachgelieferte Antwort gewartet. */
    private val redeliveryWaitMs: Long = 5_000,
    private val clientIdStore: ClientIdStore = MemoryClientIdStore(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Protokoll ohne Nutzdaten: nur Funktion, Dauer, Ergebnis – nie JSON (PINs, Hashes). */
    private val log: (String) -> Unit = { Log.d(TAG, it) },
) : TseTransport {

    private class LostSession(val clientId: String, val at: Long, val resumable: Boolean, val deviceWasOpen: Boolean)
    private class ConnectionLost(cause: IOException) : Exception(cause)

    private val mutex = Mutex()
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var deviceOpen = false
    /** Bereits gelesener Teil einer Nachricht; bleibt bei einem Lese-Timeout erhalten, damit die Rahmung stimmt. */
    private val pending = ByteArrayOutputStream()
    private var lost: LostSession? = null

    /** client_id der aktuellen Verbindung (vom Drucker vergeben). */
    var clientId: String? = null
        private set

    /** Letzte vom Drucker erhaltene data_id; wird beim Wiedereinstieg als received_id gemeldet. */
    var lastDataId: String? = null
        private set

    override val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    override suspend fun connect() = io { if (!isConnected) establish() }

    override suspend fun open() = io {
        if (!isConnected) establish()
        if (!deviceOpen) openDevice()
    }

    override suspend fun operate(requestJson: String, timeoutMs: Int): String = io {
        val function = functionOf(requestJson)
        val escaped = escape(requestJson)
        if (escaped.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
            throw TseTransportException("Anfrage $function zu groß (max. 64 KB) – nicht gesendet")
        }
        val timeout = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        // Sitzung herstellen: Fehler hier bedeuten, das Kommando ist sicher nicht gesendet
        if (!isConnected) establish()
        if (!deviceOpen) openDevice()

        val started = clock()
        val deadline = started + timeout + RESPONSE_GRACE_MS
        val result = try {
            send(
                "<device_data><device_id>$DEVICE_ID</device_id><data><type>operate</type>" +
                    "<timeout>$timeout</timeout><requestdata>$escaped</requestdata></data></device_data>",
            )
            awaitResult(function, deadline) ?: run {
                abandonSession()
                throw TseResultUnknownException(function, "Keine Antwort auf $function – Ausführung unbekannt")
            }
        } catch (e: IOException) {
            recoverAfterLoss(function, deadline, e)
        } catch (e: ConnectionLost) {
            recoverAfterLoss(function, deadline, e.cause as IOException)
        }
        log("$function: ${clock() - started} ms, ${resultCode(result)}")
        result
    }

    /**
     * Liest bis zur Antwort auf [function]; null bei Ablauf von [deadline]. Ereignisse und Antworten auf andere
     * Funktionen werden übersprungen. Fehlercodes werden ausgewertet ([handleCode]).
     */
    private fun awaitResult(function: String, deadline: Long): String? {
        while (true) {
            val remaining = deadline - clock()
            if (remaining <= 0) return null
            val msg = try {
                readMessage(remaining.toInt())
            } catch (e: SocketTimeoutException) {
                return null
            } catch (e: IOException) {
                throw ConnectionLost(e)
            }
            when (msg.tagName) {
                "error" -> handleCode(function, msg.text("code"))
                "device_data" -> {
                    val data = msg.child("data") ?: continue
                    data.text("resultdata")?.let { result ->
                        val answered = functionOf(result)
                        if (answered == "?" || answered == function) return result
                        log("Antwort auf $answered verworfen (erwartet $function)")
                        continue
                    }
                    // Ergebnis ohne resultdata: Gerätefehler (Handbuch S. 218); Typ heißt je nach Quelle unterschiedlich
                    if (data.text("type") in RESULT_TYPES) handleCode(function, data.text("code"))
                }
            }
        }
    }

    /** Wertet einen Fehlercode ohne Ergebnis aus; wirft immer. */
    private fun handleCode(function: String, code: String?): Nothing {
        if (code in NOT_EXECUTED) {
            if (code == "DEVICE_NOT_OPEN" || code == "DEVICE_NOT_FOUND") deviceOpen = false
            throw TseTransportException("Drucker meldet $code – $function nicht ausgeführt")
        }
        abandonSession()
        throw TseResultUnknownException(function, "Gerätefehler ${code ?: "ohne Code"} bei $function – Ausführung unbekannt")
    }

    /**
     * Verbindung nach dem Senden verloren: sofort per <reconnect> wieder einsteigen und auf die nachgelieferte
     * Antwort warten. Ohne Antwort bleibt das Ergebnis unbekannt, und die Sitzung wird verworfen, damit eine
     * späte Antwort nie einem anderen Kommando zugeordnet wird.
     */
    private fun recoverAfterLoss(function: String, deadline: Long, cause: IOException): String {
        markLost(resumable = true)
        val resumed = try { establish() } catch (e: TseTransportException) { false }
        if (resumed) {
            val answer = try {
                awaitResult(function, minOf(deadline, clock() + redeliveryWaitMs))
            } catch (e: ConnectionLost) {
                null
            }
            if (answer != null) {
                log("$function: Antwort nach Wiedereinstieg erhalten")
                return answer
            }
        }
        abandonSession()
        throw TseResultUnknownException(function, "Verbindung während $function abgebrochen – Ausführung unbekannt", cause)
    }

    override suspend fun close() = io {
        if (isConnected && deviceOpen) {
            try {
                send("<close_device><device_id>$DEVICE_ID</device_id></close_device>")
                readUntil("close_device", openTimeoutMs)
            } catch (e: IOException) {
                abandonSession()
            }
        }
        deviceOpen = false
    }

    override suspend fun disconnect() = io {
        if (isConnected) {
            runCatching {
                if (deviceOpen) {
                    send("<close_device><device_id>$DEVICE_ID</device_id></close_device>")
                    readUntil("close_device", openTimeoutMs)
                }
                clientId?.let { sendDisconnect(it) }
            }
        }
        closeSocket()
        deviceOpen = false
        clientId = null
        lost = null
        clientIdStore.save(null)
    }

    // --- Verbindungsaufbau

    /**
     * Neue TCP-Verbindung. Innerhalb des Sperrfensters nach einem Abbruch Wiedereinstieg per <reconnect>
     * (true = Sitzung samt geöffnetem Gerät fortgesetzt); sonst wird die alte Sitzung per <disconnect> beendet.
     */
    private fun establish(): Boolean {
        closeSocket()
        // Adresse außerhalb des Lambdas bilden: dort würde "port" sonst Socket.port (0) meinen
        val address = InetSocketAddress(host, port)
        val s = try {
            socketFactory.createSocket().also { socket ->
                socket.tcpNoDelay = true
                socket.connect(address, connectTimeoutMs)
            }
        } catch (e: IOException) {
            throw TseTransportException("TSE-Host $host:$port nicht erreichbar: ${e.message}", e)
        }
        socket = s
        input = s.getInputStream().buffered()
        output = s.getOutputStream()

        val hello = try { readUntil("connect", handshakeTimeoutMs) } catch (e: IOException) {
            closeSocket()
            throw TseTransportException("Kein Handshake vom TSE-Host: ${e.message}", e)
        }
        val newId = hello.child("data")?.text("client_id") ?: hello.text("client_id")
            ?: run { closeSocket(); throw TseTransportException("Handshake ohne client_id") }

        val previous = lost
        lost = null
        var resumed = false
        val received = lastDataId
        if (previous != null && previous.resumable && clock() - previous.at <= reconnectWindowMs && !received.isNullOrEmpty()) {
            resumed = try {
                send(
                    "<reconnect><data><new_client_id>${escape(newId)}</new_client_id>" +
                        "<old_client_id>${escape(previous.clientId)}</old_client_id>" +
                        "<received_id>${escape(received)}</received_id></data></reconnect>",
                )
                val code = readUntil("reconnect", handshakeTimeoutMs).let { it.text("code") ?: it.child("data")?.text("code") }
                log("Wiedereinstieg ${if (code == "OK") "erfolgreich" else "abgelehnt ($code)"}")
                code == "OK"
            } catch (e: IOException) {
                false
            }
        }
        if (resumed) {
            // Sitzung samt geöffnetem Gerät läuft weiter
            deviceOpen = previous?.deviceWasOpen == true
        } else {
            deviceOpen = false
            // Alte Sitzung beenden, sonst bleibt die TSE bis zu 1 Minute reserviert (Handbuch S. 36)
            val old = previous?.clientId ?: clientIdStore.load()
            if (old != null && old != newId) {
                try { sendDisconnect(old) } catch (e: IOException) {
                    closeSocket()
                    throw TseTransportException("Verbindung beim Beenden der alten Sitzung abgebrochen: ${e.message}", e)
                }
            }
        }
        clientId = newId
        clientIdStore.save(newId)
        return resumed
    }

    private fun sendDisconnect(id: String) {
        send("<disconnect><data><client_id>${escape(id)}</client_id></data></disconnect>")
        val code = readUntil("disconnect", handshakeTimeoutMs).let { it.text("code") ?: it.child("data")?.text("code") }
        log("Alte Sitzung beendet: ${code ?: "ohne Code"}")
    }

    /**
     * Gerät öffnen. Ist die Verbindung inzwischen tot (z. B. App im Hintergrund eingefroren, WLAN kurz weg), wird
     * genau einmal neu verbunden – gefahrlos, weil noch kein Kommando gesendet wurde.
     */
    private suspend fun openDevice(reconnectOnce: Boolean = true) {
        for (attempt in 1..openAttempts) {
            val code = try {
                send("<open_device><device_id>$DEVICE_ID</device_id><data><type>type_storage</type></data></open_device>")
                readUntil("open_device", openTimeoutMs).text("code")
            } catch (e: IOException) {
                abandonSession()
                if (reconnectOnce) {
                    log("Verbindung beim Öffnen getrennt – neu verbinden")
                    establish()
                    return openDevice(reconnectOnce = false)
                }
                throw TseTransportException("Öffnen der TSE fehlgeschlagen: ${e.message}", e)
            }
            if (code == "OK") {
                deviceOpen = true
                return
            }
            if (code !in RETRY_OPEN || attempt == openAttempts) {
                throw TseTransportException("TSE-Gerät konnte nicht geöffnet werden: ${code ?: "ohne Code"}" +
                    if (code == "DEVICE_IN_USE") " (von anderer Kasse/Anwendung belegt)" else "")
            }
            log("TSE belegt ($code), Versuch $attempt/$openAttempts")
            delay(openRetryDelayMs)
        }
    }

    /** Sitzung verloren; [resumable] = Wiedereinstieg erlaubt, sonst wird sie beim nächsten Aufbau beendet. */
    private fun markLost(resumable: Boolean) {
        clientId?.let { lost = LostSession(it, clock(), resumable, deviceOpen) }
        closeSocket()
        deviceOpen = false
    }

    private fun abandonSession() = markLost(resumable = false)

    private fun closeSocket() {
        runCatching { socket?.close() }
        pending.reset()
        socket = null
        input = null
        output = null
    }

    // --- Nachrichten

    private fun send(xml: String) {
        val out = output ?: throw IOException("nicht verbunden")
        out.write(xml.toByteArray(Charsets.UTF_8))
        out.write(0)
        out.flush()
    }

    /** Liest bis zum Null-Byte; merkt sich jede data_id. Ein Timeout mitten in der Nachricht verliert nichts. */
    private fun readMessage(timeoutMs: Int): Element {
        val s = socket ?: throw IOException("nicht verbunden")
        val inp = input ?: throw IOException("nicht verbunden")
        s.soTimeout = timeoutMs.coerceAtLeast(1)
        while (true) {
            val b = inp.read()
            if (b == -1) throw IOException("Verbindung vom TSE-Host geschlossen")
            if (b == 0) break
            pending.write(b)
            if (pending.size() > MAX_MESSAGE_BYTES) throw IOException("Nachricht zu groß")
        }
        val text = pending.toString(Charsets.UTF_8.name())
        pending.reset()
        val element = parse(text)
        element.text("data_id")?.takeIf { it.isNotEmpty() }?.let { lastDataId = it }
        return element
    }

    private fun readUntil(tag: String, timeoutMs: Int): Element {
        val deadline = clock() + timeoutMs
        while (true) {
            val remaining = deadline - clock()
            if (remaining <= 0) throw SocketTimeoutException("keine <$tag>-Antwort")
            val msg = readMessage(remaining.toInt())
            if (msg.tagName == tag) return msg
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block() } }

    companion object {
        private const val TAG = "EposXmlSocket"
        const val DEVICE_ID = "local_TSE"
        private const val RESPONSE_GRACE_MS = 5_000
        private const val MAX_MESSAGE_BYTES = 1 shl 20
        /** Handbuch S. 217: maximale Größe von requestdata. */
        private const val MAX_REQUEST_BYTES = 64 * 1024
        /** Handbuch S. 217: timeout 100–120000 ms. */
        private const val MIN_TIMEOUT_MS = 100
        private const val MAX_TIMEOUT_MS = 120_000

        /** Handbuch: „operateresult“, Developer's Guide: „onoperateresult“. */
        private val RESULT_TYPES = setOf("operateresult", "onoperateresult")

        /** Fehlercodes, bei denen das Kommando sicher nicht an die TSE ging (Handbuch S. 82, 218). */
        private val NOT_EXECUTED = setOf(
            "DEVICE_NOT_OPEN", "DEVICE_NOT_FOUND", "PARAM_ERROR", "COMMAND_ILLEGAL", "FUNCTION_NOT_FOUND",
            "ERROR_PARMETER", "ERROR_PARAMETER", "ERROR_DEVICE_BUSY",
        )

        /** Bei diesen open_device-Codes wird nach kurzer Pause erneut versucht. */
        private val RETRY_OPEN = setOf("DEVICE_IN_USE", "DEVICE_OPEN_ERROR")

        private val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }

        /** XML-Maskierung wie in der praxiserprobten Anbindung, inklusive Anführungszeichen. */
        fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        fun parse(xml: String): Element = try {
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
        } catch (e: Exception) {
            throw IOException("Ungültige XML-Nachricht vom TSE-Host", e)
        }

        /** Funktionsname aus dem JSON (nur für Protokoll und Fehlermeldungen). */
        fun functionOf(json: String): String =
            Regex("\"function\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: "?"

        private fun resultCode(json: String): String =
            Regex("\"result\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: "ohne result"

        private fun Element.child(tag: String): Element? {
            val nodes = getElementsByTagName(tag)
            return if (nodes.length > 0) nodes.item(0) as Element else null
        }

        private fun Element.text(tag: String): String? = child(tag)?.textContent?.trim()
    }
}
