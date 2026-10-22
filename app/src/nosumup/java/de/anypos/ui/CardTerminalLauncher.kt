package de.anypos.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import de.anypos.AppContainer
import de.anypos.payment.CardPaymentRequest
import de.anypos.payment.CardPaymentResult

/** SumUp ist in diesem Build nicht enthalten: nichts senden, sicher „abgelehnt“ (nichts abgebucht). */
@Composable
fun CardTerminalLauncher(container: AppContainer, request: CardPaymentRequest, onResult: (CardPaymentResult) -> Unit) {
    LaunchedEffect(request.attemptId) {
        onResult(CardPaymentResult.Declined("SumUp ist in dieser Version nicht enthalten – es wurde nichts abgebucht."))
    }
}
