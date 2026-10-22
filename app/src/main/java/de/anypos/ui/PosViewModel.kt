package de.anypos.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.anypos.AppContainer
import de.anypos.data.Article
import de.anypos.data.CardAttempt
import de.anypos.data.BusinessCase
import de.anypos.data.VoucherType
import de.anypos.data.PaymentType
import de.anypos.data.Receipt
import de.anypos.data.ReceiptWithDetails
import de.anypos.data.TaxRate
import de.anypos.data.User
import de.anypos.domain.Cart
import de.anypos.domain.CartLine
import de.anypos.domain.DisplayLine
import de.anypos.domain.DisplayState
import de.anypos.domain.TipOption
import de.anypos.domain.Tips
import de.anypos.domain.Key
import de.anypos.domain.Money
import de.anypos.domain.PaymentInput
import de.anypos.hardware.ReceiptDocument
import de.anypos.payment.CardPaymentRequest
import de.anypos.payment.CardPaymentResult
import de.anypos.payment.CardTerminals
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Zahlart, die nach der Trinkgeldabfrage folgt. */
enum class PayKind { CASH, CARD, SPLIT }

/** Offene Trinkgeldfrage; [askId] bindet Antworten des Kundendisplays an genau diese Frage. */
data class TipRequest(val askId: String, val kind: PayKind, val baseCents: Long, val options: List<TipOption>)

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

    var tipRequest by mutableStateOf<TipRequest?>(null)
        private set
    /** Auswahl des Kunden am Display zur offenen Frage; wird erst mit [applyTip] wirksam. */
    var tipCustomerChoice by mutableStateOf<String?>(null)
        private set
    /** Nach der Trinkgeldabfrage: diese Zahlung öffnet die Kassenansicht als Nächstes. */
    var continuePayment by mutableStateOf<PayKind?>(null)
    private var paid by mutableStateOf<DisplayState.Paid?>(null)

    init {
        // Kundendisplay: jeden Zustandswechsel sofort veröffentlichen
        viewModelScope.launch { snapshotFlow { displayState() }.collect { c.customerDisplay.publish(it) } }
        viewModelScope.launch {
            c.customerDisplay.choices.collect { ch -> if (ch.askId == tipRequest?.askId) tipCustomerChoice = ch.optionId }
        }
    }

    override fun onCleared() {
        c.customerDisplay.publish(DisplayState.Idle(shop()))
    }

    private fun shop() = c.settings[Key.COMPANY_NAME]

    private fun displayState(): DisplayState {
        tipRequest?.let { return DisplayState.TipAsk(shop(), it.askId, it.baseCents, it.options, tipCustomerChoice) }
        if (!cart.isEmpty) return DisplayState.Basket(shop(), cart.lines.map { it.toDisplay(it.key == selectedLineKey) }, cart.totalCents)
        return paid ?: DisplayState.Idle(shop())
    }

    private fun CartLine.toDisplay(highlight: Boolean): DisplayLine {
        val isItem = businessCase == BusinessCase.SALE
        return DisplayLine(
            name = if (businessCase in BusinessCase.tips) "Trinkgeld" else name,
            quantity = if (isItem) "${Money.formatQuantity(quantityMilli)} ×" else null,
            unitPrice = if (isItem && quantityMilli != 1000L) "je ${Money.formatEur(unitPriceCents)}" else null,
            total = Money.formatEur(totalCents),
            discount = discountCents.takeIf { it != 0L }?.let { Money.formatEur(it) },
            highlight = highlight,
        )
    }

    // --- Trinkgeld

    /**
     * Vor der Zahlung: Ist die Abfrage eingeschaltet und gibt es noch kein Trinkgeld, wird eine Frage gestellt
     * (Kassenansicht und Kundendisplay). @return true = Frage gestellt, die Zahlung folgt nach [applyTip].
     */
    fun requestPayment(kind: PayKind): Boolean {
        if (!c.settings.bool(Key.TIP_ENABLED) || cart.tipCents != 0L || cart.totalWithoutTipCents <= 0) return false
        if (cart.lines.none { it.businessCase == BusinessCase.SALE && it.totalCents > 0 }) return false
        val base = cart.totalWithoutTipCents
        val options = Tips.options(base, Tips.parsePercents(c.settings[Key.TIP_PERCENTS]), c.settings.bool(Key.TIP_ROUND_UP))
        tipCustomerChoice = null
        tipRequest = TipRequest(java.util.UUID.randomUUID().toString(), kind, base, options)
        return true
    }

    /** Trinkgeld übernehmen (0 = keins) und mit der gewählten Zahlung weitermachen. */
    fun applyTip(cents: Long) {
        val request = tipRequest ?: return
        val employee = c.settings[Key.TIP_RECIPIENT] != "owner"
        val key = if (employee) 5 else c.settings.int(Key.TIP_OWNER_TAX_KEY)
        val rate = taxRates.value.firstOrNull { it.dsfinvkKey == key } ?: run { message = "Steuersatz für Trinkgeld fehlt"; return }
        viewModelScope.launch {
            lock.withLock {
                try {
                    cart = cart.withTip(cents, if (employee) BusinessCase.TIP_EMPLOYEE else BusinessCase.TIP_OWNER, rate)
                } catch (e: IllegalArgumentException) {
                    message = e.message
                    return@launch
                } finally {
                    tipRequest = null
                    tipCustomerChoice = null
                }
            }
            if (cents > 0) c.audit.log(user.name, "TRINKGELD", "${Money.format(cents)} zu Beleg ${openReceipt?.number}")
            continuePayment = request.kind
        }
    }

    fun cancelTip() {
        tipRequest = null
        tipCustomerChoice = null
    }

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
        if (busy) { message = "Bitte warten – Zahlung läuft."; return }
        viewModelScope.launch {
            lock.withLock {
                if (openReceipt == null) openReceipt = c.pos.open(user)
                try {
                    cart = change(cart.withoutTip())
                    paid = null
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
                coverable = cart.totalWithoutTipCents
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

    // Jede Änderung am Warenkorb entfernt ein schon gewähltes Trinkgeld, weil sich der Betrag geändert hat
    fun setQuantity(key: Long, qtyMilli: Long) { if (busy) return; cart = cart.withoutTip().setQuantity(key, qtyMilli) }
    fun changeQuantity(key: Long, delta: Long) {
        if (busy) return
        val line = cart.lines.firstOrNull { it.key == key } ?: return
        cart = cart.withoutTip().setQuantity(key, line.quantityMilli + delta)
    }
    fun setPrice(key: Long, cents: Long) { if (busy) return; cart = cart.withoutTip().setUnitPrice(key, cents) }
    fun discountPercent(key: Long, bp: Int) { if (busy) return; cart = cart.withoutTip().setDiscountPercent(key, bp) }
    fun remove(key: Long) { if (busy) return; cart = cart.remove(key).withoutTip() }

    /** Vorgang abbrechen: wird als AVBelegabbruch protokolliert, nicht gelöscht. */
    fun abort() {
        val open = openReceipt ?: run { cart = cart.clear(); return }
        viewModelScope.launch {
            lock.withLock {
                runCatching { c.pos.abort(open, cart) }.onFailure { message = it.message }
                cart = cart.clear()
                openReceipt = null
                tipRequest = null
                paid = null
            }
        }
    }

    fun payCash(givenCents: Long) {
        val total = cart.totalCents
        if (total == 0L) { pay(emptyList(), change = null); return } // vollständig durch Gutschein gedeckt
        if (total >= 0 && givenCents < total) { message = "Gegebener Betrag ist zu niedrig"; return }
        pay(listOf(PaymentInput(PaymentType.CASH, "Bar", total, givenCents.takeIf { total >= 0 })), change = if (total >= 0) givenCents - total else -total)
    }

    // --- Kartenzahlung (Terminal)

    private val terminal get() = c.settings[Key.CARD_TERMINAL]

    /** Laufender Auftrag ans Terminal; die Kassenansicht startet damit das Gerät (SumUp bzw. Simulation). */
    var cardRequest by mutableStateOf<CardPaymentRequest?>(null)
        private set
    /** Ergebnis unbekannt: erst klären (bezahlt / nicht bezahlt), nie blind neu abbuchen. */
    var cardUnknown by mutableStateOf<CardAttempt?>(null)
        private set
    /** Abgelehnt oder abgebrochen: Hinweis, ggf. mit „ohne Terminal buchen“. */
    var cardFailed by mutableStateOf<String?>(null)
    private var cardOthers: List<PaymentInput> = emptyList()
    private var cardChange: Long? = null
    private var cardAmount = 0L

    /** Ungeklärte Kartenzahlungen aus früheren Vorgängen (z. B. nach Absturz), außer der gerade laufenden. */
    val unresolvedCards = c.db.cardAttempts().unresolvedFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun payCard() {
        val total = cart.totalCents
        when {
            total == 0L -> finishPayment(emptyList(), change = null)
            terminal == CardTerminals.NONE -> finishPayment(listOf(PaymentInput(PaymentType.CARD, "EC-Karte", total)), change = null)
            else -> startCard(total, cart.tipCents, emptyList(), null)
        }
    }

    /** Splitzahlung: Teilbeträge in beliebiger Kombination; Rückgeld nur aus der letzten Barzahlung. */
    fun pay(payments: List<PaymentInput>, change: Long?) {
        val cards = payments.filter { it.type != PaymentType.CASH }
        if (terminal == CardTerminals.NONE || cards.isEmpty()) return finishPayment(payments, change)
        if (cards.size > 1) { message = "Mit Kartenterminal ist je Beleg nur ein Kartenanteil möglich."; return }
        startCard(cards.single().amountCents, 0, payments - cards.single(), change)
    }

    private fun startCard(amount: Long, tip: Long, others: List<PaymentInput>, change: Long?) {
        val open = openReceipt ?: return
        if (amount < CardTerminals.MIN_AMOUNT_CENTS) {
            message = "Kartenzahlung am Terminal erst ab ${Money.formatEur(CardTerminals.MIN_AMOUNT_CENTS)} – bitte bar zahlen."
            return
        }
        busy = true
        viewModelScope.launch {
            try {
                cardOthers = others; cardChange = change; cardAmount = amount
                cardRequest = c.cards.begin(open, amount, tip, terminal, user.name)
            } catch (e: Exception) {
                message = e.message
                busy = false
            }
        }
    }

    /** Ergebnis vom Terminal (SumUp-SDK oder Simulation). */
    fun onCardResult(result: CardPaymentResult) {
        val request = cardRequest ?: return
        cardRequest = null
        viewModelScope.launch {
            val attempt = c.cards.record(request, result)
            when (result) {
                is CardPaymentResult.Approved -> finishPayment(cardOthers + c.cards.payment(attempt), cardChange)
                is CardPaymentResult.Declined -> { busy = false; cardFailed = "Karte abgelehnt: ${result.message}" }
                CardPaymentResult.Cancelled -> { busy = false; cardFailed = "Kartenzahlung abgebrochen." }
                is CardPaymentResult.Unknown -> cardUnknown = attempt
            }
        }
    }

    /** Unbekanntes Ergebnis geklärt: [paid] laut SumUp-App/Händlerbereich. */
    fun resolveCard(paid: Boolean, txCode: String?) {
        val a = cardUnknown ?: return
        viewModelScope.launch {
            val r = c.cards.resolve(a.id, paid, user.name, txCode,
                if (paid) "laut SumUp-App abgebucht" else "laut SumUp-App nicht abgebucht")
            cardUnknown = null
            if (paid) finishPayment(cardOthers + c.cards.payment(r), cardChange)
            else { busy = false; cardFailed = "Nicht abgebucht – erneut mit Karte versuchen oder anders bezahlen." }
        }
    }

    /** Betrag der letzten Kartenzahlung (für die Notfall-Buchung). */
    fun cartTotalForCard() = cardAmount

    /** Notfall (Einstellung): Kartenzahlung ohne Terminal buchen, z. B. über ein externes Gerät. */
    fun bookCardManually() {
        cardFailed = null
        viewModelScope.launch { c.audit.log(user.name, "KARTE_OHNE_TERMINAL", "${Money.format(cardAmount)} zu Beleg ${openReceipt?.number}") }
        finishPayment(cardOthers + PaymentInput(PaymentType.CARD, "EC-Karte (ohne Terminal)", cardAmount), cardChange)
    }

    /** Ungeklärte Zahlung eines früheren (abgebrochenen) Vorgangs klären. */
    fun resolveOldCard(attempt: CardAttempt, paid: Boolean) {
        viewModelScope.launch {
            runCatching {
                c.cards.resolve(attempt.id, paid, user.name, null,
                    if (paid) "abgebucht, Vorgang abgebrochen – Erstattung über SumUp auslösen" else "nicht abgebucht")
            }.onFailure { message = it.message }
            if (paid) message = "Bitte ${Money.formatEur(attempt.amountCents)} (Referenz ${attempt.reference}) im SumUp-Händlerbereich erstatten " +
                "oder den Verkauf neu erfassen und dort als „ohne Terminal“ buchen."
        }
    }

    private fun finishPayment(payments: List<PaymentInput>, change: Long?) {
        val open = openReceipt ?: return
        busy = true
        viewModelScope.launch {
            lock.withLock {
                try {
                    val result = c.pos.complete(open, cart, payments)
                    lastReceipt = result
                    lastChangeCents = change
                    paid = DisplayState.Paid(
                        shop(), result.receipt.totalCents,
                        tipCents = result.lines.filter { it.businessCase in BusinessCase.tips }.sumOf { it.totalCents },
                        givenCents = payments.firstOrNull { it.type == PaymentType.CASH }?.givenCents,
                        changeCents = change,
                    )
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
