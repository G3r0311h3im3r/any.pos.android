package de.anypos

import android.app.Application
import android.content.Context
import de.anypos.backup.BackupService
import de.anypos.data.PosDatabase
import de.anypos.data.Role
import de.anypos.data.User
import de.anypos.domain.AuditLog
import de.anypos.domain.ClosingService
import de.anypos.domain.Key
import de.anypos.domain.PinHasher
import de.anypos.domain.PosService
import de.anypos.domain.SettingsRepository
import de.anypos.export.ExportService
import de.anypos.hardware.PrinterService
import de.anypos.hardware.Company
import de.anypos.tse.EpsonTse
import de.anypos.tse.NoTse
import de.anypos.tse.SimulatedTse
import de.anypos.tse.Tse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

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
    val printer = PrinterService(context.applicationContext, settings)
    val backup = BackupService(context.applicationContext, db, settings, audit)

    private var tseMode: String? = null
    private var tseInstance: Tse = NoTse()

    @Synchronized
    fun tse(): Tse {
        val mode = settings[Key.TSE_MODE]
        if (mode != tseMode) {
            tseInstance = when (mode) {
                "simulated" -> runBlocking(Dispatchers.IO) {
                    SimulatedTse(
                        txStart = db.receipts().maxTseTx(SimulatedTse.SERIAL),
                        sigStart = db.receipts().maxTseSig(SimulatedTse.SERIAL),
                    )
                }
                "epson" -> EpsonTse()
                else -> NoTse()
            }
            tseMode = mode
        }
        return tseInstance
    }

    val pos = PosService(db, ::tse, settings, audit)
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
    }

    companion object {
        const val DEFAULT_ADMIN_PIN = "1234"
    }
}
