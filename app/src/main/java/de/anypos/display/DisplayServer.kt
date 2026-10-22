package de.anypos.display

import de.anypos.domain.CustomerDisplayHub
import de.anypos.payment.SumUpSwitch
import de.anypos.payment.SumUpSwitchBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kleiner Webserver für Kundendisplays im WLAN (ohne Bibliothek, nur HTTP/1.1 GET/POST):
 *
 * - `GET  /d/<schlüssel>`         Anzeigeseite ([page])
 * - `GET  /d/<schlüssel>/events`  Server-Sent Events: aktueller Zustand sofort, danach jede Änderung; alle 15 s ein Lebenszeichen
 * - `POST /d/<schlüssel>/tip`     Trinkgeldauswahl `{"ask":"…","option":"…"}` – nur für die gerade offene Frage
 * - `GET  /d/<schlüssel>/sumup/<einmalcode>?smp-status=…`  Rückruf der SumUp-App (Payment Switch) → [switch]
 * - `POST /d/<schlüssel>/sumup-open/<einmalcode>`         Kunde hat „Mit Karte zahlen“ angetippt
 *
 * Alles andere und jeder falsche Schlüssel: 404. Die Seite zeigt nur, was der Kunde ohnehin sieht; die TSE ist nicht beteiligt.
 */
class DisplayServer(
    private val hub: CustomerDisplayHub,
    private val token: String,
    port: Int,
    private val page: () -> String,
    bindAddress: InetAddress? = null,
    private val heartbeatMs: Long = 15_000,
    private val maxConnections: Int = 32,
    /** Kartenzahlung über die SumUp-App am Display; null = nicht angeboten. */
    private val switch: SumUpSwitchBridge? = null,
) : Closeable {
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(bindAddress, port))
    }
    val port: Int get() = server.localPort

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = AtomicInteger()

    fun start(): DisplayServer {
        Thread({
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: IOException) { break }
                if (connections.incrementAndGet() > maxConnections) {
                    connections.decrementAndGet()
                    runCatching { socket.close() }
                    continue
                }
                scope.launch {
                    try { handle(socket) } catch (e: IOException) { /* Display weg */ } finally {
                        runCatching { socket.close() }
                        connections.decrementAndGet()
                    }
                }
            }
        }, "display-server").apply { isDaemon = true; start() }
        return this
    }

    override fun close() {
        runCatching { server.close() }
        scope.cancel()
    }

    private suspend fun handle(socket: Socket) {
        socket.soTimeout = 10_000
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        val requestLine = readLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            if (headers.size > 50) return
        }
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].substringBefore('?')
        val segments = path.trim('/').split('/')
        if (segments.size !in 2..4 || segments[0] != "d" || !sameToken(segments[1])) return respond(out, 404, "text/plain", "Nicht gefunden")
        val home = "/d/" + segments[1]
        when {
            method == "GET" && segments.size == 4 && segments[2] == "sumup" && switch != null -> {
                // Rückruf der SumUp-App: Ergebnis übernehmen (nur mit passendem Einmal-Code), dann zurück zum Display
                val accepted = switch.deliver(segments[3], SumUpSwitch.params(parts[1]))
                respond(out, 200, "text/html; charset=utf-8",
                    "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width\">" +
                        "<p style=\"font:20px sans-serif\">${if (accepted) "Zahlung übermittelt" else "Rückmeldung bereits verarbeitet"} – zurück zum Display …</p>" +
                        "<script>location.replace(\"$home\")</script>")
            }
            method == "POST" && segments.size == 4 && segments[2] == "sumup-open" && switch != null ->
                if (switch.markOpened(segments[3])) respond(out, 204, null, "") else respond(out, 409, "text/plain", "Keine offene Zahlung")
            segments.size == 4 -> respond(out, 404, "text/plain", "Nicht gefunden")
            method == "GET" && segments.size == 2 -> respond(out, 200, "text/html; charset=utf-8", page())
            method == "GET" && segments[2] == "events" -> events(socket, out)
            method == "POST" && segments[2] == "tip" -> {
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                if (length !in 1..1024) return respond(out, 400, "text/plain", "Ungültig")
                val body = ByteArray(length).also { readFully(input, it) }.toString(Charsets.UTF_8)
                val accepted = runCatching {
                    val o = Json.parseToJsonElement(body) as JsonObject
                    hub.choose(o.getValue("ask").jsonPrimitive.content, o.getValue("option").jsonPrimitive.content)
                }.getOrDefault(false)
                if (accepted) respond(out, 204, null, "") else respond(out, 409, "text/plain", "Keine offene Frage")
            }
            else -> respond(out, 404, "text/plain", "Nicht gefunden")
        }
    }

    /** Server-Sent Events: hält die Verbindung offen, bis das Display sie schließt. */
    private suspend fun events(socket: Socket, out: OutputStream) {
        socket.soTimeout = 0
        write(out, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-store\r\n" +
            "Connection: keep-alive\r\nX-Accel-Buffering: no\r\n\r\nretry: 2000\n\n")
        coroutineScope {
            val beat = launch {
                while (true) {
                    delay(heartbeatMs)
                    write(out, "event: ping\ndata: 1\n\n")
                }
            }
            try {
                hub.state.map { CustomerDisplayHub.json(it) }.distinctUntilChanged().collect { json ->
                    write(out, "data: $json\n\n")
                }
            } finally {
                beat.cancel()
            }
        }
    }

    private fun write(out: OutputStream, text: String) = synchronized(out) {
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun respond(out: OutputStream, status: Int, type: String?, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 409 -> "Conflict"; else -> "Not Found" }
        val head = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            if (type != null) append("Content-Type: $type\r\n")
            append("Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
        }
        synchronized(out) {
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.write(bytes)
            out.flush()
        }
    }

    /** Vergleich in konstanter Zeit, damit der Schlüssel nicht zeichenweise erraten werden kann. */
    private fun sameToken(candidate: String) =
        token.isNotEmpty() && MessageDigest.isEqual(candidate.toByteArray(), token.toByteArray())

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString(Charsets.US_ASCII.name())
            if (b == '\n'.code) return buf.toString(Charsets.US_ASCII.name()).trimEnd('\r')
            buf.write(b)
            if (buf.size() > 8 * 1024) throw IOException("Zeile zu lang")
        }
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var read = 0
        while (read < target.size) {
            val n = input.read(target, read, target.size - read)
            if (n < 0) throw IOException("Anfrage unvollständig")
            read += n
        }
    }

    companion object {
        /** Zufälliger Schlüssel für die Adresse (128 Bit, URL-tauglich). */
        fun newToken(): String {
            val bytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        /** IPv4-Adressen des Geräts im lokalen Netz (für die Anzeige der Display-Adresse). */
        fun localAddresses(): List<String> = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { it.hostAddress!! }
        }.getOrDefault(emptyList())
    }
}
