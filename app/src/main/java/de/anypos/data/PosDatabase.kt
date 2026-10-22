package de.anypos.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/** Aktuelle Schemaversion; bei Änderungen erhöhen und AutoMigration ergänzen. */
const val DB_VERSION = 4

class Converters {
    @TypeConverter fun roleToString(role: Role): String = role.name
    @TypeConverter fun stringToRole(value: String): Role = Role.valueOf(value)
}

@Database(
    entities = [
        TaxRate::class, Category::class, Article::class, User::class,
        Receipt::class, ReceiptLine::class, Payment::class, DailyClosing::class,
        AuditEntry::class, Setting::class, ImportRun::class, TseInfo::class, Voucher::class, CardAttempt::class,
    ],
    version = DB_VERSION,
    exportSchema = true,
    autoMigrations = [
        // 1 -> 2: GTIN/Warengruppe je Belegposition, Tabelle tse_info (DSFinV-K)
        AutoMigration(from = 1, to = 2),
        // 2 -> 3: Retouren (Verweisart, Positionsverweis), Gutscheine
        AutoMigration(from = 2, to = 3),
        // 3 -> 4: Kartenterminal (Transaktionscode/Karte je Zahlung, Tabelle card_attempt)
        AutoMigration(from = 3, to = 4),
    ],
)
@TypeConverters(Converters::class)
abstract class PosDatabase : RoomDatabase() {
    abstract fun masterData(): MasterDataDao
    abstract fun users(): UserDao
    abstract fun receipts(): ReceiptDao
    abstract fun closings(): ClosingDao
    abstract fun audit(): AuditDao
    abstract fun settings(): SettingDao
    abstract fun importRuns(): ImportRunDao
    abstract fun tseInfo(): TseInfoDao
    abstract fun vouchers(): VoucherDao
    abstract fun cardAttempts(): CardAttemptDao

    companion object {
        const val VERSION = DB_VERSION

        /** Steuersätze nach DSFinV-K USt-Schlüssel. */
        val DEFAULT_TAX_RATES = listOf(
            TaxRate(1, "A", 1900, 1, "19 %"),
            TaxRate(2, "B", 700, 2, "7 %"),
            // Durchschnittssätze nur für pauschalierende Land-/Forstwirte; Sätze vor Nutzung prüfen.
            TaxRate(3, "C", 780, 3, "7,8 % Durchschnittssatz §24 Abs.1 Nr.3"),
            TaxRate(4, "D", 550, 4, "5,5 % Durchschnittssatz §24 Abs.1 Nr.1"),
            TaxRate(5, "E", 0, 5, "0 % nicht steuerbar"),
            TaxRate(6, "F", 0, 6, "0 % steuerfrei"),
        )

        fun build(context: Context): PosDatabase =
            Room.databaseBuilder(context, PosDatabase::class.java, "anypos.db").build()
    }
}
