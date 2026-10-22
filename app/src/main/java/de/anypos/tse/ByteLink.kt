package de.anypos.tse

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Rohe Byte-Leitung zum Drucker für den ESC/POS-Zugang zur TSE ([EscPosTseTransport]):
 * USB ([UsbByteLink]) im Betrieb, TCP ([SocketByteLink]) für Tests gegen die Mock-TSE.
 * [write]/[read] blockieren und werden nur auf einem IO-Thread aufgerufen.
 */
interface ByteLink {
    val isOpen: Boolean
    val description: String

    /** Leitung öffnen; wirft [TseTransportException], wenn der Drucker nicht erreichbar ist. */
    suspend fun open()

    /** Schreibt alle Bytes; [IOException] bei abgebrochener Leitung. */
    fun write(data: ByteArray)

    /** Liest höchstens `buffer.size` Bytes; 0 nach Ablauf von [timeoutMs]; [IOException] bei abgebrochener Leitung. */
    fun read(buffer: ByteArray, timeoutMs: Int): Int

    fun close()
}

/** ESC/POS über TCP (RAW-Port 9100). Im Betrieb nicht vorgesehen (Netzwerk nutzt ePOS Device XML mit TLS). */
class SocketByteLink(private val host: String, private val port: Int = 9100, private val connectTimeoutMs: Int = 5_000) : ByteLink {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override val isOpen get() = socket?.let { it.isConnected && !it.isClosed } == true
    override val description get() = "ESC/POS $host:$port"

    override suspend fun open() {
        if (isOpen) return
        val address = InetSocketAddress(host, port)
        val s = try {
            Socket().apply { tcpNoDelay = true; connect(address, connectTimeoutMs) }
        } catch (e: IOException) {
            throw TseTransportException("Drucker $host:$port nicht erreichbar: ${e.message}", e)
        }
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
    }

    override fun write(data: ByteArray) {
        val out = output ?: throw IOException("nicht verbunden")
        out.write(data)
        out.flush()
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val s = socket ?: throw IOException("nicht verbunden")
        val inp = input ?: throw IOException("nicht verbunden")
        s.soTimeout = timeoutMs.coerceAtLeast(1)
        return try {
            val n = inp.read(buffer)
            if (n < 0) throw IOException("Verbindung vom Drucker geschlossen")
            n
        } catch (e: SocketTimeoutException) {
            0
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }
}
