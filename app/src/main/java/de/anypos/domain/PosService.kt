package de.anypos.domain

import androidx.room.withTransaction
import de.anypos.data.BusinessCase
import de.anypos.data.Payment
import de.anypos.data.PaymentType
import de.anypos.data.PosDatabase
import de.anypos.data.Receipt
import de.anypos.data.ReceiptLine
import de.anypos.data.ReceiptState
import de.anypos.data.ReceiptType
import de.anypos.data.ReceiptWithDetails
import de.anypos.data.ReferenceType
import de.anypos.data.TseInfo
import de.anypos.data.User
import de.anypos.data.Voucher
import de.anypos.data.VoucherType
import de.anypos.tse.ProcessData
import de.anypos.tse.Tse
import de.anypos.tse.TseCapacityException
import de.anypos.tse.TseFinish
import de.anypos.tse.TseResultUnknownException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.math.RoundingMode

data class PaymentInput(
    val type: String,
    val label: String,
    val amountCents: Long,
    val givenCents: Long? = null,
    /** Nur bei Kartenzahlung über ein Terminal. */
    val providerTxCode: String? = null,
    val cardScheme: String? = null,
    val cardLast4: String? = null,
)

/** Rückgabe einer Menge (positiv, Tausendstel) einer Originalposition. */
data class ReturnItem(val lineId: Long, val quantityMilli: Long)

class PosException(message: String) : Exception(message)

/**
 * Ablauf eines Kassenvorgangs (KassenSichV):
 *  1. [open] beim ersten Artikel: fortlaufende Belegnummer vergeben, TSE-Transaktion starten
 *  2. [complete] bei Zahlung: TSE-Transaktion mit processData abschließen, Beleg unveränderlich speichern
 *  3. [abort] bei Abbruch: Vorgang als AVBelegabbruch mit TSE abschließen und aufbewahren
 *
 * Ist die TSE nicht erreichbar, wird der Vorgang trotzdem erfasst und als TSE-Ausfall gekennzeichnet
 * (Pflicht zur Belegausgabe bleibt, Ausfall steht auf dem Beleg).
 *
 * Gutscheinbestände werden nur in derselben Datenbanktransaktion verändert, in der der Beleg gespeichert wird.
 */
class PosService(
    private val db: PosDatabase,
    private val tse: () -> Tse,
    private val settings: SettingsRepository,
    private val audit: AuditLog,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Für den TSE-Start im Hintergrund. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val receipts = db.receipts()
    private val vouchers = db.vouchers()
    private val clientId get() = settings[Key.REGISTER_ID]

    /** Ergebnis eines TSE-Starts; wird in [finish] übernommen. */
    private data class StartResult(val serial: String?, val txNumber: Long?, val start: Long?, val error: String?)

    /** Laufende TSE-Starts je Beleg-ID. */
    private val starts = ConcurrentHashMap<Long, Deferred<StartResult>>()

    /** Start/Speichern der Transaktionsnummer und Abgleich dürfen sich nicht überschneiden (sonst „verwaist“). */
    private val tseLock = Mutex()

    /**
     * Neuer Vorgang: Belegnummer sofort, TSE-Start im Hintergrund (Netzwerk ca. 0,3–1 s), damit der erste Artikel
     * nicht wartet. Der Start erfolgt trotzdem zu Beginn des Vorgangs; [finish] wartet auf ihn.
     */
    suspend fun open(user: User, type: String = ReceiptType.SALE): Receipt {
        val now = clock()
        val receipt = db.withTransaction {
            val number = receipts.maxNumber() + 1
            val r = Receipt(number = number, type = type, createdAt = now, userId = user.id, userName = user.name)
            r.copy(id = receipts.insertReceipt(r))
        }
        starts[receipt.id] = scope.async { startTse(receipt, user) }
        return receipt
    }

    private suspend fun startTse(receipt: Receipt, user: User): StartResult = tseLock.withLock {
        val result = try {
            startOnce()
        } catch (e: TseCapacityException) {
            // z. B. 512 offene Transaktionen: verwaiste abschließen, dann einmal erneut
            reconcileLocked()
            try { startOnce() } catch (e2: Exception) { failed(e2) }
        } catch (e: Exception) {
            failed(e)
        }
        receipts.updateTseStart(receipt.id, result.serial, result.txNumber, result.start, result.error)
        result.error?.let { audit.log(user.name, "TSE_START_FEHLER", "Beleg ${receipt.number}: $it") }
        // Unbekanntes Ergebnis: Die TSE hat womöglich eine Transaktion begonnen, die die Kasse nicht kennt
        if (result.error != null && result.unknown) scope.launch { reconcile() }
        result
    }

    private val StartResult.unknown get() = error?.contains(UNKNOWN_MARK) == true

    private suspend fun startOnce(): StartResult {
        val s = tse().startTransaction(clientId)
        return StartResult(s.serial, s.txNumber, s.startTime, null)
    }

    private fun failed(e: Exception) = StartResult(null, null, null,
        "TSE-Start fehlgeschlagen: ${e.message}" + if (e is TseResultUnknownException) " $UNKNOWN_MARK" else "")

    /**
     * Abgleich mit der TSE (beim Start der App, nach unbekanntem Ergebnis, bei voller TSE, vor dem Kassenabschluss):
     * offene TSE-Transaktionen dieser Kasse ohne offenen Beleg werden – sofern eingestellt – als Belegabbruch
     * abgeschlossen. Für jede entsteht ein eigener Vorgang „AVBelegabbruch“ (ohne Positionen und Zahlung), denn jeder
     * abgesicherte Vorgang muss in der DSFinV-K als Vorgang erkennbar sein (DSFinV-K 2.4 Kap. 2.1, Anhang B).
     * @return die angelegten Abbruch-Vorgänge
     */
    suspend fun reconcile(): List<Receipt> = tseLock.withLock { reconcileLocked() }

    private suspend fun reconcileLocked(): List<Receipt> {
        if (settings[Key.TSE_CLOSE_ORPHANS] == "false") return emptyList()
        val known = receipts.openReceipts().mapNotNull { it.tseTxNumber }.toSet()
        val closed = try { tse().reconcile(clientId, known) } catch (e: Exception) {
            audit.log("System", "TSE_ABGLEICH_FEHLER", e.message.orEmpty())
            return emptyList()
        }
        return closed.map { f -> recordOrphan(f) }
    }

    /** Vorgang „AVBelegabbruch“ für eine verwaiste, auf der TSE abgeschlossene Transaktion. */
    private suspend fun recordOrphan(f: TseFinish): Receipt {
        val receipt = db.withTransaction {
            val r = Receipt(
                number = receipts.maxNumber() + 1, type = ReceiptType.ABORT, state = ReceiptState.ABORTED,
                createdAt = f.endTime, finishedAt = f.endTime, userId = 0, userName = "System", totalCents = 0,
                tseSerial = f.serial, tseTxNumber = f.txNumber, tseStart = null, tseEnd = f.endTime,
                tseSigCounter = f.signatureCounter, tseSignature = f.signature, tseSigAlgorithm = f.signatureAlgorithm,
                tseTimeFormat = f.timeFormat, tsePublicKey = f.publicKey,
                tseProcessType = ProcessData.PROCESS_TYPE, tseProcessData = ProcessData.build(ReceiptType.ABORT, emptyList(), emptyList()),
                tseError = ORPHAN_NOTE,
            )
            r.copy(id = receipts.insertReceipt(r))
        }
        db.tseInfo().insertIfAbsent(TseInfo(f.serial, f.signatureAlgorithm, f.timeFormat, f.publicKey, f.certificate, firstUsedAt = f.endTime))
        audit.log("System", "TSE_ABGLEICH", "Verwaiste TSE-Transaktion ${f.txNumber} als Belegabbruch abgeschlossen (Vorgang ${receipt.number})")
        return receipt
    }

    /** Schließt einen Verkauf mit einer oder mehreren Zahlungen (Splitzahlung) ab. */
    suspend fun complete(open: Receipt, cart: Cart, payments: List<PaymentInput>): ReceiptWithDetails {
        require(open.state == ReceiptState.OPEN) { "Beleg ${open.number} ist nicht offen" }
        if (cart.isEmpty) throw PosException("Warenkorb ist leer")
        val paid = payments.sumOf { it.amountCents }
        if (paid != cart.totalCents) throw PosException("Zahlbetrag ${Money.format(paid)} entspricht nicht der Summe ${Money.format(cart.totalCents)}")
        if (cart.totalCents < 0 && cart.lines.any { it.businessCase in BusinessCase.voucherRedemptions }) {
            throw PosException("Gutscheine dürfen nicht ausgezahlt werden")
        }
        val lines = toLines(open.id, cart)
        val voucherChanges = planVoucherChanges(lines, open.number)
        return finish(open, open.type, ReceiptState.FINISHED, lines, payments.map { it.toPayment(open.id) }) {
            applyVoucherChanges(voucherChanges)
        }
    }

    suspend fun abort(open: Receipt, cart: Cart): ReceiptWithDetails =
        finish(open, ReceiptType.ABORT, ReceiptState.ABORTED, toLines(open.id, cart), emptyList())

    /** Vollstorno: neuer Beleg mit umgekehrten Vorzeichen, Verweis auf das Original. */
    suspend fun cancel(originalId: Long, user: User): ReceiptWithDetails {
        val original = load(originalId)
        val o = original.receipt
        if (o.state != ReceiptState.FINISHED || o.type != ReceiptType.SALE) throw PosException("Nur abgeschlossene Verkaufsbelege können storniert werden")
        if (o.referencesReceiptId != null) throw PosException("Storno- und Retourenbelege können nicht storniert werden")
        if (original.lines.any { it.businessCase in BusinessCase.cashMovements }) throw PosException("Kassenbewegungen werden durch eine Gegenbuchung im Kassenbuch korrigiert")
        if (receipts.referencingCount(o.id) > 0) throw PosException("Zu Beleg ${o.number} gibt es bereits einen Storno oder eine Retoure")

        val changes = original.lines.mapNotNull { l ->
            val code = l.voucherCode ?: return@mapNotNull null
            val v = vouchers.get(code) ?: throw PosException("Gutschein $code nicht gefunden")
            when (l.businessCase) {
                in BusinessCase.voucherSales -> {
                    if (!v.active || v.balanceCents != v.initialCents) throw PosException("Gutschein $code wurde bereits (teilweise) eingelöst – Storno nicht möglich")
                    v.copy(active = false, balanceCents = 0)
                }
                else -> v.copy(balanceCents = v.balanceCents - l.totalCents) // Einlösung war negativ -> Restwert zurück
            }
        }

        val open = open(user).copy(referencesReceiptId = o.id, referencesReceiptNumber = o.number, referenceType = ReferenceType.CANCEL)
        receipts.updateReference(open.id, o.id, o.number, ReferenceType.CANCEL)
        val lines = original.lines.map {
            it.copy(id = 0, receiptId = open.id, quantityMilli = -it.quantityMilli, discountCents = -it.discountCents, totalCents = -it.totalCents)
        }
        val pays = original.payments.map { it.copy(id = 0, receiptId = open.id, amountCents = -it.amountCents, givenCents = null) }
        audit.log(user.name, "STORNO", "Beleg ${o.number} storniert durch Beleg ${open.number}")
        return finish(open, ReceiptType.SALE, ReceiptState.FINISHED, lines, pays) { changes.forEach { vouchers.update(it) } }
    }

    /** Bereits zurückgegebene Menge (positiv) je Originalposition. */
    suspend fun returnedQuantities(original: ReceiptWithDetails): Map<Long, Long> =
        receipts.returnedQuantities(original.lines.map { it.id }).associate { it.lineId to -it.quantityMilli }

    /**
     * Teil-Retoure: neuer Beleg mit negativen Mengen für die zurückgegebenen Warenpositionen.
     * Positionsrabatte werden anteilig zurückgerechnet; Erstattung über [refund].
     */
    suspend fun returnItems(originalId: Long, items: List<ReturnItem>, refundType: String, refundLabel: String, user: User): ReceiptWithDetails {
        val original = load(originalId)
        val o = original.receipt
        if (o.state != ReceiptState.FINISHED || o.type != ReceiptType.SALE || o.referencesReceiptId != null) {
            throw PosException("Retoure nur zu abgeschlossenen Verkaufsbelegen möglich")
        }
        if (receipts.cancellationCount(o.id) > 0) throw PosException("Beleg ${o.number} wurde storniert")
        val wanted = items.filter { it.quantityMilli > 0 }
        if (wanted.isEmpty()) throw PosException("Keine Rückgabemenge gewählt")
        val already = returnedQuantities(original)

        val open = open(user).copy(referencesReceiptId = o.id, referencesReceiptNumber = o.number, referenceType = ReferenceType.RETURN)
        receipts.updateReference(open.id, o.id, o.number, ReferenceType.RETURN)
        val lines = wanted.mapIndexed { i, item ->
            val l = original.lines.firstOrNull { it.id == item.lineId } ?: throw PosException("Position nicht gefunden")
            if (l.businessCase != BusinessCase.SALE || l.quantityMilli <= 0) throw PosException("${l.name} kann nicht zurückgegeben werden")
            val left = l.quantityMilli - (already[l.id] ?: 0)
            if (item.quantityMilli > left) throw PosException("${l.name}: höchstens ${Money.formatQuantity(left)} rückgabefähig")
            val discount = proportionalDiscount(l, item.quantityMilli)
            l.copy(id = 0, receiptId = open.id, position = i + 1, quantityMilli = -item.quantityMilli,
                discountCents = -discount, totalCents = -refundCents(l, item.quantityMilli), referencesLineId = l.id)
        }
        val refund = lines.sumOf { it.totalCents }
        val pay = Payment(receiptId = open.id, type = refundType, label = refundLabel, amountCents = refund)
        audit.log(user.name, "RETOURE", "Beleg ${open.number} zu Beleg ${o.number}: ${Money.format(refund)} ($refundLabel)")
        return finish(open, ReceiptType.SALE, ReceiptState.FINISHED, lines, listOf(pay))
    }

    /**
     * Ein-/Auszahlung, Privatentnahme/-einlage, Geldtransit. [amountCents] positiv = Geld in die Kasse,
     * negativ = Geld aus der Kasse. Nicht steuerbar (USt-Schlüssel 5).
     */
    suspend fun cashMovement(user: User, businessCase: String, amountCents: Long, text: String): ReceiptWithDetails {
        require(businessCase in BusinessCase.cashMovements)
        if (amountCents == 0L) throw PosException("Betrag darf nicht 0 sein")
        val open = open(user)
        val tax = db.masterData().taxRatesOnce().first { it.dsfinvkKey == 5 }
        val line = ReceiptLine(
            receiptId = open.id, position = 1, articleId = null, sku = "", name = text.ifBlank { businessCase },
            quantityMilli = 1000, unitPriceCents = amountCents, discountCents = 0, totalCents = amountCents,
            taxRateId = tax.id, taxPercentBp = 0, dsfinvkKey = 5, businessCase = businessCase,
        )
        val pay = Payment(receiptId = open.id, type = PaymentType.CASH, label = "Bar", amountCents = amountCents)
        audit.log(user.name, "KASSENBEWEGUNG", "$businessCase ${Money.format(amountCents)} $text")
        return finish(open, ReceiptType.SALE, ReceiptState.FINISHED, listOf(line), listOf(pay))
    }

    /** Noch nicht ausgezahltes Trinkgeld der Mitarbeitenden (erhalten minus ausgezahlt). */
    suspend fun tipBalance(): Long = receipts.balanceOf(BusinessCase.TIP_EMPLOYEE)

    /**
     * Auszahlung gesammelter Trinkgelder an Mitarbeitende aus der Kasse: eigener Vorgang mit Geschäftsvorfall
     * „TrinkgeldAN“ (negativ, USt-Schlüssel 5), mit TSE abgesichert (Praxishilfe HWK, DSFinV-K).
     */
    suspend fun tipPayout(user: User, amountCents: Long, recipient: String): ReceiptWithDetails {
        if (amountCents <= 0) throw PosException("Betrag muss größer als 0 sein")
        val open = open(user)
        val tax = db.masterData().taxRatesOnce().first { it.dsfinvkKey == 5 }
        val text = "Trinkgeld-Auszahlung" + recipient.trim().takeIf { it.isNotEmpty() }?.let { " an $it" }.orEmpty()
        val line = ReceiptLine(
            receiptId = open.id, position = 1, articleId = null, sku = "", name = text,
            quantityMilli = 1000, unitPriceCents = -amountCents, discountCents = 0, totalCents = -amountCents,
            taxRateId = tax.id, taxPercentBp = 0, dsfinvkKey = 5, businessCase = BusinessCase.TIP_EMPLOYEE,
        )
        val pay = Payment(receiptId = open.id, type = PaymentType.CASH, label = "Bar", amountCents = -amountCents)
        audit.log(user.name, "TRINKGELD_AUSZAHLUNG", "${Money.format(amountCents)} $text")
        return finish(open, ReceiptType.SALE, ReceiptState.FINISHED, listOf(line), listOf(pay))
    }

    /** Nach Absturz/Neustart offene Vorgänge als Abbruch abschließen, damit keine TSE-Transaktion offen bleibt. */
    suspend fun recoverOpenReceipts() {
        for (r in receipts.openReceipts()) {
            audit.log(r.userName, "WIEDERANLAUF", "Offener Vorgang ${r.number} als Abbruch abgeschlossen")
            finish(r, ReceiptType.ABORT, ReceiptState.ABORTED, emptyList(), emptyList())
        }
    }

    suspend fun load(id: Long): ReceiptWithDetails {
        val r = receipts.receipt(id) ?: throw PosException("Beleg $id nicht gefunden")
        return ReceiptWithDetails(r, receipts.lines(id), receipts.payments(id))
    }

    /** Prüft Gutscheinverkäufe und -einlösungen vor der TSE-Signatur und liefert die zu speichernden Bestände. */
    private suspend fun planVoucherChanges(lines: List<ReceiptLine>, receiptNumber: Long): List<Pair<Voucher, Boolean>> =
        lines.filter { it.voucherCode != null }.map { l ->
            val code = l.voucherCode!!
            val existing = vouchers.get(code)
            when (l.businessCase) {
                in BusinessCase.voucherSales -> {
                    if (existing != null) throw PosException("Gutscheinnummer $code ist bereits vergeben")
                    val type = if (l.businessCase == BusinessCase.SINGLE_VOUCHER_SALE) VoucherType.SINGLE else VoucherType.MULTI
                    Voucher(code, type, l.totalCents, l.totalCents, l.taxRateId.takeIf { type == VoucherType.SINGLE }, clock(), receiptNumber) to true
                }
                else -> {
                    val v = existing ?: throw PosException("Gutschein $code nicht gefunden")
                    if (!v.active) throw PosException("Gutschein $code ist nicht gültig")
                    if (v.balanceCents < -l.totalCents) throw PosException("Gutschein $code hat nur noch ${Money.formatEur(v.balanceCents)}")
                    v.copy(balanceCents = v.balanceCents + l.totalCents) to false
                }
            }
        }

    private suspend fun applyVoucherChanges(changes: List<Pair<Voucher, Boolean>>) {
        for ((v, isNew) in changes) if (isNew) vouchers.insert(v) else vouchers.update(v)
    }

    private suspend fun finish(
        receipt: Receipt, type: String, state: String, lines: List<ReceiptLine>, payments: List<Payment>,
        inTransaction: suspend () -> Unit = {},
    ): ReceiptWithDetails {
        // Auf den TSE-Start im Hintergrund warten und sein Ergebnis übernehmen
        val open = starts.remove(receipt.id)?.await()?.let {
            receipt.copy(tseSerial = it.serial, tseTxNumber = it.txNumber, tseStart = it.start, tseError = it.error)
        } ?: receipt
        val processData = ProcessData.build(type, lines, payments)
        var result = open.copy(
            type = type, state = state, finishedAt = clock(), totalCents = lines.sumOf { it.totalCents },
            tseProcessType = ProcessData.PROCESS_TYPE, tseProcessData = processData,
        )
        val tx = open.tseTxNumber
        if (tx != null) {
            try {
                val f = tse().finishTransaction(clientId, tx, ProcessData.PROCESS_TYPE, processData)
                result = result.copy(
                    // Startzeit kennt die TSE-Anbindung nach einem Neustart nicht mehr: dann die gespeicherte nehmen
                    tseSerial = f.serial, tseStart = f.startTime.takeIf { it > 0 } ?: open.tseStart, tseEnd = f.endTime,
                    tseSigCounter = f.signatureCounter,
                    tseSignature = f.signature, tseSigAlgorithm = f.signatureAlgorithm, tseTimeFormat = f.timeFormat,
                    tsePublicKey = f.publicKey, tseError = null,
                )
                db.tseInfo().insertIfAbsent(
                    TseInfo(f.serial, f.signatureAlgorithm, f.timeFormat, f.publicKey, f.certificate, firstUsedAt = f.endTime),
                )
            } catch (e: Exception) {
                audit.log(open.userName, "TSE_ENDE_FEHLER", "Beleg ${open.number}: ${e.message}")
                result = result.copy(tseError = "TSE-Abschluss fehlgeschlagen: ${e.message}")
            }
        }
        db.withTransaction {
            receipts.insertLines(lines)
            receipts.insertPayments(payments)
            receipts.updateReceipt(result)
            if (state == ReceiptState.FINISHED) inTransaction()
        }
        return load(open.id)
    }

    private fun toLines(receiptId: Long, cart: Cart): List<ReceiptLine> = cart.lines.mapIndexed { i, l ->
        ReceiptLine(
            receiptId = receiptId, position = i + 1, articleId = l.articleId, sku = l.sku, name = l.name,
            quantityMilli = l.quantityMilli, unitPriceCents = l.unitPriceCents, discountCents = l.discountCents,
            totalCents = l.totalCents, taxRateId = l.taxRate.id, taxPercentBp = l.taxRate.percentBp,
            dsfinvkKey = l.taxRate.dsfinvkKey, businessCase = l.businessCase,
            gtin = l.gtin, categoryId = l.categoryId, categoryName = l.categoryName, voucherCode = l.voucherCode,
        )
    }

    private fun PaymentInput.toPayment(receiptId: Long) =
        Payment(
            receiptId = receiptId, type = type, label = label, amountCents = amountCents, givenCents = givenCents,
            providerTxCode = providerTxCode, cardScheme = cardScheme, cardLast4 = cardLast4,
        )

    companion object {
        private const val UNKNOWN_MARK = "(Ergebnis unbekannt)"

        /** Hinweis für TSE_TA_FEHLER: Vorgang entstand beim Wiederanlauf, die Startzeit der Transaktion ist unbekannt. */
        const val ORPHAN_NOTE = "Wiederanlauf: verwaiste TSE-Transaktion als Belegabbruch abgeschlossen, Startzeit unbekannt"

        /** Anteiliger Positionsrabatt für eine Teilmenge. */
        fun proportionalDiscount(line: ReceiptLine, quantityMilli: Long): Long =
            BigDecimal.valueOf(line.discountCents).multiply(BigDecimal.valueOf(quantityMilli))
                .divide(BigDecimal.valueOf(line.quantityMilli), 0, RoundingMode.HALF_UP).toLong()

        /** Erstattungsbetrag (positiv) für die Rückgabe einer Teilmenge. */
        fun refundCents(line: ReceiptLine, quantityMilli: Long): Long =
            Money.lineTotal(line.unitPriceCents, quantityMilli) - proportionalDiscount(line, quantityMilli)
    }
}
