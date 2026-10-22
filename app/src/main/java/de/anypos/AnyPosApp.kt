package de.anypos

import android.app.Application
import android.content.Context
import de.anypos.backup.BackupService
import de.anypos.backup.KeystoreSecretStore
import de.anypos.backup.SecretStore
import de.anypos.data.PosDatabase
import de.anypos.data.Role
import de.anypos.data.User
import de.anypos.domain.AuditLog
import de.anypos.domain.ClosingService
import de.anypos.domain.CustomerDisplayHub
import de.anypos.domain.DisplayState
import de.anypos.display.DisplayServer
import de.anypos.domain.Key
import de.anypos.domain.PinHasher
import de.anypos.domain.PosService
import de.anypos.domain.SettingsRepository
import de.anypos.export.ExportService
import de.anypos.hardware.PrinterService
import de.anypos.hardware.Company
import de.anypos.tse.ClientIdStore
import de.anypos.tse.EposXmlSocketTransport
import de.anypos.tse.EscPosTseTransport
import de.anypos.tse.EpsonTse
import de.anypos.tse.NoTse
import de.anypos.tse.ReadOnlyGuardTransport
import de.anypos.tse.SimulatedTse
import de.anypos.tse.TlsPinning
import de.anypos.tse.Tse
import de.anypos.tse.TseCredentials
import de.anypos.tse.TseLoginException
import de.anypos.tse.UsbByteLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import javax.net.SocketFactory

class AnyPosApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Vorbereitete Wiederherstellung übernehmen, bevor die Datenbank geöffnet wird
        val previous = BackupService.applyPendingRestore(this)
        container = AppContainer(this, restoredFrom = previous)
    }
}

/** Manuelle Dependency Injection: alle langlebigen Dienste an einer Stelle. */
class AppContainer(context: Context, private val restoredFrom: String? = null) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = PosDatabase.build(context)
    val settings = SettingsRepository(db.settings(), scope)
    val audit = AuditLog(db.audit())
    private val appContext = context.applicationContext

    /** Bondruck und TSE-Zugang über das USB-Kabel nutzen dieselbe Leitung: immer nur einer zur Zeit. */
    private val printerLine = Mutex()
    val printer = PrinterService(appContext, settings, printerLine)
    val backup = BackupService(context.applicationContext, db, settings, audit)

    private var tseConfig: String? = null
    private var tseInstance: Tse = NoTse()

    /** Ablage für PIN/Secret der TSE (Android-Keystore). */
    val secrets: SecretStore = KeystoreSecretStore()

    /** client_id der letzten ePOS-Sitzung, überdauert Neustarts (alte Sitzung wird dann per <disconnect> beendet). */
    private val tseClientIds = object : ClientIdStore {
        private val prefs = context.applicationContext.getSharedPreferences("tse_session", Context.MODE_PRIVATE)
        override fun load(): String? = prefs.getString("client_id", null)
        override fun save(clientId: String?) { prefs.edit().putString("client_id", clientId).apply() }
    }

    /** TSE-Host: eigener Eintrag oder, wenn leer, der Netzwerkdrucker. */
    fun tseHost(): String = settings[Key.TSE_HOST].ifBlank { settings[Key.PRINTER_HOST] }

    fun tseTls(): Boolean = settings[Key.TSE_TLS] != "false"

    /** Weg zur TSE im Drucker: "usb" (ESC/POS über das Druckerkabel) oder "tcp" (ePOS Device XML); leer = wie der Drucker. */
    fun tseConnection(): String = settings[Key.TSE_CONNECTION].ifBlank { if (settings[Key.PRINTER_MODE] == "usb") "usb" else "tcp" }

    /** Port: eigener Eintrag oder 8143 (TLS) bzw. 8009 (ohne TLS). */
    fun tsePort(): Int = settings[Key.TSE_PORT].toIntOrNull() ?: if (tseTls()) 8143 else 8009

    private suspend fun tseCredentials(): TseCredentials? {
        val pin = settings[Key.TSE_TIMEADMIN_PIN].ifBlank { return null }
        val secret = settings[Key.TSE_SHARED_SECRET]
        return try {
            TseCredentials(secrets.decrypt(pin), if (secret.isBlank()) "" else secrets.decrypt(secret))
        } catch (e: Exception) {
            // z. B. nach Wiederherstellung auf einem anderen Gerät: Keystore-Schlüssel fehlt
            throw TseLoginException("TSE-PIN/Secret nicht lesbar – bitte in den Einstellungen neu eingeben")
        }
    }

    /**
     * Liefert die TSE passend zu den Einstellungen; bei Änderung wird neu aufgebaut. Die TSE gehört immer genau
     * einer Verbindung – die alte wird deshalb zuerst geschlossen, bevor die neue entsteht.
     */
    @Synchronized
    fun tse(): Tse {
        val mode = settings[Key.TSE_MODE]
        val host = tseHost()
        val port = tsePort()
        val tls = tseTls()
        val fingerprint = settings[Key.TSE_TLS_FINGERPRINT]
        val usb = tseConnection() == "usb"
        val config = when {
            mode != "epson" -> mode
            usb -> "epson|usb"
            else -> "epson|$host|$port|$tls|$fingerprint"
        }
        if (config != tseConfig) {
            (tseInstance as? EpsonTse)?.let { old -> runBlocking(Dispatchers.IO) { withTimeoutOrNull(3_000) { old.shutdown() } } }
            tseInstance = when (mode) {
                "simulated" -> runBlocking(Dispatchers.IO) {
                    SimulatedTse(
                        txStart = db.receipts().maxTseTx(SimulatedTse.SERIAL),
                        sigStart = db.receipts().maxTseSig(SimulatedTse.SERIAL),
                    )
                }
                "epson" -> EpsonTse(
                    ReadOnlyGuardTransport(
                        if (usb) {
                            EscPosTseTransport(UsbByteLink(appContext), deviceLock = printerLine)
                        } else {
                            EposXmlSocketTransport(
                                host, port,
                                socketFactory = if (tls) TlsPinning.pinnedSocketFactory(fingerprint) else SocketFactory.getDefault(),
                                clientIdStore = tseClientIds,
                            )
                        },
                        writeEnabled = { settings[Key.TSE_WRITE_ENABLED] == "true" },
                        onBlocked = { function -> audit.log("System", "TSE_SCHREIBSPERRE", "$function abgewiesen") },
                    ),
                    clientId = { settings[Key.REGISTER_ID] },
                    credentials = ::tseCredentials,
                    // USB ist eine direkte Leitung, die PIN geht dort nicht über ein Netz
                    encrypted = usb || tls,
                )
                else -> NoTse()
            }
            tseConfig = config
        }
        return tseInstance
    }

    val pos = PosService(db, ::tse, settings, audit, scope = scope)

    /** Zustand für Kundendisplays; die Kasse veröffentlicht, der [DisplayServer] verteilt. */
    val customerDisplay = CustomerDisplayHub()
    private var displayServer: DisplayServer? = null

    /** Fehler beim Start des Display-Servers (z. B. Port belegt), für die Einstellungen. */
    @Volatile var displayError: String? = null
        private set

    private val displayPage by lazy { appContext.assets.open("customer_display.html").use { it.readBytes().toString(Charsets.UTF_8) } }

    /** Adresse für Displays: eigener Eintrag oder die IP des Geräts im WLAN; null = aus oder ohne Netz. */
    fun customerDisplayUrl(): String? {
        if (!settings.bool(Key.CUSTOMER_DISPLAY)) return null
        val token = settings[Key.CUSTOMER_DISPLAY_TOKEN].ifBlank { return null }
        val host = settings[Key.CUSTOMER_DISPLAY_HOST].ifBlank { DisplayServer.localAddresses().firstOrNull() ?: return null }
        return "http://$host:${settings[Key.CUSTOMER_DISPLAY_PORT]}/d/$token"
    }

    /** Startet/stoppt den Display-Server passend zu den Einstellungen (Ein/Aus, Port, Schlüssel). */
    private fun watchCustomerDisplay() = scope.launch {
        settings.values.map { listOf(it[Key.CUSTOMER_DISPLAY.name], it[Key.CUSTOMER_DISPLAY_PORT.name], it[Key.CUSTOMER_DISPLAY_TOKEN.name]) }
            .distinctUntilChanged().collect {
                displayServer?.close()
                displayServer = null
                displayError = null
                if (!settings.bool(Key.CUSTOMER_DISPLAY)) return@collect
                val token = settings[Key.CUSTOMER_DISPLAY_TOKEN]
                if (token.isBlank()) {
                    settings.put(Key.CUSTOMER_DISPLAY_TOKEN, DisplayServer.newToken()) // löst erneuten Durchlauf aus
                    return@collect
                }
                if (customerDisplay.state.value is DisplayState.Idle) customerDisplay.publish(DisplayState.Idle(settings[Key.COMPANY_NAME]))
                displayServer = try {
                    DisplayServer(customerDisplay, token, settings.int(Key.CUSTOMER_DISPLAY_PORT), { displayPage }).start()
                } catch (e: Exception) {
                    displayError = "Kundendisplay konnte nicht starten: ${e.message}"
                    audit.log("System", "KUNDENDISPLAY_FEHLER", e.message.orEmpty())
                    null
                }
            }
    }
    val closing = ClosingService(db, pos, audit)
    val exports = ExportService(context.applicationContext, db, pos, settings, audit, ::company)

    fun company() = Company(
        name = settings[Key.COMPANY_NAME],
        street = settings[Key.COMPANY_STREET],
        zip = settings[Key.COMPANY_ZIP],
        city = settings[Key.COMPANY_CITY],
        country = settings[Key.COMPANY_COUNTRY],
        taxNumber = settings[Key.COMPANY_TAX_NUMBER],
        vatId = settings[Key.COMPANY_VAT_ID],
        registerId = settings[Key.REGISTER_ID],
        footer = settings[Key.RECEIPT_FOOTER],
    )

    /** Ersteinrichtung und Wiederanlauf; wird beim App-Start einmal aufgerufen. */
    suspend fun bootstrap() {
        db.masterData().insertTaxRates(PosDatabase.DEFAULT_TAX_RATES)
        if (db.users().count() == 0) {
            db.users().upsert(User(name = "Admin", pinHash = PinHasher.hash(DEFAULT_ADMIN_PIN), role = Role.ADMIN))
            audit.log("System", "ERSTEINRICHTUNG", "Benutzer Admin angelegt")
        }
        settings.load() // stellt sicher, dass Einstellungen vor dem ersten Vorgang geladen sind
        restoredFrom?.let { audit.log("System", "WIEDERHERSTELLUNG", "Sicherung übernommen, bisherige Datenbank aufbewahrt als $it") }
        pos.recoverOpenReceipts()
        watchCustomerDisplay()
        // Abgleich mit der TSE im Hintergrund (Netzwerk), damit der Start der App nicht wartet
        scope.launch { pos.reconcile() }
    }

    companion object {
        const val DEFAULT_ADMIN_PIN = "1234"
    }
}
