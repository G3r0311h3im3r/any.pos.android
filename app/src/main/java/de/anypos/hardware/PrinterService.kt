package de.anypos.hardware

import android.content.Context
import de.anypos.domain.Key
import de.anypos.domain.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serialisiert alle Druck- und Schubladenaufträge über den konfigurierten Transport. */
class PrinterService(private val context: Context, private val settings: SettingsRepository) {
    private val mutex = Mutex()

    val columns: Int get() = settings.int(Key.PRINTER_COLUMNS)
    val configured: Boolean get() = settings[Key.PRINTER_MODE] != "none"

    fun transport(): PrinterTransport = when (settings[Key.PRINTER_MODE]) {
        "usb" -> UsbPrinterTransport(context)
        "tcp" -> TcpPrinterTransport(settings[Key.PRINTER_HOST])
        else -> NoPrinterTransport()
    }

    suspend fun print(data: ByteArray) = withContext(Dispatchers.IO) {
        mutex.withLock { transport().send(data) }
    }

    /** Öffnet die Schublade über den Kick-Anschluss des Druckers. */
    suspend fun openDrawer() = print(EscPos(columns).init().drawerKick(settings.int(Key.DRAWER_PIN)).bytes())

    suspend fun testPage() = print(
        EscPos(columns).init().align(EscPos.Align.CENTER).bold(true).line("Any.POS Testdruck").bold(false)
            .line("Umlaute: ÄÖÜ äöü ß €").separator().align(EscPos.Align.LEFT)
            .row("Artikel", "12,34 €").qr("Any.POS").feed(3).cut().bytes()
    )
}
