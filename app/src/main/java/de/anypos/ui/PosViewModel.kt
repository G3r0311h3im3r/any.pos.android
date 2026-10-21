package de.anypos.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.anypos.AppContainer
import de.anypos.data.Article
import de.anypos.data.BusinessCase
import de.anypos.data.VoucherType
import de.anypos.data.PaymentType
import de.anypos.data.Receipt
import de.anypos.data.ReceiptWithDetails
import de.anypos.data.TaxRate
import de.anypos.data.User
import de.anypos.domain.Cart
import de.anypos.domain.Key
import de.anypos.domain.Money
import de.anypos.domain.PaymentInput
import de.anypos.hardware.ReceiptDocument
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PosViewModel(private val c: AppContainer, private val user: User) : ViewModel() {

    val articles = c.db.masterData().articles().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val categories = c.db.masterData().categories().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val taxRates = c.db.masterData().taxRates().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    var cart by mutableStateOf(Cart())
        private set
    var openReceipt by mutableStateOf<Receipt?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set
    var lastReceipt by mutableStateOf<ReceiptWithDetails?>(null)
        private set
    var lastChangeCents by mutableStateOf<Long?>(null)
    var selectedLineKey by mutableStateOf<Long?>(null)

    /** Verhindert, dass zwei schnelle Eingaben zwei Vorgänge öffnen. */
    private val lock = Mutex()

    private fun tax(article: Article): TaxRate? = taxRates.value.firstOrNull { it.id == article.taxRateId }

    fun add(article: Article, priceCents: Long = article.priceCents) {
        val rate = tax(article) ?: run { message = "Steuersatz für ${article.name} fehlt"; return }
        val category = categories.value.firstOrNull { it.id == article.categoryId }?.name
        changeCart { it.add(article, rate, priceCents, categoryName = category) }
    }

    /** Öffnet bei Bedarf den Vorgang (TSE-Start) und ändert den Warenkorb. */
    private fun changeCart(change: (Cart) -> Cart) {
        viewModelScope.launch {
            lock.withLock {
                if (openReceipt == null) openReceipt = c.pos.open(user)
                try {
                    cart = change(cart)
                } catch (e: IllegalArgumentException) {
                    message = e.message
                }
                selectedLineKey = cart.lines.lastOrNull()?.key
                lastChangeCents = null
            }
        }
    }

    /**
     * Gutscheinverkauf. Ohne Nummer wird eine zufällige Nummer erzeugt; vorgedruckte Gutscheinkarten
     * können per Scanner übernommen werden.
     */
    fun sellVoucher(type: String, amountCents: Long, code: String, taxRateId: Long) {
        if (amountCents <= 0) { message = "Gutscheinwert muss größer als 0 sein"; return }
        viewModelScope.launch {
            val voucherCode = code.trim().uppercase().ifEmpty { newVoucherCode() }
            if (c.db.vouchers().get(voucherCode) != null) { message = "Gutscheinnummer $voucherCode ist bereits vergeben"; return@launch }
            val rate = if (type == VoucherType.MULTI) taxRates.value.first { it.dsfinvkKey == 5 } else taxRates.value.first { it.id == taxRateId }
            changeCart { it.addVoucherSale(voucherCode, type, amountCents, rate) }
        }
    }

    /**
     * Gutscheineinlösung bis zum offenen Betrag. Ein Einzweckgutschein deckt nur Waren seines Steuersatzes.
     */
    fun redeemVoucher(code: String) {
        val trimmed = code.trim().uppercase()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val v = c.db.vouchers().get(trimmed) ?: run { message = "Gutschein $trimmed nicht gefunden"; return@launch }
            if (!v.active || v.balanceCents <= 0) {
                message = "Gutschein $trimmed ist nicht mehr gültig (Restwert ${Money.formatEur(v.balanceCents)})"
                return@launch
            }
            val rate: TaxRate
            val coverable: Long
            if (v.type == VoucherType.SINGLE) {
                rate = taxRates.value.first { it.id == v.taxRateId }
                coverable = cart.lines.filter { it.businessCase == BusinessCase.SALE && it.taxRate.id == rate.id }.sumOf { it.totalCents } +
                    cart.lines.filter { it.businessCase == BusinessCase.SINGLE_VOUCHER_REDEEM && it.taxRate.id == rate.id }.sumOf { it.totalCents }
            } else {
                rate = taxRates.value.first { it.dsfinvkKey == 5 }
                coverable = cart.totalCents
            }
            val amount = minOf(v.balanceCents, coverable)
            if (amount <= 0) {
                message = if (v.type == VoucherType.SINGLE) "Einzweckgutschein gilt nur für Waren mit ${rate.label}" else "Erst Waren erfassen, dann Gutschein einlösen"
                return@launch
            }
            changeCart { it.addVoucherRedemption(v, amount, rate) }
        }
    }

    private fun newVoucherCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // ohne 0/O, 1/I
        val rnd = java.security.SecureRandom()
        return "GS-" + (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
    }

    /** Barcode-/Artikelnummer-Eingabe (Handscanner arbeitet als Tastatur). */
    fun scan(code: String, onOpenPrice: (Article) -> Unit) {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val a = c.db.masterData().findSellable(trimmed)
            when {
                a == null -> message = "Artikel '$trimmed' nicht gefunden"
                a.openPrice -> onOpenPrice(a)
                else -> add(a)
            }
        }
    }

    fun setQuantity(key: Long, qtyMilli: Long) { cart = cart.setQuantity(key, qtyMilli) }
    fun changeQuantity(key: Long, delta: Long) {
        val line = cart.lines.firstOrNull { it.key == key } ?: return
        cart = cart.setQuantity(key, line.quantityMilli + delta)
    }
    fun setPrice(key: Long, cents: Long) { cart = cart.setUnitPrice(key, cents) }
    fun discountPercent(key: Long, bp: Int) { cart = cart.setDiscountPercent(key, bp) }
    fun remove(key: Long) { cart = cart.remove(key) }

    /** Vorgang abbrechen: wird als AVBelegabbruch protokolliert, nicht gelöscht. */
    fun abort() {
        val open = openReceipt ?: run { cart = cart.clear(); return }
        viewModelScope.launch {
            lock.withLock {
                runCatching { c.pos.abort(open, cart) }.onFailure { message = it.message }
                cart = cart.clear()
                openReceipt = null
            }
        }
    }

    fun payCash(givenCents: Long) {
        val total = cart.totalCents
        if (total == 0L) { pay(emptyList(), change = null); return } // vollständig durch Gutschein gedeckt
        if (total >= 0 && givenCents < total) { message = "Gegebener Betrag ist zu niedrig"; return }
        pay(listOf(PaymentInput(PaymentType.CASH, "Bar", total, givenCents.takeIf { total >= 0 })), change = if (total >= 0) givenCents - total else -total)
    }

    fun payCard(label: String = "EC-Karte") =
        if (cart.totalCents == 0L) pay(emptyList(), change = null)
        else pay(listOf(PaymentInput(PaymentType.CARD, label, cart.totalCents)), change = null)

    /** Splitzahlung: Teilbeträge in beliebiger Kombination; Rückgeld nur aus der letzten Barzahlung. */
    fun pay(payments: List<PaymentInput>, change: Long?) {
        val open = openReceipt ?: return
        busy = true
        viewModelScope.launch {
            lock.withLock {
                try {
                    val result = c.pos.complete(open, cart, payments)
                    lastReceipt = result
                    lastChangeCents = change
                    cart = cart.clear()
                    openReceipt = null
                    val cash = payments.any { it.type == PaymentType.CASH } || c.settings.bool(Key.OPEN_DRAWER_ON_CARD)
                    printReceipt(result, openDrawer = cash)
                } catch (e: Exception) {
                    message = e.message
                } finally {
                    busy = false
                }
            }
        }
    }

    fun reprintLast() {
        val r = lastReceipt ?: return
        viewModelScope.launch { printReceipt(r, openDrawer = false, copy = true) }
    }

    fun openDrawer() {
        viewModelScope.launch {
            c.audit.log(user.name, "SCHUBLADE_OHNE_VERKAUF")
            runCatching { c.printer.openDrawer() }.onFailure { message = "Schublade: ${it.message}" }
        }
    }

    private suspend fun printReceipt(r: ReceiptWithDetails, openDrawer: Boolean, copy: Boolean = false) {
        if (!c.printer.configured) return
        val doc = ReceiptDocument.receipt(c.company(), r, copy)
        runCatching {
            c.printer.print(ReceiptDocument.toEscPos(doc, c.printer.columns, openDrawer, c.settings.int(Key.DRAWER_PIN)))
        }.onFailure { message = "Beleg ${r.receipt.number} gespeichert, Druck fehlgeschlagen: ${it.message}" }
    }

    fun receiptText(r: ReceiptWithDetails): String =
        ReceiptDocument.toText(ReceiptDocument.receipt(c.company(), r), c.printer.columns)

    fun totalText() = Money.formatEur(cart.totalCents)
}
