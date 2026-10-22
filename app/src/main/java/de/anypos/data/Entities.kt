package de.anypos.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Steuersatz. [dsfinvkKey] ist der USt-Schlüssel nach DSFinV-K (1 = 19 %, 2 = 7 %, …).
 * Prozentwerte werden in Basispunkten gespeichert (1900 = 19,00 %), damit nie mit Double gerechnet wird.
 */
@Entity(tableName = "tax_rate")
data class TaxRate(
    @PrimaryKey val id: Long,
    val code: String,
    val percentBp: Int,
    val dsfinvkKey: Int,
    val label: String,
)

@Entity(tableName = "category")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "article",
    indices = [Index("sku", unique = true), Index("externalId"), Index("barcode")],
)
data class Article(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val externalId: String? = null,
    val sku: String,
    val name: String,
    val priceCents: Long,
    val taxRateId: Long,
    val categoryId: Long? = null,
    val barcode: String? = null,
    val active: Boolean = true,
    /** Preis wird beim Verkauf abgefragt (Freipreis-/Warengruppenartikel). */
    val openPrice: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

enum class Role { ADMIN, MANAGER, CASHIER }

@Entity(tableName = "user", indices = [Index("name", unique = true)])
data class User(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val pinHash: String,
    val role: Role,
    val active: Boolean = true,
)

/** Vorgangstyp nach DSFinV-K (BON_TYP bzw. erster Teil der TSE-processData). */
object ReceiptType {
    const val SALE = "Beleg"
    const val ABORT = "AVBelegabbruch"
    const val TRAINING = "AVTraining"
    const val OTHER = "AVSonstige"
}

object ReceiptState {
    const val OPEN = "OPEN"
    const val FINISHED = "FINISHED"
    const val ABORTED = "ABORTED"
}

/**
 * Beleg / Vorgang. Wird nie gelöscht und nach Abschluss nicht mehr verändert.
 * Ein Storno entsteht als neuer Beleg mit negativen Positionen und Verweis auf das Original.
 */
@Entity(
    tableName = "receipt",
    indices = [Index("number", unique = true), Index("closingId"), Index("finishedAt")],
)
data class Receipt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: Long,
    val type: String = ReceiptType.SALE,
    val state: String = ReceiptState.OPEN,
    val createdAt: Long,
    val finishedAt: Long? = null,
    val userId: Long,
    val userName: String,
    val totalCents: Long = 0,
    val referencesReceiptId: Long? = null,
    val referencesReceiptNumber: Long? = null,
    /** [ReferenceType]; null bei Altbelegen mit Verweis bedeutet Storno. */
    val referenceType: String? = null,
    val closingId: Long? = null,
    val training: Boolean = false,
    // TSE-Daten
    val tseSerial: String? = null,
    val tseTxNumber: Long? = null,
    val tseStart: Long? = null,
    val tseEnd: Long? = null,
    val tseSigCounter: Long? = null,
    val tseSignature: String? = null,
    val tseSigAlgorithm: String? = null,
    val tseTimeFormat: String? = null,
    val tsePublicKey: String? = null,
    val tseProcessType: String? = null,
    val tseProcessData: String? = null,
    val tseError: String? = null,
) {
    val isCancellation: Boolean get() = referencesReceiptId != null && referenceType != ReferenceType.RETURN
    val isReturn: Boolean get() = referencesReceiptId != null && referenceType == ReferenceType.RETURN
}

object ReferenceType {
    /** Vollstorno des Originalbelegs. */
    const val CANCEL = "STORNO"
    /** Rückgabe einzelner Positionen/Mengen. */
    const val RETURN = "RETOURE"
}

/** Geschäftsvorfalltyp (DSFinV-K GV_TYP). */
object BusinessCase {
    const val SALE = "Umsatz"
    const val OPENING = "Anfangsbestand"
    const val DEPOSIT = "Einzahlung"
    const val PAYOUT = "Auszahlung"
    const val PRIVATE_IN = "Privateinlage"
    const val PRIVATE_OUT = "Privatentnahme"
    const val TRANSIT = "Geldtransit"
    const val DIFFERENCE = "DifferenzSollIst"
    const val SINGLE_VOUCHER_SALE = "EinzweckgutscheinKauf"
    const val SINGLE_VOUCHER_REDEEM = "EinzweckgutscheinEinloesung"
    const val MULTI_VOUCHER_SALE = "MehrzweckgutscheinKauf"
    const val MULTI_VOUCHER_REDEEM = "MehrzweckgutscheinEinloesung"
    /** Trinkgeld für Mitarbeitende: steuerfrei, kein Umsatz des Betriebs (USt-Schlüssel 5); auch die Auszahlung. */
    const val TIP_EMPLOYEE = "TrinkgeldAN"
    /** Trinkgeld an den Betrieb/Inhaber: Umsatz, umsatzsteuerpflichtig. */
    const val TIP_OWNER = "TrinkgeldAG"

    val cashMovements = listOf(OPENING, DEPOSIT, PAYOUT, PRIVATE_IN, PRIVATE_OUT, TRANSIT, DIFFERENCE)
    val voucherSales = listOf(SINGLE_VOUCHER_SALE, MULTI_VOUCHER_SALE)
    val voucherRedemptions = listOf(SINGLE_VOUCHER_REDEEM, MULTI_VOUCHER_REDEEM)
    val tips = listOf(TIP_EMPLOYEE, TIP_OWNER)

    /** Umsatzsteuerlich relevante Umsätze (Einzweckgutscheine werden bei Ausgabe versteuert). */
    val taxable = listOf(SALE, SINGLE_VOUCHER_SALE, SINGLE_VOUCHER_REDEEM, TIP_OWNER)

    fun label(businessCase: String) = when (businessCase) {
        SINGLE_VOUCHER_SALE -> "Gutscheinverkauf (Einzweck)"
        SINGLE_VOUCHER_REDEEM -> "Gutscheineinlösung (Einzweck)"
        MULTI_VOUCHER_SALE -> "Gutscheinverkauf (Mehrzweck)"
        MULTI_VOUCHER_REDEEM -> "Gutscheineinlösung (Mehrzweck)"
        TIP_EMPLOYEE -> "Trinkgeld (Mitarbeiter, ohne USt)"
        TIP_OWNER -> "Trinkgeld"
        else -> businessCase
    }
}

@Entity(
    tableName = "receipt_line",
    foreignKeys = [ForeignKey(entity = Receipt::class, parentColumns = ["id"], childColumns = ["receiptId"])],
    indices = [Index("receiptId")],
)
data class ReceiptLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: Long,
    val position: Int,
    val articleId: Long?,
    val sku: String,
    val name: String,
    /** Menge in Tausendstel (1000 = 1 Stück). */
    val quantityMilli: Long,
    val unitPriceCents: Long,
    val discountCents: Long,
    /** Brutto-Positionsbetrag nach Rabatt. */
    val totalCents: Long,
    val taxRateId: Long,
    val taxPercentBp: Int,
    val dsfinvkKey: Int,
    val businessCase: String = BusinessCase.SALE,
    // Stand zum Verkaufszeitpunkt (DSFinV-K lines.csv), unabhängig von späteren Stammdatenänderungen
    val gtin: String? = null,
    val categoryId: Long? = null,
    val categoryName: String? = null,
    /** Bei Retouren: zurückgegebene Originalposition. */
    val referencesLineId: Long? = null,
    /** Gutscheinnummer bei Gutscheinverkauf und -einlösung (DSFinV-K GUTSCHEIN_NR). */
    val voucherCode: String? = null,
)

object VoucherType {
    /** Einzweckgutschein: Leistungsort und Steuersatz stehen bei Ausgabe fest, Versteuerung bei Ausgabe. */
    const val SINGLE = "EINZWECK"
    /** Mehrzweckgutschein: Versteuerung erst bei Einlösung. */
    const val MULTI = "MEHRZWECK"
}

/**
 * Gutschein. Der Restwert wird ausschließlich beim Abschluss eines Belegs verändert
 * (Verkauf legt an, Einlösung reduziert, Storno stellt wieder her).
 */
@Entity(tableName = "voucher")
data class Voucher(
    @PrimaryKey val code: String,
    val type: String,
    val initialCents: Long,
    val balanceCents: Long,
    /** Nur Einzweckgutschein: Steuersatz der späteren Leistung. */
    val taxRateId: Long?,
    val createdAt: Long,
    val soldReceiptNumber: Long,
    val active: Boolean = true,
)

/**
 * Stammdaten einer verwendeten TSE (DSFinV-K tse.csv). Wird beim ersten erfolgreich signierten
 * Vorgang gespeichert, damit Exporte auch nach einem TSE-Wechsel vollständig bleiben.
 */
@Entity(tableName = "tse_info")
data class TseInfo(
    @PrimaryKey val serial: String,
    val signatureAlgorithm: String,
    val timeFormat: String,
    val publicKey: String,
    val certificate: String,
    val pdEncoding: String = "UTF-8",
    val firstUsedAt: Long,
)

object PaymentType {
    const val CASH = "Bar"
    const val CARD = "Unbar"
}

@Entity(
    tableName = "payment",
    foreignKeys = [ForeignKey(entity = Receipt::class, parentColumns = ["id"], childColumns = ["receiptId"])],
    indices = [Index("receiptId")],
)
data class Payment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: Long,
    /** [PaymentType] */
    val type: String,
    /** Anzeigename, z. B. "Bar", "EC-Karte". */
    val label: String,
    val amountCents: Long,
    val givenCents: Long? = null,
)

/** Tagesabschluss (Z-Bon). */
@Entity(tableName = "daily_closing", indices = [Index("number", unique = true)])
data class DailyClosing(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: Long,
    val createdAt: Long,
    val businessDate: String,
    val userName: String,
    val firstReceiptNumber: Long?,
    val lastReceiptNumber: Long?,
    val receiptCount: Int,
    val totalCents: Long,
    val cashSalesCents: Long,
    val cardSalesCents: Long,
    val cashMovementsCents: Long,
    val openingCashCents: Long,
    val expectedCashCents: Long,
    val countedCashCents: Long,
    val differenceCents: Long,
    @ColumnInfo(defaultValue = "0") val voucherSalesCents: Long = 0,
    @ColumnInfo(defaultValue = "0") val voucherRedemptionsCents: Long = 0,
)

/** Manipulationsgeschütztes Protokoll (Hash-Kette) für nicht belegbezogene Ereignisse. */
@Entity(tableName = "audit_log")
data class AuditEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val userName: String,
    val action: String,
    val details: String,
    val prevHash: String,
    val hash: String,
)

@Entity(tableName = "setting")
data class Setting(
    @PrimaryKey val key: String,
    val value: String,
)

@Entity(tableName = "import_run")
data class ImportRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val userName: String,
    val fileName: String,
    val created: Int,
    val updated: Int,
    val unchanged: Int,
    val errors: Int,
    val report: String,
)

/** Ergebnis von [ReceiptDao.returnedQuantities]; Menge ist negativ (Retourenpositionen). */
data class ReturnedQuantity(val lineId: Long, val quantityMilli: Long)

/** Beleg mit allen Positionen und Zahlungen. */
data class ReceiptWithDetails(
    val receipt: Receipt,
    val lines: List<ReceiptLine>,
    val payments: List<Payment>,
)
