package de.anypos.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MasterDataDao {
    @Query("SELECT * FROM tax_rate ORDER BY id")
    fun taxRates(): Flow<List<TaxRate>>

    @Query("SELECT * FROM tax_rate ORDER BY id")
    suspend fun taxRatesOnce(): List<TaxRate>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTaxRates(rates: List<TaxRate>)

    @Query("SELECT * FROM category ORDER BY sortOrder, name")
    fun categories(): Flow<List<Category>>

    @Query("SELECT * FROM category ORDER BY sortOrder, name")
    suspend fun categoriesOnce(): List<Category>

    @Insert
    suspend fun insertCategory(category: Category): Long

    @Query("SELECT * FROM article ORDER BY name")
    fun articles(): Flow<List<Article>>

    @Query("SELECT * FROM article ORDER BY name")
    suspend fun articlesOnce(): List<Article>

    @Query("SELECT * FROM article WHERE active = 1 AND (barcode = :code OR sku = :code) LIMIT 1")
    suspend fun findSellable(code: String): Article?

    @Upsert
    suspend fun upsertArticle(article: Article): Long

    @Upsert
    suspend fun upsertArticles(articles: List<Article>)
}

@Dao
interface UserDao {
    @Query("SELECT * FROM user ORDER BY name")
    fun users(): Flow<List<User>>

    @Query("SELECT * FROM user WHERE active = 1 ORDER BY name")
    suspend fun activeUsers(): List<User>

    @Query("SELECT COUNT(*) FROM user")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(user: User): Long
}

@Dao
interface ReceiptDao {
    @Insert
    suspend fun insertReceipt(receipt: Receipt): Long

    @Update
    suspend fun updateReceipt(receipt: Receipt)

    /** Nur die TSE-Startdaten setzen (läuft im Hintergrund, darf andere Felder nicht überschreiben). */
    @Query("UPDATE receipt SET tseSerial = :serial, tseTxNumber = :txNumber, tseStart = :start, tseError = :error WHERE id = :id")
    suspend fun updateTseStart(id: Long, serial: String?, txNumber: Long?, start: Long?, error: String?)

    /** Nur den Verweis (Storno/Retoure) setzen. */
    @Query("UPDATE receipt SET referencesReceiptId = :refId, referencesReceiptNumber = :refNumber, referenceType = :refType WHERE id = :id")
    suspend fun updateReference(id: Long, refId: Long, refNumber: Long, refType: String)

    @Insert
    suspend fun insertLines(lines: List<ReceiptLine>)

    @Insert
    suspend fun insertPayments(payments: List<Payment>)

    @Query("SELECT COALESCE(MAX(number), 0) FROM receipt")
    suspend fun maxNumber(): Long

    @Query("SELECT MIN(finishedAt) FROM receipt WHERE state != 'OPEN'")
    suspend fun firstFinishedAt(): Long?

    @Query("SELECT * FROM receipt WHERE id = :id")
    suspend fun receipt(id: Long): Receipt?

    @Query("SELECT * FROM receipt_line WHERE receiptId = :receiptId ORDER BY position")
    suspend fun lines(receiptId: Long): List<ReceiptLine>

    @Query("SELECT * FROM payment WHERE receiptId = :receiptId ORDER BY id")
    suspend fun payments(receiptId: Long): List<Payment>

    @Query("SELECT * FROM receipt WHERE state != 'OPEN' ORDER BY number DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<Receipt>>

    @Query("SELECT * FROM receipt WHERE state = 'OPEN'")
    suspend fun openReceipts(): List<Receipt>

    @Query("SELECT * FROM receipt WHERE state != 'OPEN' AND closingId IS NULL ORDER BY number")
    suspend fun unclosed(): List<Receipt>

    @Query("SELECT * FROM receipt WHERE closingId = :closingId ORDER BY number")
    suspend fun byClosing(closingId: Long): List<Receipt>

    /** Saldo eines Geschäftsvorfalls über alle abgeschlossenen Belege (z. B. noch nicht ausgezahltes Trinkgeld). */
    @Query(
        "SELECT COALESCE(SUM(l.totalCents), 0) FROM receipt_line l JOIN receipt r ON r.id = l.receiptId " +
            "WHERE l.businessCase = :businessCase AND r.state = 'FINISHED' AND r.training = 0",
    )
    suspend fun balanceOf(businessCase: String): Long

    @Query("SELECT * FROM receipt WHERE number IN (:numbers)")
    suspend fun byNumbers(numbers: List<Long>): List<Receipt>

    @Query("SELECT * FROM receipt WHERE state != 'OPEN' AND finishedAt >= :from AND finishedAt < :to ORDER BY number")
    suspend fun finishedBetween(from: Long, to: Long): List<Receipt>

    @Query("SELECT COALESCE(MAX(tseTxNumber), 0) FROM receipt WHERE tseSerial = :serial")
    suspend fun maxTseTx(serial: String): Long

    @Query("SELECT COALESCE(MAX(tseSigCounter), 0) FROM receipt WHERE tseSerial = :serial")
    suspend fun maxTseSig(serial: String): Long

    /** Alle abgeschlossenen Folgebelege (Storno oder Retoure) zu einem Original. */
    @Query("SELECT COUNT(*) FROM receipt WHERE referencesReceiptId = :receiptId AND state = 'FINISHED'")
    suspend fun referencingCount(receiptId: Long): Int

    @Query("SELECT COUNT(*) FROM receipt WHERE referencesReceiptId = :receiptId AND state = 'FINISHED' AND (referenceType IS NULL OR referenceType = 'STORNO')")
    suspend fun cancellationCount(receiptId: Long): Int

    /** Bereits zurückgegebene Menge (negativ summiert) je Originalposition. */
    @Query(
        "SELECT l.referencesLineId AS lineId, SUM(l.quantityMilli) AS quantityMilli FROM receipt_line l " +
            "JOIN receipt r ON r.id = l.receiptId WHERE r.state = 'FINISHED' AND l.referencesLineId IN (:lineIds) GROUP BY l.referencesLineId",
    )
    suspend fun returnedQuantities(lineIds: List<Long>): List<ReturnedQuantity>

    @Query("UPDATE receipt SET closingId = :closingId WHERE state != 'OPEN' AND closingId IS NULL")
    suspend fun assignClosing(closingId: Long): Int
}

@Dao
interface ClosingDao {
    @Insert
    suspend fun insert(closing: DailyClosing): Long

    @Query("SELECT COALESCE(MAX(number), 0) FROM daily_closing")
    suspend fun maxNumber(): Long

    @Query("SELECT * FROM daily_closing ORDER BY number DESC")
    fun all(): Flow<List<DailyClosing>>

    @Query("SELECT * FROM daily_closing ORDER BY number DESC LIMIT 1")
    suspend fun last(): DailyClosing?

    @Query("SELECT * FROM daily_closing WHERE createdAt >= :from AND createdAt < :to ORDER BY number")
    suspend fun between(from: Long, to: Long): List<DailyClosing>

    @Query("SELECT * FROM daily_closing ORDER BY number")
    suspend fun allOnce(): List<DailyClosing>
}

@Dao
interface VoucherDao {
    @Query("SELECT * FROM voucher WHERE code = :code")
    suspend fun get(code: String): Voucher?

    @Insert
    suspend fun insert(voucher: Voucher)

    @Update
    suspend fun update(voucher: Voucher)

    @Query("SELECT * FROM voucher ORDER BY createdAt DESC")
    fun all(): Flow<List<Voucher>>
}

@Dao
interface TseInfoDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(info: TseInfo)

    @Query("SELECT * FROM tse_info")
    suspend fun all(): List<TseInfo>
}

@Dao
interface AuditDao {
    @Insert
    suspend fun insert(entry: AuditEntry): Long

    @Query("SELECT * FROM audit_log ORDER BY id DESC LIMIT 1")
    suspend fun last(): AuditEntry?

    @Query("SELECT * FROM audit_log ORDER BY id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<AuditEntry>>

    @Query("SELECT * FROM audit_log ORDER BY id")
    suspend fun all(): List<AuditEntry>
}

@Dao
interface SettingDao {
    @Query("SELECT * FROM setting")
    fun all(): Flow<List<Setting>>

    @Query("SELECT * FROM setting")
    suspend fun allOnce(): List<Setting>

    @Upsert
    suspend fun put(setting: Setting)
}

@Dao
interface ImportRunDao {
    @Insert
    suspend fun insert(run: ImportRun): Long

    @Query("SELECT * FROM import_run ORDER BY id DESC")
    fun all(): Flow<List<ImportRun>>
}
