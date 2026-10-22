package de.anypos.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import com.sumup.checkout.core.models.TransactionInfo
import com.sumup.merchant.reader.api.SumUpAPI
import com.sumup.merchant.reader.api.SumUpCheckoutContract
import com.sumup.merchant.reader.api.SumUpPayment
import de.anypos.AppContainer
import de.anypos.payment.CardPaymentRequest
import de.anypos.payment.CardPaymentResult
import de.anypos.payment.SumUpResults

/**
 * Startet den SumUp-Bezahlvorgang (Reader SDK, Solo/Solo Lite per Bluetooth) für [request] – genau einmal je Versuch,
 * auch wenn die Ansicht neu aufgebaut wird (sonst drohte eine zweite Abbuchung).
 */
@Composable
fun CardTerminalLauncher(container: AppContainer, request: CardPaymentRequest, onResult: (CardPaymentResult) -> Unit) {
    var launched by rememberSaveable(request.attemptId) { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(SumUpCheckoutContract()) { result ->
        onResult(SumUpResults.map(raw(result.data), request.amountCents))
    }
    LaunchedEffect(request.attemptId) {
        if (launched) return@LaunchedEffect
        launched = true
        if (!SumUpAPI.isLoggedIn()) {
            onResult(CardPaymentResult.Declined("Nicht bei SumUp angemeldet (Einstellungen → Kartenzahlung) – nichts abgebucht."))
            return@LaunchedEffect
        }
        val payment = SumUpPayment.builder()
            .total(SumUpResults.total(request.amountCents, request.tipCents))
            .currency(SumUpPayment.Currency.EUR)
            .apply { if (request.tipCents > 0) tip(SumUpResults.euros(request.tipCents)) }
            .title(request.title)
            .foreignTransactionId(request.reference)
            // Bei Verbindungsabbruch nachfragen statt „fehlgeschlagen“ zu melden → Ergebnis „unbekannt“ möglich
            .configureRetryPolicy(2_000, 60_000, true)
            .skipSuccessScreen()
            .build()
        launcher.launch(payment)
    }
}

private fun raw(data: Intent?): SumUpResults.Raw {
    if (data == null) return SumUpResults.Raw(resultCode = null)
    val info = IntentCompat.getParcelableExtra(data, SumUpAPI.Response.TX_INFO, TransactionInfo::class.java)
    return SumUpResults.Raw(
        resultCode = data.getIntExtra(SumUpAPI.Response.RESULT_CODE, -1).takeIf { it != -1 },
        message = data.getStringExtra(SumUpAPI.Response.MESSAGE),
        txCode = data.getStringExtra(SumUpAPI.Response.TX_CODE) ?: info?.transactionCode,
        status = info?.status,
        cardType = info?.card?.type,
        last4 = info?.card?.last4Digits,
        amount = info?.amount,
    )
}
