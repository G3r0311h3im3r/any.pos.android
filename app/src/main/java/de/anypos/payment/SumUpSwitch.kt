package de.anypos.payment

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64

/**
 * SumUp „Payment Switch“: Die Zahlung läuft in der **SumUp-App** (Lesegerät oder Tap to Pay), die Kasse übergibt nur
 * Betrag und Referenz und bekommt das Ergebnis über eine Rückruf-Adresse zurück (developer.sumup.com,
 * Payment Switch Android). Keine Bibliothek nötig – nur der Affiliate Key und die SumUp-App.
 *
 * Die Rückruf-Adresse enthält je Versuch einen **Einmal-Code** im Pfad; nur ein Rückruf mit diesem Code wird
 * angenommen, und zwar genau einmal.
 */
object SumUpSwitch {
    const val APP_ID = "de.anypos"
    /** Rückruf, wenn die SumUp-App auf diesem Tablet läuft (Intent-Filter der MainActivity). */
    const val APP_CALLBACK = "anypos://sumup-callback"
    /** Paketname der SumUp-App (für den Kioskmodus). */
    const val SUMUP_PACKAGE = "com.kaching.merchant"

    fun newNonce(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(12).also { SecureRandom().nextBytes(it) })

    /** `sumupmerchant://pay/1.0?…`; [callback] ohne Query (Einmal-Code steckt im Pfad). */
    fun startUri(affiliateKey: String, amountCents: Long, title: String, reference: String, callback: String): String =
        "sumupmerchant://pay/1.0?" + listOf(
            "affiliate-key" to affiliateKey,
            "app-id" to APP_ID,
            "total" to BigDecimal.valueOf(amountCents, 2).toPlainString(),
            "currency" to "EUR",
            "title" to title,
            "foreign-tx-id" to reference,
            "skip-screen-success" to "true",
            "callback" to callback,
        ).joinToString("&") { (k, v) -> "$k=${enc(v)}" }

    /**
     * Parameter aus einer Rückruf-Adresse, tolerant gegenüber `?`/`&` (die SumUp-App hängt ihre Werte an die
     * Adresse an).
     */
    fun params(uriOrQuery: String): Map<String, String> =
        uriOrQuery.substringAfter('?', "").split('&', '?').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else dec(part.substring(0, i)) to dec(part.substring(i + 1))
        }.toMap()

    /**
     * Auswertung: genehmigt nur bei `smp-status=success` **mit** Transaktionscode und **passender** Referenz;
     * `failed` = abgelehnt (nichts abgebucht); alles andere ist unbekannt und wird geklärt.
     */
    fun map(params: Map<String, String>, expectedReference: String): CardPaymentResult {
        val status = params["smp-status"]?.lowercase()
        val tx = params["smp-tx-code"]?.takeIf { it.isNotBlank() }
        val ref = params["foreign-tx-id"]
        val message = params["smp-message"] ?: params["smp-failure-cause"]
        return when {
            ref != null && ref != expectedReference -> CardPaymentResult.Unknown("Rückmeldung gehört zu Referenz $ref, erwartet $expectedReference")
            status == "success" && tx != null -> CardPaymentResult.Approved(tx)
            status == "success" -> CardPaymentResult.Unknown("SumUp meldet Erfolg ohne Transaktionscode")
            status == "failed" -> CardPaymentResult.Declined(message?.takeIf { it.isNotBlank() } ?: "Zahlung nicht erfolgreich")
            else -> CardPaymentResult.Unknown("Unklare Rückmeldung der SumUp-App" + (status?.let { " ($it)" } ?: ""))
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
    private fun dec(s: String) = runCatching { URLDecoder.decode(s, Charsets.UTF_8) }.getOrDefault(s)
}

/**
 * Verbindet die laufende Payment-Switch-Zahlung mit dem Rückruf – egal ob er über die App (Tablet) oder über den
 * Display-Server (Smartphone am Kundendisplay) ankommt.
 */
class SumUpSwitchBridge(private val clock: () -> Long = System::currentTimeMillis) {
    data class Pending(
        val request: CardPaymentRequest,
        val nonce: String,
        val link: String,
        val onDisplay: Boolean,
        val startedAt: Long,
        /** Am Display wurde „Mit Karte zahlen“ angetippt (die SumUp-App wurde geöffnet). */
        val opened: Boolean = false,
    )

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private val _results = MutableSharedFlow<Pair<Long, CardPaymentResult>>(extraBufferCapacity = 4)
    /** (Versuchs-ID, Ergebnis) */
    val results: SharedFlow<Pair<Long, CardPaymentResult>> = _results.asSharedFlow()

    fun begin(request: CardPaymentRequest, nonce: String, link: String, onDisplay: Boolean) {
        _pending.value = Pending(request, nonce, link, onDisplay, clock())
    }

    /** Das Display meldet, dass der Kunde die SumUp-App geöffnet hat. */
    fun markOpened(nonce: String): Boolean {
        val p = _pending.value ?: return false
        if (p.nonce != nonce) return false
        _pending.value = p.copy(opened = true)
        return true
    }

    /** Rückruf der SumUp-App. @return false, wenn kein Versuch mit diesem Einmal-Code offen ist (wird ignoriert). */
    fun deliver(nonce: String, params: Map<String, String>): Boolean {
        val p = _pending.value ?: return false
        if (p.nonce != nonce) return false
        _pending.value = null
        _results.tryEmit(p.request.attemptId to SumUpSwitch.map(params, p.request.reference))
        return true
    }

    /** Beenden mit festem Ergebnis, z. B. „SumUp-App nicht installiert“ (es wurde nichts gestartet). */
    fun finish(attemptId: Long, result: CardPaymentResult) {
        val p = _pending.value ?: return
        if (p.request.attemptId != attemptId) return
        _pending.value = null
        _results.tryEmit(attemptId to result)
    }

    /**
     * Ohne Rückruf beenden (Kassierer bricht ab / keine Rückmeldung): nie geöffnet → abgebrochen (sicher nichts
     * abgebucht), sonst unbekannt.
     */
    fun abandon(attemptId: Long, reason: String) {
        val p = _pending.value ?: return
        if (p.request.attemptId != attemptId) return
        _pending.value = null
        val result = if (p.onDisplay && !p.opened) CardPaymentResult.Cancelled else CardPaymentResult.Unknown(reason)
        _results.tryEmit(attemptId to result)
    }
}
