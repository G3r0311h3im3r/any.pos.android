package de.anypos.tsemock

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.xml.parsers.DocumentBuilderFactory

/** Störungen auf Verbindungsebene, um Ausfallpfade („Ergebnis unbekannt“) zu prüfen. */
enum class Fault {
    /** Verbindung wird nach Empfang getrennt, das Kommando wird **nicht** ausgeführt. */
    DROP_BEFORE_EXECUTE,

    /** Kommando wird ausgeführt, danach wird die Verbindung ohne Antwort getrennt. */
    DROP_AFTER_EXECUTE,

    /** Kommando wird ausgeführt, die Antwort bleibt aus; die Verbindung bleibt offen. */
    NO_ANSWER,
}

/**
 * TCP-Server mit dem ePOS-Device-XML-Protokoll des Epson-Druckers (Handbuch Rev. AC; Nachrichten enden mit '\0').
 *
 * - Begrüßung `<connect>` mit `client_id`, danach `open_device` / `device_data` / `close_device` / `reconnect` / `disconnect`.
 * - Die TSE gehört immer genau einer Verbindung. Bricht diese ab, bleibt die TSE [MockConfig.reconnectWindowMs]
 *   für andere gesperrt (`DEVICE_IN_USE`); `<reconnect>` mit der alten `client_id` übernimmt die Sitzung und liefert
 *   eine zurückgehaltene Antwort nach, `<disconnect>` mit der alten `client_id` gibt die TSE sofort frei.
 * - Fehler zu `device_data` kommen als eigene `<error>`-Nachricht (Handbuch S. 82).
 * - Ergebnistyp umschaltbar ([resultType]): Handbuch „operateresult“, Developer's Guide „onoperateresult“.
 *
 * Protokolliert werden nur Funktion und Ergebnis, nie JSON-Inhalte.
 */
class MockTseServer(
    val tse: MockTse,
    port: Int = 0,
    bindAddress: InetAddress? = InetAddress.getLoopbackAddress(),
    /** TLS wie Port 8143 am Drucker; null = Klartext wie Port 8009. */
    tls: SSLContext? = null,
    private val resultType: String = "operateresult",
    private val log: (String) -> Unit = {},
) : Closeable {

    private val server: ServerSocket =
        tls?.serverSocketFactory?.createServerSocket(port, 50, bindAddress) ?: ServerSocket(port, 50, bindAddress)
    val port: Int get() = server.localPort

    private val lock = Any()
    private val sessions: MutableSet<Session> = Collections.synchronizedSet(mutableSetOf())
    private val clientCounter = AtomicLong()
    private val dataCounter = AtomicLong()
    private var owner: Session? = null
    private var lostOwnerId: String? = null
    private var lostAt = 0L
    /** Antworten, die wegen eines Abbruchs nicht zugestellt wurden: client_id → XML (Nachlieferung nach reconnect). */
    private val undelivered = mutableMapOf<String, String>()
    private val faults = ArrayDeque<Pair<Fault, String?>>()

    private val clock get() = tse.config.clock

    fun start(): MockTseServer {
        Thread({
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: IOException) { break }
                val session = Session(socket, "sock" + clientCounter.incrementAndGet())
                sessions += session
                Thread(session::run, "mock-tse-${session.clientId}").apply { isDaemon = true; start() }
            }
        }, "mock-tse-accept").apply { isDaemon = true; start() }
        return this
    }

    /** Nächstes Kommando (optional nur [function]) erhält die Störung [fault]. */
    fun injectFault(fault: Fault, function: String? = null) = synchronized(lock) { faults.addLast(fault to function) }

    /** Netzwerkausfall: alle Verbindungen trennen; der Besitzer der TSE gilt als abgebrochen. */
    fun dropAllConnections() = sessions.toList().forEach { it.socket.close() }

    /** Sperre nach einem Abbruch noch aktiv? */
    val blockedAfterDisconnect: Boolean get() = synchronized(lock) { lostOwnerId != null && clock() - lostAt <= tse.config.reconnectWindowMs }

    /** Empfangene `<disconnect>`-Nachrichten (client_id), für Tests. */
    val disconnects: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun close() {
        server.close()
        dropAllConnections()
    }

    private fun takeFault(function: String): Fault? = synchronized(lock) {
        faults.firstOrNull { it.second == null || it.second == function }?.also { faults.remove(it) }?.first
    }

    private inner class Session(val socket: Socket, val clientId: String) {
        private val inp: InputStream = socket.getInputStream().buffered()
        private val out: OutputStream = socket.getOutputStream()

        fun run() {
            try {
                send("<connect><data><client_id>$clientId</client_id><protocol_version>2</protocol_version></data></connect>")
                while (true) {
                    val msg = parse(read() ?: break)
                    when (msg.tagName) {
                        "open_device" -> openDevice(msg)
                        "close_device" -> closeDevice()
                        "reconnect" -> reconnect(msg)
                        "disconnect" -> if (!disconnect(msg)) break
                        "device_data" -> if (!deviceData(msg)) break
                        else -> log("$clientId: unbekannte Nachricht <${msg.tagName}>")
                    }
                }
            } catch (e: IOException) {
                // Verbindung abgebrochen
            } finally {
                runCatching { socket.close() }
                sessions -= this
                synchronized(lock) {
                    if (owner === this) {
                        owner = null
                        lostOwnerId = clientId
                        lostAt = clock()
                        log("$clientId: Verbindung abgebrochen, TSE ${tse.config.reconnectWindowMs / 1000} s gesperrt")
                    }
                }
            }
        }

        private fun openDevice(msg: Element) {
            val code = when {
                msg.text("device_id") != DEVICE_ID -> "DEVICE_NOT_FOUND"
                else -> synchronized(lock) {
                    when {
                        owner === this -> "OK"
                        owner != null || blockedAfterDisconnect -> "DEVICE_IN_USE"
                        else -> { owner = this; lostOwnerId = null; "OK" }
                    }
                }
            }
            log("$clientId: open_device $code")
            send("<open_device><device_id>$DEVICE_ID</device_id><code>$code</code><data_id>${dataCounter.incrementAndGet()}</data_id></open_device>")
        }

        private fun closeDevice() {
            val code = synchronized(lock) { if (owner === this) { owner = null; "OK" } else "DEVICE_NOT_OPEN" }
            send("<close_device><device_id>$DEVICE_ID</device_id><code>$code</code><data_id>${dataCounter.incrementAndGet()}</data_id></close_device>")
        }

        /** Handbuch S. 67: Antwort nur mit code (OK, CLIENT_NOT_FOUND …); danach Nachlieferung zurückgehaltener Daten. */
        private fun reconnect(msg: Element) {
            val old = msg.text("old_client_id")
            var redeliver: String? = null
            val code = synchronized(lock) {
                if (old != null && old == lostOwnerId && blockedAfterDisconnect && owner == null) {
                    owner = this
                    lostOwnerId = null
                    redeliver = undelivered.remove(old)
                    "OK"
                } else "CLIENT_NOT_FOUND"
            }
            log("$clientId: reconnect von $old $code${if (redeliver != null) ", Antwort wird nachgeliefert" else ""}")
            send("<reconnect><code>$code</code></reconnect>")
            redeliver?.let { send(it) }
        }

        /** Handbuch S. 68: beendet die Sitzung [client_id] und gibt die TSE frei. @return false = eigene Sitzung beendet. */
        private fun disconnect(msg: Element): Boolean {
            val id = msg.child("data")?.text("client_id") ?: msg.text("client_id")
            if (id != null) disconnects += id
            var other: Session? = null
            synchronized(lock) {
                if (id != null && id == lostOwnerId) lostOwnerId = null
                if (id != null) undelivered.remove(id)
                val target = sessions.firstOrNull { it.clientId == id }
                if (target != null && owner === target) owner = null
                if (target != null && target !== this) other = target
            }
            log("$clientId: disconnect $id OK")
            send("<disconnect><code>OK</code></disconnect>")
            other?.socket?.close()
            return id != clientId
        }

        /** @return false, wenn die Verbindung absichtlich getrennt wurde. */
        private fun deviceData(msg: Element): Boolean {
            val data = msg.child("data")
            if (data?.text("type") != "operate") return true
            val isOwner = synchronized(lock) { owner === this }
            if (!isOwner) {
                // Fehler als eigene Nachricht (Handbuch S. 82–83)
                send("<error><device_id>$DEVICE_ID</device_id><code>DEVICE_NOT_OPEN</code><data></data>" +
                    "<data_id>${dataCounter.incrementAndGet()}</data_id></error>")
                return true
            }
            val request = data.text("requestdata").orEmpty()
            val function = Regex("\"function\"\\s*:\\s*\"([^\"]+)\"").find(request)?.groupValues?.get(1) ?: "?"
            when (takeFault(function)) {
                Fault.DROP_BEFORE_EXECUTE -> { log("$clientId: $function – Störung: Abbruch vor Ausführung"); return false }
                Fault.DROP_AFTER_EXECUTE -> {
                    // Antwort wird zurückgehalten und nach einem <reconnect> nachgeliefert
                    val held = resultXml(tse.handle(request))
                    synchronized(lock) { undelivered[clientId] = held }
                    log("$clientId: $function – Störung: Abbruch nach Ausführung")
                    return false
                }
                Fault.NO_ANSWER -> { tse.handle(request); log("$clientId: $function – Störung: keine Antwort"); return true }
                null -> Unit
            }
            val result = tse.handle(request)
            log("$clientId: $function → ${Regex("\"result\"\\s*:\\s*\"([^\"]+)\"").find(result)?.groupValues?.get(1)}")
            send(resultXml(result))
            return true
        }

        private fun resultXml(result: String) =
            "<device_data><sequence>0</sequence><device_id>$DEVICE_ID</device_id><data><type>$resultType</type>" +
                "<success>true</success><code>SUCCESS</code><resultdata>${escape(result)}</resultdata></data>" +
                "<data_id>${dataCounter.incrementAndGet()}</data_id></device_data>"

        private fun read(): String? {
            val buf = ByteArrayOutputStream()
            while (true) {
                val b = inp.read()
                if (b == -1) return null
                if (b == 0) return buf.toString(Charsets.UTF_8.name())
                buf.write(b)
            }
        }

        private fun send(xml: String) = synchronized(out) {
            out.write(xml.toByteArray(Charsets.UTF_8))
            out.write(0)
            out.flush()
        }
    }

    companion object {
        const val DEVICE_ID = "local_TSE"

        /** TLS-Kontext mit dem festen Testzertifikat des Mocks („MOCK TSE – KEIN GUELTIGES ZERTIFIKAT“). */
        fun tlsContext(): SSLContext {
            val ks = KeyStore.getInstance("PKCS12")
            MockTseServer::class.java.getResourceAsStream("/mock-tse.p12")!!.use { ks.load(it, "mocktse".toCharArray()) }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "mocktse".toCharArray()) }
            return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        }

        private val factory = DocumentBuilderFactory.newInstance().apply {
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }

        fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        private fun parse(xml: String): Element = try {
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
        } catch (e: Exception) {
            throw IOException("Ungültiges XML", e)
        }

        private fun Element.child(tag: String): Element? = getElementsByTagName(tag).let { if (it.length > 0) it.item(0) as Element else null }
        private fun Element.text(tag: String): String? = child(tag)?.textContent?.trim()
    }
}
