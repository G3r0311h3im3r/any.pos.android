package de.anypos.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import de.anypos.AppContainer
import de.anypos.payment.CardPaymentRequest
import de.anypos.payment.CardPaymentResult

/**
 * Startet das echte Kartenterminal. SumUp (Reader SDK) folgt in Etappe B; bis dahin wird nichts an ein Gerät
 * gesendet und die Zahlung als abgelehnt gemeldet (sicher: es wurde nichts abgebucht).
 */
@Composable
fun CardTerminalLauncher(container: AppContainer, request: CardPaymentRequest, onResult: (CardPaymentResult) -> Unit) {
    LaunchedEffect(request.attemptId) {
        onResult(CardPaymentResult.Declined("SumUp-Anbindung ist noch nicht eingebaut – es wurde nichts abgebucht."))
    }
}
