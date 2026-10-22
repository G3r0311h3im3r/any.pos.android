package de.anypos.tse

import android.util.Log
import kotlinx.coroutines.Dispatchers
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
 * ePOS Device XML über TCP (Epson TSE Developer's Guide Rev. 1.0.1): Port 8009, jede Nachricht endet mit '\0'.
 *
 * Ablauf: TCP verbinden → Drucker sendet <connect> mit client_id → <open_device> local_TSE →
 * je Kommando <device_data> mit JSON in <requestdata> → Antwort mit <resultdata>.
 *
 * Nach einem Verbindungsabbruch sperrt der Drucker die TSE 1 Minute für andere Clients; innerhalb dieses
 * Fensters steigt der Transport beim nächsten Kommando per <reconnect> wieder ein. Das abgebrochene
 * Kommando wird dabei nie erneut gesendet.
 *
 * Für TLS (Port 8143) genügt eine andere [socketFactory].
 */
class EposXmlSocketTransport(
    private val host: String,
    private val port: Int = 8009,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val connectTimeoutMs: Int = 5_000,
    private val handshakeTimeoutMs: Int = 5_000,
    private val reconnectWindowMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Protokoll ohne Nutzdaten: nur Funktion, Dauer, Ergebnis – nie JSON (PINs, Hashes). */
    private val log: (String) -> Unit = { Log.d(TAG, it) },
) : TseTransport {

    private val mutex = Mutex()
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var deviceOpen = false
    private var sequence = 0L

    /** client_id der aktuellen Verbindung (vom Drucker vergeben). */
    var clientId: String? = null
        private set

    /** Letzte vom Drucker erhaltene data_id; wird beim Wiedereinstieg als received_id gemeldet. */
    var lastDataId: String? = null
        private set

    // Wiedereinstieg nach Abbruch
    private var lostAt: Long? = null
    private var lostClientId: String? = null

    override val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    override suspend fun connect() = io { if (!isConnected) establish() }

    override suspend fun open() = io {
        if (!isConnected) establish()
        if (!deviceOpen) openDevice()
    }

    override suspend fun operate(requestJson: String, timeoutMs: Int): String = io {
        val function = functionOf(requestJson)
        // Sitzung herstellen: Fehler hier bedeuten, das Kommando ist sicher nicht gesendet
        if (!isConnected) establish()
        if (!deviceOpen) openDevice()

        val seq = ++sequence
        val started = clock()
        try {
            send(
                "<device_data><sequence>$seq</sequence><device_id>$DEVICE_ID</device_id><data>" +
                    "<type>operate</type><timeout>$timeoutMs</timeout><requestdata>${escape(requestJson)}</requestdata>" +
                    "</data></device_data>",
            )
        } catch (e: IOException) {
            connectionLost()
            throw TseResultUnknownException(function, "Senden von $function abgebrochen – Ausführung unbekannt", e)
        }

        val result = awaitResult(function, seq, started + timeoutMs + RESPONSE_GRACE_MS)
        log("$function: ${clock() - started} ms, ${resultCode(result)}")
        result
    }

    /** Liest bis zur Antwort auf Sequenz [seq]; fremde Nachrichten und Ereignisse werden übersprungen. */
    private fun awaitResult(function: String, seq: Long, deadline: Long): String {
        while (true) {
            val remaining = deadline - clock()
            if (remaining <= 0) {
                connectionLost()
                throw TseResultUnknownException(function, "Keine Antwort auf $function – Ausführung unbekannt")
            }
            val msg = try {
                readMessage(remaining.toInt())
            } catch (e: IOException) {
                connectionLost()
                throw TseResultUnknownException(function, "Verbindung während $function abgebrochen – Ausführung unbekannt", e)
            }
            if (msg.tagName != "device_data") continue
            val data = msg.child("data") ?: continue
            if (data.text("type") != "onoperateresult") continue
            // Schickt der Drucker eine Sequenz mit, muss sie passen
            if (msg.text("sequence")?.let { it != seq.toString() } == true) continue

            data.text("resultdata")?.let { return it }
            val code = data.text("code")
            if (code == "DEVICE_NOT_OPEN") {
                // Der Drucker hat das Kommando nicht an die TSE weitergegeben
                deviceOpen = false
                throw TseTransportException("TSE-Gerät nicht geöffnet ($function nicht ausgeführt)")
            }
            throw TseResultUnknownException(function, "Gerätefehler ${code ?: "ohne Code"} bei $function – Ausführung unbekannt")
        }
    }

    override suspend fun close() = io {
        if (isConnected && deviceOpen) {
            runCatching {
                send("<close_device><device_id>$DEVICE_ID</device_id></close_device>")
                readUntil("close_device", handshakeTimeoutMs)
            }
        }
        deviceOpen = false
    }

    override suspend fun disconnect() = io {
        closeSocket()
        deviceOpen = false
        clientId = null
        lostAt = null
        lostClientId = null
    }

    // --- Verbindungsaufbau

    /** Neue TCP-Verbindung; innerhalb des Sperrfensters nach einem Abbruch per <reconnect>. */
    private fun establish() {
        closeSocket()
        // Adresse außerhalb von apply bilden: dort würde "port" sonst Socket.port (0) meinen
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
        clientId = newId

        val oldId = lostClientId
        val lost = lostAt
        lostAt = null
        lostClientId = null
        if (oldId != null && lost != null && clock() - lost <= reconnectWindowMs) {
            val ok = try {
                send(
                    "<reconnect><data><new_client_id>${escape(newId)}</new_client_id>" +
                        "<old_client_id>${escape(oldId)}</old_client_id>" +
                        "<received_id>${escape(lastDataId.orEmpty())}</received_id></data></reconnect>",
                )
                val answer = readUntil("reconnect", handshakeTimeoutMs)
                (answer.text("code") ?: answer.child("data")?.text("code")) == "OK"
            } catch (e: IOException) {
                false
            }
            // Erfolgreich: Sitzung inkl. geöffnetem Gerät läuft weiter; sonst Gerät neu öffnen
            if (!ok) deviceOpen = false
            log("Wiedereinstieg ${if (ok) "erfolgreich" else "abgelehnt"}")
        } else {
            deviceOpen = false
        }
    }

    private fun openDevice() {
        try {
            send("<open_device><device_id>$DEVICE_ID</device_id><data><type>type_storage</type></data></open_device>")
            val answer = readUntil("open_device", handshakeTimeoutMs)
            val code = answer.text("code")
            if (code != "OK") throw TseTransportException("TSE-Gerät konnte nicht geöffnet werden: ${code ?: "ohne Code"}")
            deviceOpen = true
        } catch (e: IOException) {
            closeSocket()
            throw TseTransportException("Öffnen der TSE fehlgeschlagen: ${e.message}", e)
        }
    }

    private fun connectionLost() {
        lostAt = clock()
        lostClientId = clientId
        closeSocket()
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
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

    /** Liest bis zum Null-Byte; merkt sich jede data_id. */
    private fun readMessage(timeoutMs: Int): Element {
        val s = socket ?: throw IOException("nicht verbunden")
        val inp = input ?: throw IOException("nicht verbunden")
        s.soTimeout = timeoutMs.coerceAtLeast(1)
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = inp.read()
            if (b == -1) throw IOException("Verbindung vom TSE-Host geschlossen")
            if (b == 0) break
            buf.write(b)
            if (buf.size() > MAX_MESSAGE_BYTES) throw IOException("Nachricht zu groß")
        }
        val element = parse(buf.toString(Charsets.UTF_8.name()))
        element.text("data_id")?.let { lastDataId = it }
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

        private val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }

        fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

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
