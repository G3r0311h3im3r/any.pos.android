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
import java.math.BigDecimal
import java.math.RoundingMode

data class PaymentInput(val type: String, val label: String, val amountCents: Long, val givenCents: Long? = null)

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
) {
    private val receipts = db.receipts()
    private val vouchers = db.vouchers()
    private val clientId get() = settings[Key.REGISTER_ID]

    suspend fun open(user: User, type: String = ReceiptType.SALE): Receipt {
        val now = clock()
        val receipt = db.withTransaction {
            val number = receipts.maxNumber() + 1
            val r = Receipt(number = number, type = type, createdAt = now, userId = user.id, userName = user.name)
            r.copy(id = receipts.insertReceipt(r))
        }
        val started = try {
            val s = tse().startTransaction(clientId)
            receipt.copy(tseSerial = s.serial, tseTxNumber = s.txNumber, tseStart = s.startTime)
        } catch (e: Exception) {
            audit.log(user.name, "TSE_START_FEHLER", "Beleg ${receipt.number}: ${e.message}")
            receipt.copy(tseError = "TSE-Start fehlgeschlagen: ${e.message}")
        }
        receipts.updateReceipt(started)
        return started
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
        receipts.updateReceipt(open)
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
        receipts.updateReceipt(open)
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
        open: Receipt, type: String, state: String, lines: List<ReceiptLine>, payments: List<Payment>,
        inTransaction: suspend () -> Unit = {},
    ): ReceiptWithDetails {
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
                    tseSerial = f.serial, tseStart = f.startTime, tseEnd = f.endTime, tseSigCounter = f.signatureCounter,
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
        Payment(receiptId = receiptId, type = type, label = label, amountCents = amountCents, givenCents = givenCents)

    companion object {
        /** Anteiliger Positionsrabatt für eine Teilmenge. */
        fun proportionalDiscount(line: ReceiptLine, quantityMilli: Long): Long =
            BigDecimal.valueOf(line.discountCents).multiply(BigDecimal.valueOf(quantityMilli))
                .divide(BigDecimal.valueOf(line.quantityMilli), 0, RoundingMode.HALF_UP).toLong()

        /** Erstattungsbetrag (positiv) für die Rückgabe einer Teilmenge. */
        fun refundCents(line: ReceiptLine, quantityMilli: Long): Long =
            Money.lineTotal(line.unitPriceCents, quantityMilli) - proportionalDiscount(line, quantityMilli)
    }
}
