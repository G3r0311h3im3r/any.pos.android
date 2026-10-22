package de.anypos.hardware

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume

/** Übertragungsweg zum Bondrucker. Austauschbar, damit später das Epson ePOS SDK eingesetzt werden kann. */
interface PrinterTransport {
    val description: String
    suspend fun send(data: ByteArray)
}

class PrinterException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Direkter USB-Zugriff (Android USB-Host-API) per Bulk-Transfer.
 * Unterstützt Druckerklasse (0x07) und Epson-Vendor-Klasse (TM-Drucker stehen ab Werk oft auf "Vendor Class").
 */
class UsbPrinterTransport(private val context: Context) : PrinterTransport {
    private val usb = context.getSystemService(UsbManager::class.java)

    override val description: String
        get() = findPrinter()?.let { "USB ${it.manufacturerName ?: ""} ${it.productName ?: ""} (${"%04X".format(it.vendorId)}:${"%04X".format(it.productId)})".trim() }
            ?: "USB – kein Drucker gefunden"

    fun findPrinter(): UsbDevice? =
        usb.deviceList.values.firstOrNull { d -> d.vendorId == EPSON_VENDOR_ID && bulkOut(d) != null }
            ?: usb.deviceList.values.firstOrNull { d ->
                (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_PRINTER } && bulkOut(d) != null
            }

    override suspend fun send(data: ByteArray) {
        val device = findPrinter() ?: throw PrinterException(NOT_FOUND)
        ensurePermission(device)
        val (iface, endpoint) = bulkOut(device) ?: throw PrinterException("Drucker hat keinen Bulk-OUT-Endpunkt")
        val conn = usb.openDevice(device) ?: throw PrinterException("USB-Verbindung konnte nicht geöffnet werden")
        try {
            if (!conn.claimInterface(iface, true)) throw PrinterException("USB-Schnittstelle belegt")
            var offset = 0
            while (offset < data.size) {
                val len = minOf(CHUNK, data.size - offset)
                val sent = conn.bulkTransfer(endpoint, data, offset, len, TIMEOUT_MS)
                if (sent <= 0) throw PrinterException("USB-Übertragung abgebrochen (Papier, Deckel, Kabel prüfen)")
                offset += sent
            }
            conn.releaseInterface(iface)
        } finally {
            conn.close()
        }
    }

    /** Fragt bei Bedarf die USB-Berechtigung an (Android-Dialog „Zugriff erlauben“). */
    suspend fun ensurePermission(device: UsbDevice) {
        if (!usb.hasPermission(device) && !requestPermission(device)) {
            throw PrinterException("USB-Berechtigung für den Drucker wurde nicht erteilt")
        }
    }

    /** Schnittstelle mit Bulk-OUT und Bulk-IN (Rückkanal für Statusmeldungen und TSE-Antworten). */
    fun bulkInOut(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val eps = (0 until iface.endpointCount).map { iface.getEndpoint(it) }.filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            val out = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
            val inp = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
            if (out != null && inp != null) return Triple(iface, out, inp)
        }
        return null
    }

    private fun bulkOut(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT) return iface to ep
            }
        }
        return null
    }

    private suspend fun requestPermission(device: UsbDevice): Boolean = withTimeout(60_000) {
        suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    context.unregisterReceiver(this)
                    if (cont.isActive) cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            val pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            usb.requestPermission(device, pi)
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
        }
    }

    companion object {
        const val EPSON_VENDOR_ID = 0x04B8
        const val ACTION_USB_PERMISSION = "de.anypos.USB_PERMISSION"
        const val NOT_FOUND = "Kein USB-Drucker gefunden. Kabel, Hub/OTG-Adapter und Stromversorgung prüfen."
        private const val CHUNK = 16 * 1024
        private const val TIMEOUT_MS = 5_000
    }
}

/** Netzwerkdrucker (RAW-Port 9100). Nur für Tests/Alternativbetrieb; Zielaufbau ist USB. */
class TcpPrinterTransport(private val host: String, private val port: Int = 9100) : PrinterTransport {
    override val description get() = "Netzwerk $host:$port"
    override suspend fun send(data: ByteArray) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 3_000)
                s.soTimeout = 5_000
                s.getOutputStream().apply { write(data); flush() }
            }
        } catch (e: Exception) {
            throw PrinterException("Netzwerkdrucker $host nicht erreichbar: ${e.message}", e)
        }
    }
}

/** Kein Drucker konfiguriert: Belege werden nur gespeichert (Belegausgabe dann z. B. per Anzeige). */
class NoPrinterTransport : PrinterTransport {
    override val description = "Kein Drucker"
    override suspend fun send(data: ByteArray) = throw PrinterException("Kein Drucker konfiguriert")
}
