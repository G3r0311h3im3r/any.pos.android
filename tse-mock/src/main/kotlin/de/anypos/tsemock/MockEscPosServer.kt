package de.anypos.tsemock

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/**
 * TSE-Zugriff per ESC/POS über einen Byte-Strom, wie der Drucker ihn über USB, seriell oder Port 9100 anbietet
 * (Developer's Guide Kap. 5; Ablauf nach EpsonTseDemo 2.4.1, `TSEAccessByEscPosNetwork`/`…Serial`):
 *
 * - TSE freischalten `10 14 06 01 03 01 03 14 01 06 02 08`, freigeben `10 14 06 01 01 01 03 14 01 06 02 08`;
 *   Antwort `37 5C <code> 00`, `30` = OK.
 * - JSON senden `1C 28 50 pL pH 49 00 00 00 <json>` (pL/pH = Länge JSON + 4).
 * - Antwort in Blöcken `53 27 <status> 00 sL sH <daten>`, jeder Block wird mit `06` quittiert; `status 40` = letzter Block.
 *
 * Wie beim XML-Server gehört die TSE genau einer Verbindung; bricht sie ab, bleibt die TSE
 * [MockConfig.reconnectWindowMs] gesperrt. Alle anderen Bytes gelten als Druckdaten ([printed]).
 * Mit [noise] streut der Drucker Statusbytes (wie automatische Statusmeldungen) vor jeden Antwortblock.
 */
class MockEscPosServer(
    val tse: MockTse,
    port: Int = 0,
    bindAddress: InetAddress? = InetAddress.getLoopbackAddress(),
    /** Größe der Antwortblöcke; klein = viele Blöcke. */
    private val blockSize: Int = 1024,
    var noise: Boolean = false,
    /** Wartezeit auf die Quittung `06` eines Blocks. */
    private val ackTimeoutMs: Int = 5_000,
    private val log: (String) -> Unit = {},
) : Closeable {

    private val server = ServerSocket(port, 50, bindAddress)
    val port: Int get() = server.localPort

    private val lock = Any()
    private val sessions: MutableSet<Session> = Collections.synchronizedSet(mutableSetOf())
    private val counter = AtomicLong()
    private var owner: Session? = null
    private var lostAt: Long? = null
    private val faults = ArrayDeque<Pair<Fault, String?>>()

    private val clock get() = tse.config.clock

    /** Anzahl Druckdaten-Bytes (alles außerhalb der TSE-Befehle). */
    val printed = AtomicLong()

    /** JSON-Befehle, die ohne freigeschaltete TSE kamen (dürfen nie vorkommen). */
    val unownedRequests = AtomicLong()

    /** Freischalt-Versuche, die wegen Belegung abgelehnt wurden. */
    val rejectedEnables = AtomicLong()

    val blockedAfterDisconnect: Boolean
        get() = synchronized(lock) { lostAt?.let { clock() - it <= tse.config.reconnectWindowMs } == true }

    val isTseEnabled: Boolean get() = synchronized(lock) { owner != null }

    fun start(): MockEscPosServer {
        Thread({
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: IOException) { break }
                val session = Session(socket, "esc" + counter.incrementAndGet())
                sessions += session
                Thread(session::run, "mock-escpos-${session.id}").apply { isDaemon = true; start() }
            }
        }, "mock-escpos-accept").apply { isDaemon = true; start() }
        return this
    }

    fun injectFault(fault: Fault, function: String? = null) = synchronized(lock) { faults.addLast(fault to function) }

    fun dropAllConnections() = sessions.toList().forEach { it.socket.close() }

    override fun close() {
        server.close()
        dropAllConnections()
    }

    private fun takeFault(function: String): Fault? = synchronized(lock) {
        faults.firstOrNull { it.second == null || it.second == function }?.also { faults.remove(it) }?.first
    }

    private inner class Session(val socket: Socket, val id: String) {
        private val inp: InputStream = socket.getInputStream().buffered()
        private val out: OutputStream = socket.getOutputStream()

        fun run() {
            try {
                while (true) {
                    when (val b = inp.read()) {
                        -1 -> break
                        0x10 -> if (!dleDc4()) printed.incrementAndGet()
                        0x1C -> if (!fsParen()) break
                        else -> printed.incrementAndGet()
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
                        lostAt = clock()
                        log("$id: Verbindung abgebrochen, TSE ${tse.config.reconnectWindowMs / 1000} s gesperrt")
                    }
                }
            }
        }

        /** `10 14 06 01 <03|01> 01 03 14 01 06 02 08`; false = keine TSE-Freischaltung (Druckdaten). */
        private fun dleDc4(): Boolean {
            val rest = readExactly(11)
            if (rest[0] != 0x14.toByte() || rest[1] != 0x06.toByte() || !rest.copyOfRange(4, 11).contentEquals(TAIL)) {
                printed.addAndGet(11)
                return false
            }
            val code = when (rest[3].toInt()) {
                0x03 -> synchronized(lock) {
                    when {
                        owner === this -> OK
                        owner != null || blockedAfterDisconnect -> { rejectedEnables.incrementAndGet(); BUSY }
                        else -> { owner = this; lostAt = null; OK }
                    }
                }
                0x01 -> synchronized(lock) { if (owner === this) owner = null; OK }
                else -> BUSY
            }
            log("$id: TSE ${if (rest[3].toInt() == 0x03) "freischalten" else "freigeben"} ${if (code == OK) "OK" else "belegt"}")
            write(byteArrayOf(0x37, 0x5C, code.toByte(), 0x00))
            return true
        }

        /** `1C 28 50 pL pH 49 00 00 00 <json>`; false = Verbindung absichtlich getrennt. */
        private fun fsParen(): Boolean {
            val head = readExactly(4)
            if (head[0] != 0x28.toByte() || head[1] != 0x50.toByte()) {
                printed.addAndGet(5)
                return true
            }
            val len = (head[2].toInt() and 0xFF) or ((head[3].toInt() and 0xFF) shl 8)
            val payload = readExactly(len)
            val request = String(payload, 4, len - 4, Charsets.UTF_8)
            val function = Regex("\"function\"\\s*:\\s*\"([^\"]+)\"").find(request)?.groupValues?.get(1) ?: "?"
            if (synchronized(lock) { owner !== this }) {
                unownedRequests.incrementAndGet()
                log("$id: $function ohne freigeschaltete TSE – ignoriert")
                return true
            }
            when (takeFault(function)) {
                Fault.DROP_BEFORE_EXECUTE -> { log("$id: $function – Störung: Abbruch vor Ausführung"); return false }
                Fault.DROP_AFTER_EXECUTE -> { tse.handle(request); log("$id: $function – Störung: Abbruch nach Ausführung"); return false }
                Fault.NO_ANSWER -> { tse.handle(request); log("$id: $function – Störung: keine Antwort"); return true }
                null -> Unit
            }
            val result = tse.handle(request).toByteArray(Charsets.UTF_8)
            log("$id: $function → ${Regex("\"result\"\\s*:\\s*\"([^\"]+)\"").find(String(result, Charsets.UTF_8))?.groupValues?.get(1)}")
            sendBlocks(result)
            return true
        }

        private fun sendBlocks(data: ByteArray) {
            var offset = 0
            do {
                val n = minOf(blockSize, data.size - offset)
                val last = offset + n >= data.size
                val block = ByteArrayOutputStream()
                if (noise) block.write(byteArrayOf(0x14, 0x00, 0x00, 0x0F))
                block.write(byteArrayOf(0x53, 0x27, if (last) 0x40 else 0x41, 0x00, (n and 0xFF).toByte(), (n shr 8).toByte()))
                block.write(data, offset, n)
                write(block.toByteArray())
                awaitAck()
                offset += n
            } while (!last)
        }

        /** Wartet auf `06`; andere Bytes davor gelten als Druckdaten. */
        private fun awaitAck() {
            socket.soTimeout = ackTimeoutMs
            try {
                while (true) {
                    val b = inp.read()
                    if (b == -1) throw IOException("Verbindung beim Warten auf Quittung geschlossen")
                    if (b == 0x06) return
                    printed.incrementAndGet()
                }
            } catch (e: SocketTimeoutException) {
                throw IOException("keine Quittung", e)
            } finally {
                socket.soTimeout = 0
            }
        }

        private fun readExactly(n: Int): ByteArray {
            val buf = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = inp.read(buf, read, n - read)
                if (r < 0) throw IOException("Verbindung geschlossen")
                read += r
            }
            return buf
        }

        private fun write(bytes: ByteArray) = synchronized(out) { out.write(bytes); out.flush() }
    }

    companion object {
        private const val OK = 0x30
        private const val BUSY = 0x31
        private val TAIL = byteArrayOf(0x01, 0x03, 0x14, 0x01, 0x06, 0x02, 0x08)
    }
}
