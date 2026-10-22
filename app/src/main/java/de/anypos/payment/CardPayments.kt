package de.anypos.payment

import de.anypos.data.CardAttempt
import de.anypos.data.CardAttemptState
import de.anypos.data.PaymentType
import de.anypos.data.PosDatabase
import de.anypos.data.Receipt
import de.anypos.domain.AuditLog
import de.anypos.domain.Money
import de.anypos.domain.PaymentInput

/** Auftrag an ein Kartenterminal. [amountCents] enthält [tipCents]; SumUp bekommt Betrag ohne Trinkgeld + Trinkgeld getrennt. */
data class CardPaymentRequest(
    val attemptId: Long,
    val reference: String,
    val amountCents: Long,
    val tipCents: Long,
    val title: String,
)

sealed interface CardPaymentResult {
    data class Approved(val txCode: String, val scheme: String? = null, val last4: String? = null) : CardPaymentResult
    data class Declined(val message: String) : CardPaymentResult
    data object Cancelled : CardPaymentResult
    /** Verbindung weg, Zeitüberschreitung o. Ä.: ob abgebucht wurde, ist offen. */
    data class Unknown(val message: String) : CardPaymentResult
}

object CardTerminals {
    const val NONE = "none"
    const val SIMULATED = "simulated"
    const val SUMUP = "sumup"

    /** Zahlungsname auf Bon und in der DSFinV-K. */
    fun paymentLabel(terminal: String) = when (terminal) {
        SUMUP -> "Karte SumUp"
        SIMULATED -> "Karte (Simulation)"
        else -> "EC-Karte"
    }

    /** SumUp nimmt Kartenzahlungen erst ab 1,00 € an. */
    const val MIN_AMOUNT_CENTS = 100L
}

/**
 * Kartenzahlungen über ein Terminal. Grundsatz wie bei der TSE: ein Versuch wird **vor** dem Start gespeichert,
 * seine Referenz nie wiederverwendet, und ein unbekanntes Ergebnis wird geklärt, bevor erneut abgebucht wird.
 */
class CardPayments(
    private val db: PosDatabase,
    private val audit: AuditLog,
    private val registerId: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val attempts = db.cardAttempts()

    /** Neuer Versuch für [receipt]; Referenz `<Kasse>-<Beleg>-<Versuch>`. */
    suspend fun begin(receipt: Receipt, amountCents: Long, tipCents: Long, terminal: String, userName: String): CardPaymentRequest {
        require(amountCents >= CardTerminals.MIN_AMOUNT_CENTS) { "Kartenzahlung erst ab ${Money.formatEur(CardTerminals.MIN_AMOUNT_CENTS)}" }
        require(tipCents in 0..amountCents)
        require(attempts.unresolved().none { it.receiptId == receipt.id }) { "Zu diesem Vorgang ist noch eine Kartenzahlung ungeklärt" }
        val n = attempts.countForReceipt(receipt.id) + 1
        val reference = "${registerId().ifBlank { "KASSE" }}-${receipt.number}-$n"
        val attempt = CardAttempt(
            reference = reference, receiptId = receipt.id, receiptNumber = receipt.number, amountCents = amountCents,
            tipCents = tipCents, terminal = terminal, state = CardAttemptState.PENDING, createdAt = clock(), userName = userName,
        )
        val id = attempts.insert(attempt)
        audit.log(userName, "KARTE_START", "$reference ${Money.format(amountCents)} ($terminal)")
        return CardPaymentRequest(id, reference, amountCents, tipCents, "Beleg ${receipt.number}")
    }

    /** Ergebnis des Terminals festhalten. */
    suspend fun record(request: CardPaymentRequest, result: CardPaymentResult): CardAttempt {
        val a = attempts.get(request.attemptId) ?: error("Kartenzahlung ${request.reference} nicht gefunden")
        val updated = when (result) {
            is CardPaymentResult.Approved -> a.copy(
                state = CardAttemptState.APPROVED, providerTxCode = result.txCode, cardScheme = result.scheme, cardLast4 = result.last4,
            )
            is CardPaymentResult.Declined -> a.copy(state = CardAttemptState.DECLINED, note = result.message)
            CardPaymentResult.Cancelled -> a.copy(state = CardAttemptState.CANCELLED)
            is CardPaymentResult.Unknown -> a.copy(state = CardAttemptState.UNKNOWN, note = result.message)
        }.copy(finishedAt = clock())
        attempts.update(updated)
        audit.log(a.userName, "KARTE_ERGEBNIS", "${a.reference}: ${updated.state}" + (updated.providerTxCode?.let { " $it" } ?: "") +
            (updated.note?.let { " – $it" } ?: ""))
        return updated
    }

    /** Unbekanntes Ergebnis vom Kassierer geklärt (z. B. nach Blick in die SumUp-App). */
    suspend fun resolve(attemptId: Long, paid: Boolean, userName: String, txCode: String?, note: String): CardAttempt {
        val a = attempts.get(attemptId) ?: error("Kartenzahlung nicht gefunden")
        require(a.state in CardAttemptState.open) { "Kartenzahlung ${a.reference} ist bereits geklärt" }
        val updated = a.copy(
            state = if (paid) CardAttemptState.RESOLVED_PAID else CardAttemptState.RESOLVED_NOT_PAID,
            providerTxCode = txCode?.trim()?.ifEmpty { null } ?: a.providerTxCode,
            finishedAt = clock(), note = note,
        )
        attempts.update(updated)
        audit.log(userName, "KARTE_GEKLAERT", "${a.reference}: ${if (paid) "abgebucht" else "nicht abgebucht"} – $note")
        return updated
    }

    suspend fun unresolved(): List<CardAttempt> = attempts.unresolved()

    /**
     * Beim App-Start: Ein Versuch, der noch „läuft“, wurde durch Absturz/Neustart unterbrochen. Sein Ergebnis ist
     * unbekannt – der Vorgang selbst wird beim Wiederanlauf als Abbruch abgeschlossen, die Zahlung muss geklärt werden.
     */
    suspend fun recover() {
        for (a in attempts.unresolved().filter { it.state == CardAttemptState.PENDING }) {
            attempts.update(a.copy(state = CardAttemptState.UNKNOWN, note = "Unterbrochen (Neustart der App)", finishedAt = clock()))
            audit.log("System", "KARTE_UNTERBROCHEN", "${a.reference} ${Money.format(a.amountCents)} – Ergebnis unbekannt")
        }
    }

    /** Zahlung für den Beleg aus einem abgebuchten Versuch. */
    fun payment(a: CardAttempt) = PaymentInput(
        PaymentType.CARD, CardTerminals.paymentLabel(a.terminal), a.amountCents,
        providerTxCode = a.providerTxCode, cardScheme = a.cardScheme, cardLast4 = a.cardLast4,
    )
}
