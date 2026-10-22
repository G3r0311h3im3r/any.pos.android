package de.anypos.tse

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import de.anypos.hardware.PrinterException
import de.anypos.hardware.UsbPrinterTransport
import java.io.IOException

/**
 * USB-Leitung zum Epson-Drucker (Bulk-OUT + Bulk-IN) für den ESC/POS-Zugang zur TSE, die im Drucker steckt.
 * Dasselbe Gerät wie beim Bondruck ([UsbPrinterTransport]); wer gerade zugreift, regelt die gemeinsame Sperre.
 */
class UsbByteLink(context: Context) : ByteLink {
    private val printer = UsbPrinterTransport(context)
    private val usb = context.getSystemService(UsbManager::class.java)

    private var device: UsbDevice? = null
    private var conn: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var out: UsbEndpoint? = null
    private var inp: UsbEndpoint? = null

    override val isOpen get() = conn != null
    override val description get() = printer.description

    override suspend fun open() {
        if (isOpen) return
        val d = printer.findPrinter() ?: throw TseTransportException(UsbPrinterTransport.NOT_FOUND)
        try {
            printer.ensurePermission(d)
        } catch (e: PrinterException) {
            throw TseTransportException(e.message ?: "USB-Berechtigung fehlt", e)
        }
        val (i, o, n) = printer.bulkInOut(d)
            ?: throw TseTransportException("Drucker hat keinen USB-Rückkanal (Bulk-IN) – TSE über USB nicht möglich")
        val c = usb.openDevice(d) ?: throw TseTransportException("USB-Verbindung zum Drucker konnte nicht geöffnet werden")
        if (!c.claimInterface(i, true)) {
            c.close()
            throw TseTransportException("USB-Schnittstelle des Druckers belegt")
        }
        device = d; conn = c; iface = i; out = o; inp = n
    }

    override fun write(data: ByteArray) {
        val c = conn ?: throw IOException("nicht verbunden")
        var offset = 0
        while (offset < data.size) {
            val len = minOf(CHUNK, data.size - offset)
            val sent = c.bulkTransfer(out, data, offset, len, WRITE_TIMEOUT_MS)
            if (sent <= 0) throw IOException("USB-Übertragung zum Drucker abgebrochen")
            offset += sent
        }
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val c = conn ?: throw IOException("nicht verbunden")
        val n = c.bulkTransfer(inp, buffer, buffer.size, timeoutMs.coerceAtLeast(1))
        if (n > 0) return n
        // -1 heißt Ablauf oder Fehler; abgezogenes Kabel / ausgeschalteter Drucker ist ein Abbruch
        if (usb.deviceList.values.none { it.deviceName == device?.deviceName }) throw IOException("Drucker vom USB getrennt")
        return 0
    }

    override fun close() {
        conn?.let { c ->
            runCatching { iface?.let { c.releaseInterface(it) } }
            runCatching { c.close() }
        }
        conn = null; device = null; iface = null; out = null; inp = null
    }

    private companion object {
        const val CHUNK = 16 * 1024
        const val WRITE_TIMEOUT_MS = 5_000
    }
}
